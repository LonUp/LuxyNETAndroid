package com.example.core

import com.example.data.local.ServerProfileEntity
import com.example.data.model.ProxyProtocol
import com.example.data.model.SecurityType
import com.example.data.model.TransportType
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class X25519KeyPair(
    val publicKeyBase64: String,
    val privateKeyBase64: String
)

sealed interface VaultImportResult {
    data class Success(
        val nodes: List<ServerProfileEntity>,
        val scheme: String,
        val expiresAtMs: Long?
    ) : VaultImportResult

    data class Expired(val expiresAtMs: Long) : VaultImportResult

    data class Error(val reason: String) : VaultImportResult
}

object EncryptedConfigVault {
    const val SCHEME_PBKDF2 = "PBKDF2-AES256GCM"
    const val SCHEME_X25519 = "X25519-HKDF-AES256GCM"
    const val PREFIX_ZSX = "luxy-zsx://"
    const val PREFIX_KAGE = "kage://"
    const val VAULT_REDACTED_VALUE = "<LOCKED-VAULT-REDACTED>"

    private const val FORMAT_ID = "luxyvpn-zsx"
    private const val FORMAT_VERSION = 1
    private const val PBKDF2_ITERATIONS = 65536

    private val P_25519 = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val A24_25519 = BigInteger.valueOf(121665)
    private val BASE_POINT_U = ByteArray(32).also { it[0] = 9 }
    private val random = SecureRandom()

    private fun randomBytes(len: Int): ByteArray = ByteArray(len).also { random.nextBytes(it) }

    private fun clampScalar(k: ByteArray) {
        k[0] = (k[0].toInt() and 248).toByte()
        k[31] = ((k[31].toInt() and 127) or 64).toByte()
    }

    private fun mod25519(x: BigInteger): BigInteger {
        val r = x.mod(P_25519)
        return if (r.signum() < 0) r.add(P_25519) else r
    }

    private fun decodeLittleEndian25519(bytes: ByteArray, maskHighBit: Boolean): BigInteger {
        val copy = bytes.copyOf(32)
        if (maskHighBit) {
            copy[31] = (copy[31].toInt() and 0x7F).toByte()
        }
        var res = BigInteger.ZERO
        for (i in 31 downTo 0) {
            res = res.shiftLeft(8).or(BigInteger.valueOf((copy[i].toInt() and 0xFF).toLong()))
        }
        return res
    }

    private fun encodeLittleEndian32(value: BigInteger): ByteArray {
        val out = ByteArray(32)
        var v = mod25519(value)
        for (i in 0 until 32) {
            out[i] = v.and(BigInteger.valueOf(0xFF)).toInt().toByte()
            v = v.shiftRight(8)
        }
        return out
    }

    fun x25519ScalarMult(kBytes: ByteArray, uBytes: ByteArray): ByteArray {
        val k = kBytes.copyOf(32)
        clampScalar(k)
        val u = decodeLittleEndian25519(uBytes, maskHighBit = true)
        val x1 = u
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = u
        var z3 = BigInteger.ONE
        var swap = 0

        for (t in 254 downTo 0) {
            val kt = (k[t / 8].toInt() ushr (t % 8)) and 1
            swap = swap xor kt
            if (swap != 0) {
                val tx = x2; x2 = x3; x3 = tx
                val tz = z2; z2 = z3; z3 = tz
            }
            swap = kt

            val a = mod25519(x2.add(z2))
            val aa = mod25519(a.multiply(a))
            val b = mod25519(x2.subtract(z2))
            val bb = mod25519(b.multiply(b))
            val e = mod25519(aa.subtract(bb))
            val c = mod25519(x3.add(z3))
            val d = mod25519(x3.subtract(z3))
            val da = mod25519(d.multiply(a))
            val cb = mod25519(c.multiply(b))

            val daPlusCb = mod25519(da.add(cb))
            val daMinusCb = mod25519(da.subtract(cb))
            x3 = mod25519(daPlusCb.multiply(daPlusCb))
            z3 = mod25519(x1.multiply(mod25519(daMinusCb.multiply(daMinusCb))))
            x2 = mod25519(aa.multiply(bb))
            z2 = mod25519(e.multiply(mod25519(aa.add(mod25519(A24_25519.multiply(e))))))
        }

        if (swap != 0) {
            x2 = x3
            z2 = z3
        }

        val invZ2 = z2.modInverse(P_25519)
        return encodeLittleEndian32(mod25519(x2.multiply(invZ2)))
    }

    fun generateX25519KeyPair(): X25519KeyPair {
        val priv = randomBytes(32)
        clampScalar(priv)
        val pub = x25519ScalarMult(priv, BASE_POINT_U)
        return X25519KeyPair(
            publicKeyBase64 = Base64Compat.encodeToString(pub, urlSafe = true, noPadding = true),
            privateKeyBase64 = Base64Compat.encodeToString(priv, urlSafe = true, noPadding = true)
        )
    }

    fun isVaultPayload(rawInput: String): Boolean {
        val trimmed = rawInput.trim()
        val lower = trimmed.lowercase()
        if (lower.startsWith(PREFIX_ZSX) ||
            lower.startsWith("luxyvpn://") ||
            lower.startsWith("zsx://") ||
            lower.startsWith(PREFIX_KAGE)
        ) {
            return true
        }
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return try {
                val obj = JSONObject(trimmed)
                val fmt = obj.optString("format", "")
                fmt == FORMAT_ID || fmt == "zsx" || fmt == "kage"
            } catch (_: Exception) {
                false
            }
        }
        return false
    }

    fun isVaultExpired(profile: ServerProfileEntity, nowMs: Long = System.currentTimeMillis()): Boolean {
        return profile.isLocked && profile.vaultExpiresAtMs != null && nowMs > profile.vaultExpiresAtMs
    }

    fun redactEndpointLabel(profile: ServerProfileEntity): String {
        return if (profile.isLocked) {
            "Locked Vault Endpoint • ${profile.transport}/${profile.security}"
        } else {
            "${profile.address}:${profile.port} • ${profile.transport}/${profile.security}"
        }
    }

    fun sanitizeLogText(text: String, activeProfile: ServerProfileEntity?): String {
        if (activeProfile == null || !activeProfile.isLocked || text.isEmpty()) return text
        var result = text
        val addrPort = if (activeProfile.address.isNotBlank()) "${activeProfile.address}:${activeProfile.port}" else ""
        val secrets = listOf(
            addrPort,
            activeProfile.address,
            activeProfile.sni,
            activeProfile.hostHeader,
            activeProfile.uuidOrPassword,
            activeProfile.publicKey,
            activeProfile.shortId,
            activeProfile.echConfigList,
            activeProfile.mldsa65Verify
        )
            .map { it.trim() }
            .filter { it.length >= 3 }
            .distinct()
            .sortedByDescending { it.length }

        for (secret in secrets) {
            result = result.replace(secret, VAULT_REDACTED_VALUE, ignoreCase = true)
        }
        return result
    }

    private fun buildAad(scheme: String, expiresAtMs: Long?): ByteArray =
        "$FORMAT_ID|v$FORMAT_VERSION|$scheme|${expiresAtMs ?: 0L}".toByteArray(Charsets.UTF_8)

    private fun derivePbkdf2Key(passphrase: String, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun deriveHkdfAesKey(ikm: ByteArray, salt: ByteArray, info: ByteArray): SecretKeySpec {
        val actualSalt = if (salt.isEmpty()) ByteArray(32) else salt
        val hmacExtract = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(actualSalt, "HmacSHA256"))
        }
        val prk = hmacExtract.doFinal(ikm)
        val hmacExpand = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(prk, "HmacSHA256"))
        }
        hmacExpand.update(info)
        hmacExpand.update(1.toByte())
        val okm = hmacExpand.doFinal().copyOf(32)
        return SecretKeySpec(okm, "AES")
    }

    private fun aesGcmEncrypt(
        key: SecretKeySpec,
        iv: ByteArray,
        aad: ByteArray,
        plaintext: ByteArray
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    private fun aesGcmDecrypt(
        key: SecretKeySpec,
        iv: ByteArray,
        aad: ByteArray,
        ciphertext: ByteArray
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    private fun serializeInnerPayload(nodes: List<ServerProfileEntity>, expiresAtMs: Long?): ByteArray {
        val arr = JSONArray()
        for (n in nodes) {
            arr.put(JSONObject().apply {
                put("name", n.name)
                put("protocol", n.protocol)
                put("address", n.address)
                put("port", n.port)
                put("uuidOrPassword", n.uuidOrPassword)
                put("encryption", n.encryption)
                put("transport", n.transport)
                put("security", n.security)
                put("sni", n.sni)
                put("alpn", n.alpn)
                put("fingerprint", n.fingerprint)
                put("publicKey", n.publicKey)
                put("shortId", n.shortId)
                put("path", n.path)
                put("hostHeader", n.hostHeader)
                put("serviceName", n.serviceName)
                put("flow", n.flow)
                put("allowInsecure", n.allowInsecure)
                put("cipherSuites", n.cipherSuites)
                put("finalMask", n.finalMask)
                put("echConfigList", n.echConfigList)
                put("echForceQuery", n.echForceQuery)
                put("mldsa65Verify", n.mldsa65Verify)
            })
        }
        val root = JSONObject().apply {
            put("createdAtMs", System.currentTimeMillis())
            if (expiresAtMs != null) put("expiresAtMs", expiresAtMs)
            put("lockPolicy", "LOCKED_NO_REVEAL")
            put("nodes", arr)
        }
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    private fun formatEnvelope(envelope: JSONObject, asUriEnvelope: Boolean): String {
        return if (asUriEnvelope) {
            PREFIX_ZSX + Base64Compat.encodeToString(
                envelope.toString().toByteArray(Charsets.UTF_8),
                urlSafe = true,
                noPadding = true
            )
        } else {
            envelope.toString(2)
        }
    }

    private fun unwrapEnvelope(raw: String): JSONObject? {
        val trimmed = raw.trim()
        val lower = trimmed.lowercase()
        val jsonStr = when {
            lower.startsWith(PREFIX_ZSX) ->
                String(Base64Compat.decode(trimmed.substring(PREFIX_ZSX.length)), Charsets.UTF_8)
            lower.startsWith("luxyvpn://") ->
                String(Base64Compat.decode(trimmed.substring("luxyvpn://".length)), Charsets.UTF_8)
            lower.startsWith("zsx://") ->
                String(Base64Compat.decode(trimmed.substring("zsx://".length)), Charsets.UTF_8)
            lower.startsWith(PREFIX_KAGE) ->
                String(Base64Compat.decode(trimmed.substring(PREFIX_KAGE.length)), Charsets.UTF_8)
            trimmed.startsWith("{") -> trimmed
            else -> return null
        }
        return try {
            JSONObject(jsonStr)
        } catch (_: Exception) {
            null
        }
    }

    fun exportVaultWithPassphrase(
        nodes: List<ServerProfileEntity>,
        passphrase: String,
        expiresAtMs: Long? = null,
        asUriEnvelope: Boolean = true
    ): String {
        require(nodes.isNotEmpty()) { "At least one node is required for vault export" }
        require(passphrase.trim().isNotEmpty()) { "Passphrase cannot be empty" }

        val salt = randomBytes(16)
        val iv = randomBytes(12)
        val key = derivePbkdf2Key(passphrase, salt)
        val aad = buildAad(SCHEME_PBKDF2, expiresAtMs)
        val plaintext = serializeInnerPayload(nodes, expiresAtMs)
        val ciphertext = aesGcmEncrypt(key, iv, aad, plaintext)

        val envelope = JSONObject().apply {
            put("format", FORMAT_ID)
            put("version", FORMAT_VERSION)
            put("scheme", SCHEME_PBKDF2)
            if (expiresAtMs != null) put("expiresAtMs", expiresAtMs)
            put("salt", Base64Compat.encodeToString(salt, urlSafe = true, noPadding = true))
            put("iv", Base64Compat.encodeToString(iv, urlSafe = true, noPadding = true))
            put("ciphertext", Base64Compat.encodeToString(ciphertext, urlSafe = true, noPadding = true))
        }
        return formatEnvelope(envelope, asUriEnvelope)
    }

    fun exportVaultWithX25519(
        nodes: List<ServerProfileEntity>,
        recipientPublicKeyBase64: String,
        expiresAtMs: Long? = null,
        asUriEnvelope: Boolean = true
    ): String {
        require(nodes.isNotEmpty()) { "At least one node is required for vault export" }
        val recipientPub = Base64Compat.decode(recipientPublicKeyBase64.trim())
        require(recipientPub.size == 32) { "X25519 recipient public key must be 32 bytes" }

        val ephemeralPriv = randomBytes(32)
        clampScalar(ephemeralPriv)
        val ephemeralPub = x25519ScalarMult(ephemeralPriv, BASE_POINT_U)
        val sharedSecret = x25519ScalarMult(ephemeralPriv, recipientPub)
        require(sharedSecret.any { it != 0.toByte() }) { "Invalid recipient X25519 key (all-zero shared secret)" }

        val salt = randomBytes(16)
        val iv = randomBytes(12)
        val aad = buildAad(SCHEME_X25519, expiresAtMs)
        val key = deriveHkdfAesKey(sharedSecret, salt, aad)
        val plaintext = serializeInnerPayload(nodes, expiresAtMs)
        val ciphertext = aesGcmEncrypt(key, iv, aad, plaintext)

        val envelope = JSONObject().apply {
            put("format", FORMAT_ID)
            put("version", FORMAT_VERSION)
            put("scheme", SCHEME_X25519)
            if (expiresAtMs != null) put("expiresAtMs", expiresAtMs)
            put("epk", Base64Compat.encodeToString(ephemeralPub, urlSafe = true, noPadding = true))
            put("salt", Base64Compat.encodeToString(salt, urlSafe = true, noPadding = true))
            put("iv", Base64Compat.encodeToString(iv, urlSafe = true, noPadding = true))
            put("ciphertext", Base64Compat.encodeToString(ciphertext, urlSafe = true, noPadding = true))
        }
        return formatEnvelope(envelope, asUriEnvelope)
    }

    fun importVault(
        rawPayload: String,
        passphrase: String? = null,
        recipientPrivateKeyBase64: String? = null,
        groupName: String = "Locked Vault",
        nowMs: Long = System.currentTimeMillis()
    ): VaultImportResult {
        return try {
            val envelope = unwrapEnvelope(rawPayload)
                ?: return VaultImportResult.Error("Unrecognized .zsx / .kage vault payload")
            val format = envelope.optString("format", "")
            if (format != FORMAT_ID && format != "zsx" && format != "kage") {
                return VaultImportResult.Error("Unsupported vault format '$format'")
            }
            val version = envelope.optInt("version", 0)
            if (version != FORMAT_VERSION) {
                return VaultImportResult.Error("Unsupported vault version $version")
            }
            val scheme = envelope.optString("scheme", "")
            val expiresAtMs = if (envelope.has("expiresAtMs") && !envelope.isNull("expiresAtMs")) {
                envelope.optLong("expiresAtMs", 0L).takeIf { it > 0L }
            } else {
                null
            }
            if (expiresAtMs != null && nowMs > expiresAtMs) {
                return VaultImportResult.Expired(expiresAtMs)
            }

            val salt = Base64Compat.decode(envelope.optString("salt", ""))
            val iv = Base64Compat.decode(envelope.optString("iv", ""))
            val ciphertext = Base64Compat.decode(envelope.optString("ciphertext", ""))
            if (iv.size != 12 || ciphertext.isEmpty()) {
                return VaultImportResult.Error("Corrupted vault ciphertext or IV")
            }

            val aad = buildAad(scheme, expiresAtMs)
            val key = when (scheme) {
                SCHEME_PBKDF2 -> {
                    if (passphrase.isNullOrBlank()) {
                        return VaultImportResult.Error("Passphrase required to unlock PBKDF2 vault")
                    }
                    derivePbkdf2Key(passphrase, salt)
                }
                SCHEME_X25519 -> {
                    if (recipientPrivateKeyBase64.isNullOrBlank()) {
                        return VaultImportResult.Error("X25519 private key required to unlock sealed vault")
                    }
                    val priv = Base64Compat.decode(recipientPrivateKeyBase64.trim())
                    val epk = Base64Compat.decode(envelope.optString("epk", ""))
                    if (priv.size != 32 || epk.size != 32) {
                        return VaultImportResult.Error("Invalid X25519 key material")
                    }
                    val sharedSecret = x25519ScalarMult(priv, epk)
                    if (sharedSecret.all { it == 0.toByte() }) {
                        return VaultImportResult.Error("Invalid X25519 key exchange")
                    }
                    deriveHkdfAesKey(sharedSecret, salt, aad)
                }
                else -> return VaultImportResult.Error("Unsupported vault scheme '$scheme'")
            }

            val plaintextBytes = try {
                aesGcmDecrypt(key, iv, aad, ciphertext)
            } catch (_: Exception) {
                return VaultImportResult.Error("Authentication failed: wrong key or tampered vault")
            }

            val payloadJson = JSONObject(String(plaintextBytes, Charsets.UTF_8))
            val innerExpiresAt = if (payloadJson.has("expiresAtMs") && !payloadJson.isNull("expiresAtMs")) {
                payloadJson.optLong("expiresAtMs", 0L).takeIf { it > 0L }
            } else {
                null
            }
            if (innerExpiresAt != null && nowMs > innerExpiresAt) {
                return VaultImportResult.Expired(innerExpiresAt)
            }

            val nodesArr = payloadJson.optJSONArray("nodes") ?: JSONArray()
            val imported = mutableListOf<ServerProfileEntity>()
            for (i in 0 until nodesArr.length()) {
                val obj = nodesArr.optJSONObject(i) ?: continue
                val addr = obj.optString("address", "").trim()
                val port = obj.optInt("port", 443)
                if (addr.isEmpty() || port !in 1..65535) continue
                imported += ServerProfileEntity(
                    name = obj.optString("name", "Locked Node").trim().ifEmpty { "Locked Node" },
                    protocol = ProxyProtocol.parse(obj.optString("protocol", "VLESS")).name,
                    address = addr,
                    port = port,
                    uuidOrPassword = obj.optString("uuidOrPassword", ""),
                    encryption = obj.optString("encryption", "none"),
                    transport = TransportType.parse(obj.optString("transport", "TCP")).name,
                    security = SecurityType.parse(obj.optString("security", "TLS")).name,
                    sni = obj.optString("sni", ""),
                    alpn = obj.optString("alpn", ""),
                    fingerprint = obj.optString("fingerprint", "chrome"),
                    publicKey = obj.optString("publicKey", ""),
                    shortId = obj.optString("shortId", ""),
                    path = obj.optString("path", ""),
                    hostHeader = obj.optString("hostHeader", ""),
                    serviceName = obj.optString("serviceName", ""),
                    flow = obj.optString("flow", ""),
                    allowInsecure = obj.optBoolean("allowInsecure", false),
                    cipherSuites = obj.optString("cipherSuites", ""),
                    finalMask = obj.optString("finalMask", ""),
                    echConfigList = obj.optString("echConfigList", ""),
                    echForceQuery = obj.optString("echForceQuery", "none"),
                    mldsa65Verify = obj.optString("mldsa65Verify", ""),
                    groupName = groupName,
                    rawUri = "",
                    isLocked = true,
                    vaultExpiresAtMs = innerExpiresAt ?: expiresAtMs,
                    vaultKeyScheme = scheme
                )
            }

            if (imported.isEmpty()) {
                VaultImportResult.Error("Vault unlocked cleanly but contained no valid proxy profiles")
            } else {
                VaultImportResult.Success(
                    nodes = imported,
                    scheme = scheme,
                    expiresAtMs = innerExpiresAt ?: expiresAtMs
                )
            }
        } catch (e: Exception) {
            VaultImportResult.Error(e.message ?: "Failed to unlock vault")
        }
    }
}
