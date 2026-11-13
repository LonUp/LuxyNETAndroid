package com.example.core

import android.util.Base64
import com.example.data.local.ServerProfileEntity
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.nio.charset.StandardCharsets
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

sealed class VaultImportResult {
    data class Success(
        val nodes: List<ServerProfileEntity>,
        val scheme: String,
        val expiresAtMs: Long?
    ) : VaultImportResult()

    data class Expired(val expiresAtMs: Long) : VaultImportResult()
    data class Error(val reason: String) : VaultImportResult()
}

/**
 * Zero-Knowledge Encrypted Config Vault (.zsx / .kage).
 *
 * Supports:
 * - Passphrase encryption via PBKDF2WithHmacSHA256 (65,536 iters) + AES-256-GCM
 * - Asymmetric sealed encryption via RFC 7748 X25519 + HKDF-SHA256 + AES-256-GCM
 *   (pure-BigInteger X25519 for universal Android minSdk 24+ and JVM test compatibility)
 *
 * Binds `format|version|scheme|expiresAtMs` in AES-GCM AAD to prevent metadata tampering,
 * rejects expired payloads, and marks imported profiles `isLocked = true` for strict
 * UI / JSON Inspector / log redaction.
 */
object EncryptedConfigVault {

    const val SCHEME_PBKDF2 = "PBKDF2-AES256GCM"
    const val SCHEME_X25519 = "X25519-HKDF-AES256GCM"
    const val PREFIX_ZSX = "luxy-zsx://"
    const val PREFIX_KAGE = "kage://"
    const val REDACTED_VALUE = "<LOCKED-VAULT-REDACTED>"

    private const val FORMAT_ID = "luxyvpn-zsx"
    private const val FORMAT_VERSION = 1
    private const val PBKDF2_ITERATIONS = 65_536
    private const val GCM_TAG_BITS = 128

    private val P_25519: BigInteger =
        BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val A24_25519: BigInteger = BigInteger.valueOf(121665)
    private val BASE_POINT_U: ByteArray = ByteArray(32).apply { this[0] = 9 }
    private val secureRandom = SecureRandom()

    fun isVaultPayload(rawInput: String): Boolean {
        val trimmed = rawInput.trim()
        if (trimmed.startsWith(PREFIX_ZSX, ignoreCase = true) ||
            trimmed.startsWith("luxyvpn://", ignoreCase = true) ||
            trimmed.startsWith("zsx://", ignoreCase = true) ||
            trimmed.startsWith(PREFIX_KAGE, ignoreCase = true)
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

    fun isVaultExpired(
        profile: ServerProfileEntity,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
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
        if (activeProfile == null || !activeProfile.isLocked) return text
        var result = text
        listOf(
            activeProfile.address,
            activeProfile.sni,
            activeProfile.hostHeader,
            activeProfile.uuidOrPassword,
            activeProfile.publicKey
        )
            .filter { it.isNotBlank() && it.length >= 3 }
            .forEach { secret ->
                result = result.replace(secret, REDACTED_VALUE)
            }
        return result
    }

    fun generateX25519KeyPair(): X25519KeyPair {
        val priv = ByteArray(32)
        secureRandom.nextBytes(priv)
        clampScalar(priv)
        val pub = x25519ScalarMult(priv, BASE_POINT_U)
        return X25519KeyPair(
            publicKeyBase64 = encodeUrlB64(pub),
            privateKeyBase64 = encodeUrlB64(priv)
        )
    }

    fun exportWithPassphrase(
        nodes: List<ServerProfileEntity>,
        passphrase: String,
        expiresAtMs: Long? = null,
        asUriEnvelope: Boolean = true
    ): String {
        require(nodes.isNotEmpty()) { "At least one node is required for vault export" }
        require(passphrase.isNotBlank()) { "Passphrase cannot be empty" }

        val salt = ByteArray(16).also { secureRandom.nextBytes(it) }
        val iv = ByteArray(12).also { secureRandom.nextBytes(it) }
        val key = derivePbkdf2Key(passphrase, salt)
        val aad = buildAad(SCHEME_PBKDF2, expiresAtMs)
        val plaintext = serializeInnerPayload(nodes, expiresAtMs)
        val ciphertext = aesGcmEncrypt(key, iv, aad, plaintext)

        val envelope = JSONObject().apply {
            put("format", FORMAT_ID)
            put("version", FORMAT_VERSION)
            put("scheme", SCHEME_PBKDF2)
            if (expiresAtMs != null) put("expiresAtMs", expiresAtMs)
            put("salt", encodeUrlB64(salt))
            put("iv", encodeUrlB64(iv))
            put("ciphertext", encodeUrlB64(ciphertext))
        }
        return formatEnvelope(envelope, asUriEnvelope)
    }

    fun exportWithX25519(
        nodes: List<ServerProfileEntity>,
        recipientPublicKeyBase64: String,
        expiresAtMs: Long? = null,
        asUriEnvelope: Boolean = true
    ): String {
        require(nodes.isNotEmpty()) { "At least one node is required for vault export" }
        val recipientPub = decodeUrlB64(recipientPublicKeyBase64.trim())
        require(recipientPub.size == 32) { "X25519 recipient public key must be 32 bytes" }

        val ephemeralPriv = ByteArray(32).also {
            secureRandom.nextBytes(it)
            clampScalar(it)
        }
        val ephemeralPub = x25519ScalarMult(ephemeralPriv, BASE_POINT_U)
        val sharedSecret = x25519ScalarMult(ephemeralPriv, recipientPub)
        require(sharedSecret.any { it.toInt() != 0 }) { "Invalid recipient X25519 key (all-zero shared secret)" }

        val salt = ByteArray(16).also { secureRandom.nextBytes(it) }
        val iv = ByteArray(12).also { secureRandom.nextBytes(it) }
        val aad = buildAad(SCHEME_X25519, expiresAtMs)
        val key = hkdfSha256(ikm = sharedSecret, salt = salt, info = aad, length = 32)
        val plaintext = serializeInnerPayload(nodes, expiresAtMs)
        val ciphertext = aesGcmEncrypt(key, iv, aad, plaintext)

        val envelope = JSONObject().apply {
            put("format", FORMAT_ID)
            put("version", FORMAT_VERSION)
            put("scheme", SCHEME_X25519)
            if (expiresAtMs != null) put("expiresAtMs", expiresAtMs)
            put("epk", encodeUrlB64(ephemeralPub))
            put("salt", encodeUrlB64(salt))
            put("iv", encodeUrlB64(iv))
            put("ciphertext", encodeUrlB64(ciphertext))
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
            val envelopeJson = unwrapEnvelope(rawPayload)
                ?: return VaultImportResult.Error("Unrecognized .zsx / .kage vault payload")

            val format = envelopeJson.optString("format", "")
            if (format != FORMAT_ID && format != "zsx" && format != "kage") {
                return VaultImportResult.Error("Unsupported vault format '$format'")
            }
            val version = envelopeJson.optInt("version", 0)
            if (version != FORMAT_VERSION) {
                return VaultImportResult.Error("Unsupported vault version $version")
            }

            val scheme = envelopeJson.optString("scheme", "")
            val expiresAtMs = if (envelopeJson.has("expiresAtMs") && !envelopeJson.isNull("expiresAtMs")) {
                envelopeJson.optLong("expiresAtMs")
            } else null

            if (expiresAtMs != null && nowMs > expiresAtMs) {
                return VaultImportResult.Expired(expiresAtMs)
            }

            val salt = decodeUrlB64(envelopeJson.optString("salt", ""))
            val iv = decodeUrlB64(envelopeJson.optString("iv", ""))
            val ciphertext = decodeUrlB64(envelopeJson.optString("ciphertext", ""))
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
                    val priv = decodeUrlB64(recipientPrivateKeyBase64.trim())
                    val epk = decodeUrlB64(envelopeJson.optString("epk", ""))
                    if (priv.size != 32 || epk.size != 32) {
                        return VaultImportResult.Error("Invalid X25519 key material")
                    }
                    val sharedSecret = x25519ScalarMult(priv, epk)
                    if (sharedSecret.all { it.toInt() == 0 }) {
                        return VaultImportResult.Error("Invalid X25519 key exchange")
                    }
                    hkdfSha256(ikm = sharedSecret, salt = salt, info = aad, length = 32)
                }
                else -> return VaultImportResult.Error("Unsupported vault scheme '$scheme'")
            }

            val plaintext = aesGcmDecrypt(key, iv, aad, ciphertext)
            val payloadJson = JSONObject(String(plaintext, StandardCharsets.UTF_8))

            val innerExpiresAt = if (payloadJson.has("expiresAtMs") && !payloadJson.isNull("expiresAtMs")) {
                payloadJson.optLong("expiresAtMs")
            } else null
            if (innerExpiresAt != null && nowMs > innerExpiresAt) {
                return VaultImportResult.Expired(innerExpiresAt)
            }

            val nodesArr = payloadJson.optJSONArray("nodes") ?: JSONArray()
            val importedNodes = mutableListOf<ServerProfileEntity>()
            for (i in 0 until nodesArr.length()) {
                val obj = nodesArr.optJSONObject(i) ?: continue
                deserializeLockedNode(
                    obj = obj,
                    groupName = groupName,
                    expiresAtMs = innerExpiresAt ?: expiresAtMs,
                    scheme = scheme
                )?.let { importedNodes.add(it) }
            }

            if (importedNodes.isEmpty()) {
                VaultImportResult.Error("Vault unlocked cleanly but contained no valid proxy profiles")
            } else {
                VaultImportResult.Success(
                    nodes = importedNodes,
                    scheme = scheme,
                    expiresAtMs = innerExpiresAt ?: expiresAtMs
                )
            }
        } catch (_: javax.crypto.AEADBadTagException) {
            VaultImportResult.Error("Authentication failed: wrong key or tampered vault")
        } catch (e: Exception) {
            VaultImportResult.Error(e.localizedMessage ?: "Failed to unlock vault")
        }
    }

    private fun formatEnvelope(envelope: JSONObject, asUriEnvelope: Boolean): String {
        val compact = envelope.toString()
        return if (asUriEnvelope) {
            PREFIX_ZSX + encodeUrlB64(compact.toByteArray(StandardCharsets.UTF_8))
        } else {
            envelope.toString(2)
        }
    }

    private fun unwrapEnvelope(raw: String): JSONObject? {
        val trimmed = raw.trim()
        val jsonStr = when {
            trimmed.startsWith(PREFIX_ZSX, ignoreCase = true) -> {
                String(decodeUrlB64(trimmed.substring(PREFIX_ZSX.length)), StandardCharsets.UTF_8)
            }
            trimmed.startsWith("luxyvpn://", ignoreCase = true) -> {
                String(decodeUrlB64(trimmed.substring("luxyvpn://".length)), StandardCharsets.UTF_8)
            }
            trimmed.startsWith("zsx://", ignoreCase = true) -> {
                String(decodeUrlB64(trimmed.substring("zsx://".length)), StandardCharsets.UTF_8)
            }
            trimmed.startsWith(PREFIX_KAGE, ignoreCase = true) -> {
                String(decodeUrlB64(trimmed.substring(PREFIX_KAGE.length)), StandardCharsets.UTF_8)
            }
            trimmed.startsWith("{") -> trimmed
            else -> return null
        }
        return JSONObject(jsonStr)
    }

    private fun serializeInnerPayload(
        nodes: List<ServerProfileEntity>,
        expiresAtMs: Long?
    ): ByteArray {
        val arr = JSONArray()
        nodes.forEach { node ->
            arr.put(JSONObject().apply {
                put("name", node.name)
                put("protocol", node.protocol)
                put("address", node.address)
                put("port", node.port)
                put("uuidOrPassword", node.uuidOrPassword)
                put("encryption", node.encryption)
                put("transport", node.transport)
                put("security", node.security)
                put("sni", node.sni)
                put("alpn", node.alpn)
                put("fingerprint", node.fingerprint)
                put("publicKey", node.publicKey)
                put("shortId", node.shortId)
                put("path", node.path)
                put("hostHeader", node.hostHeader)
                put("serviceName", node.serviceName)
                put("flow", node.flow)
                put("allowInsecure", node.allowInsecure)
                put("cipherSuites", node.cipherSuites)
                put("finalMask", node.finalMask)
                put("echConfigList", node.echConfigList)
                put("echForceQuery", node.echForceQuery)
                put("mldsa65Verify", node.mldsa65Verify)
            })
        }
        val root = JSONObject().apply {
            put("createdAtMs", System.currentTimeMillis())
            if (expiresAtMs != null) put("expiresAtMs", expiresAtMs)
            put("lockPolicy", "LOCKED_NO_REVEAL")
            put("nodes", arr)
        }
        return root.toString().toByteArray(StandardCharsets.UTF_8)
    }

    private fun deserializeLockedNode(
        obj: JSONObject,
        groupName: String,
        expiresAtMs: Long?,
        scheme: String
    ): ServerProfileEntity? {
        val address = obj.optString("address", "").trim()
        val port = obj.optInt("port", 443)
        if (address.isEmpty() || port !in 1..65535) return null
        return ServerProfileEntity(
            name = obj.optString("name", "Locked Node").ifBlank { "Locked Node" },
            protocol = obj.optString("protocol", "VLESS"),
            address = address,
            port = port,
            uuidOrPassword = obj.optString("uuidOrPassword", ""),
            encryption = obj.optString("encryption", "none"),
            transport = obj.optString("transport", "TCP"),
            security = obj.optString("security", "TLS"),
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
            vaultExpiresAtMs = expiresAtMs,
            vaultKeyScheme = scheme
        )
    }

    private fun buildAad(scheme: String, expiresAtMs: Long?): ByteArray =
        "$FORMAT_ID|v$FORMAT_VERSION|$scheme|${expiresAtMs ?: 0L}".toByteArray(StandardCharsets.UTF_8)

    private fun derivePbkdf2Key(passphrase: String, salt: ByteArray): ByteArray {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        return factory.generateSecret(spec).encoded
    }

    private fun hkdfSha256(
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val actualSalt = if (salt.isEmpty()) ByteArray(32) else salt
        mac.init(SecretKeySpec(actualSalt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)

        val result = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            val copyLen = minOf(t.size, length - offset)
            System.arraycopy(t, 0, result, offset, copyLen)
            offset += copyLen
            counter++
        }
        return result
    }

    private fun aesGcmEncrypt(
        keyBytes: ByteArray,
        iv: ByteArray,
        aad: ByteArray,
        plaintext: ByteArray
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    private fun aesGcmDecrypt(
        keyBytes: ByteArray,
        iv: ByteArray,
        aad: ByteArray,
        ciphertext: ByteArray
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    /**
     * Pure-Kotlin RFC 7748 X25519 scalar multiplication on Montgomery curve25519.
     */
    internal fun x25519ScalarMult(kBytes: ByteArray, uBytes: ByteArray): ByteArray {
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
            val kt = (k[t / 8].toInt() shr (t % 8)) and 1
            swap = swap xor kt
            if (swap != 0) {
                val tx = x2; x2 = x3; x3 = tx
                val tz = z2; z2 = z3; z3 = tz
            }
            swap = kt

            val a = x2.add(z2).mod(P_25519)
            val aa = a.multiply(a).mod(P_25519)
            val b = x2.subtract(z2).mod(P_25519)
            val bb = b.multiply(b).mod(P_25519)
            val e = aa.subtract(bb).mod(P_25519)
            val c = x3.add(z3).mod(P_25519)
            val d = x3.subtract(z3).mod(P_25519)
            val da = d.multiply(a).mod(P_25519)
            val cb = c.multiply(b).mod(P_25519)

            val daPlusCb = da.add(cb).mod(P_25519)
            val daMinusCb = da.subtract(cb).mod(P_25519)
            x3 = daPlusCb.multiply(daPlusCb).mod(P_25519)
            z3 = x1.multiply(daMinusCb.multiply(daMinusCb).mod(P_25519)).mod(P_25519)
            x2 = aa.multiply(bb).mod(P_25519)
            z2 = e.multiply(aa.add(A24_25519.multiply(e).mod(P_25519)).mod(P_25519)).mod(P_25519)
        }

        if (swap != 0) {
            x2 = x3
            z2 = z3
        }

        val invZ2 = z2.modInverse(P_25519)
        val result = x2.multiply(invZ2).mod(P_25519)
        return encodeLittleEndian32(result)
    }

    private fun clampScalar(k: ByteArray) {
        k[0] = (k[0].toInt() and 248).toByte()
        k[31] = (k[31].toInt() and 127).toByte()
        k[31] = (k[31].toInt() or 64).toByte()
    }

    private fun decodeLittleEndian25519(bytes: ByteArray, maskHighBit: Boolean): BigInteger {
        val copy = bytes.copyOf(32)
        if (maskHighBit) {
            copy[31] = (copy[31].toInt() and 0x7F).toByte()
        }
        val be = ByteArray(33)
        for (i in 0 until 32) {
            be[32 - i] = copy[i]
        }
        return BigInteger(1, be)
    }

    private fun encodeLittleEndian32(value: BigInteger): ByteArray {
        val be = value.toByteArray()
        val out = ByteArray(32)
        for (i in 0 until minOf(32, be.size)) {
            out[i] = be[be.size - 1 - i]
        }
        return out
    }

    private fun encodeUrlB64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)

    private fun decodeUrlB64(str: String): ByteArray {
        val cleaned = str.trim().replace('-', '+').replace('_', '/')
        val padded = when (cleaned.length % 4) {
            2 -> "$cleaned=="
            3 -> "$cleaned="
            else -> cleaned
        }
        return Base64.decode(padded, Base64.DEFAULT)
    }
}
