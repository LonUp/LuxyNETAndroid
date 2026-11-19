package com.example.core

import com.example.data.local.ServerProfileEntity
import com.example.data.local.SubscriptionEntity
import com.example.data.model.ErrorCategory
import com.example.data.model.HealthStatus
import com.example.data.model.SourceType
import com.example.data.model.StabilitySettings
import com.example.data.model.ThemeCodec
import com.example.data.model.ThemeConfig
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Subscription-owned profile preferences saved separately from the subscription's transient nodes. */
data class SubscriptionNodePreference(
    val subscriptionId: String,
    val canonicalFingerprint: String,
    val isEnabled: Boolean,
    val isPinned: Boolean,
    val manualCountryCode: String?
)

data class BackupSettings(
    val selectedCountryCode: String?,
    val manualConfigsEnabledForConnection: Boolean,
    val language: String,
    val theme: ThemeConfig,
    /** Present only when the active profile is included in the portable manual profile list. */
    val activeProfileId: String? = null
)

data class BackupPayload(
    val createdAtMs: Long,
    val appVersion: String,
    val manualProfiles: List<ServerProfileEntity>,
    val subscriptions: List<SubscriptionEntity>,
    val subscriptionNodePreferences: List<SubscriptionNodePreference>,
    val settings: BackupSettings
)

/** Versioned, authenticated encryption for portable app configuration backups. */
object LuxyBackupManager {
    const val CURRENT_VERSION = 1
    const val FORMAT = "luxynet-backup"
    const val KDF = "PBKDF2WithHmacSHA256"
    const val ITERATIONS = 100_000
    const val CIPHER_NAME = "AES-256-GCM"

    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val AAD = "luxynet-backup|v1|PBKDF2WithHmacSHA256|100000"
    private val random = SecureRandom()

    fun exportEncryptedBackup(
        passphrase: String,
        profiles: List<ServerProfileEntity>,
        subscriptions: List<SubscriptionEntity>,
        settings: StabilitySettings,
        appVersion: String
    ): Result<String> = runCatching {
        require(passphrase.length >= 6) { "Passphrase must contain at least 6 characters" }

        val manualProfiles = profiles.filter {
            it.sourceType == SourceType.MANUAL.name || it.sourceType == SourceType.LEGACY_UNCLASSIFIED.name
        }
        val exportedIds = manualProfiles.mapTo(HashSet()) { it.id }
        val settingsJson = JSONObject().apply {
            put("selectedCountryCode", ServerCountryResolver.normalizeCode(settings.selectedCountryCode).jsonValue())
            put("manualConfigsEnabledForConnection", settings.manualConfigsEnabledForConnection)
            put("language", settings.language)
            put("theme", ThemeCodec.encode(settings.theme))
            settings.activeProfileId?.takeIf { it in exportedIds }?.let { put("activeProfileId", it) }
        }

        val subscriptionIds = subscriptions.mapTo(HashSet()) { it.id }
        val preferences = profiles.asSequence()
            .filter { it.sourceType == SourceType.SUBSCRIPTION.name && it.subscriptionId != null && it.subscriptionId in subscriptionIds }
            .filter { !it.isEnabled || it.isPinned || it.manualCountryCode != null }
            .map { profile ->
                SubscriptionNodePreference(
                    subscriptionId = profile.subscriptionId!!,
                    canonicalFingerprint = profile.canonicalFingerprint.ifBlank { ConfigFingerprint.compute(profile) },
                    isEnabled = profile.isEnabled,
                    isPinned = profile.isPinned,
                    manualCountryCode = ServerCountryResolver.normalizeCode(profile.manualCountryCode)
                )
            }
            .toList()

        val data = JSONObject().apply {
            put("manualProfiles", JSONArray().apply { manualProfiles.forEach { put(profileJson(it)) } })
            put("subscriptions", JSONArray().apply { subscriptions.forEach { put(subscriptionJson(it)) } })
            put("subscriptionNodePreferences", JSONArray().apply { preferences.forEach { put(preferenceJson(it)) } })
            put("settings", settingsJson)
        }
        val createdAtMs = System.currentTimeMillis()
        val payload = JSONObject().apply {
            put("format", FORMAT)
            put("version", CURRENT_VERSION)
            put("createdAtMs", createdAtMs)
            put("appVersion", appVersion)
            put("payloadSha256", sha256Hex(canonicalJson(data).toByteArray(Charsets.UTF_8)))
            put("manualProfiles", data.getJSONArray("manualProfiles"))
            put("subscriptions", data.getJSONArray("subscriptions"))
            put("subscriptionNodePreferences", data.getJSONArray("subscriptionNodePreferences"))
            put("settings", settingsJson)
        }.toString().toByteArray(Charsets.UTF_8)

        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val key = deriveKey(passphrase, salt)
        val ciphertext = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            updateAAD(AAD.toByteArray(Charsets.UTF_8))
            doFinal(payload)
        }
        JSONObject().apply {
            put("format", FORMAT)
            put("version", CURRENT_VERSION)
            put("kdf", KDF)
            put("iterations", ITERATIONS)
            put("cipher", CIPHER_NAME)
            put("salt", encodeBase64Url(salt))
            put("iv", encodeBase64Url(iv))
            put("ciphertext", encodeBase64Url(ciphertext))
        }.toString()
    }

    fun importEncryptedBackup(rawBackup: String, passphrase: String): Result<BackupPayload> = runCatching {
        require(passphrase.length >= 6) { "Passphrase must contain at least 6 characters" }
        val envelope = JSONObject(rawBackup)
        require(envelope.getString("format") == FORMAT) { "Unsupported backup format" }
        val envelopeVersion = envelope.getInt("version")
        require(envelopeVersion in 1..CURRENT_VERSION) { "Unsupported backup version" }
        require(envelope.getString("kdf") == KDF) { "Unsupported backup key derivation" }
        require(envelope.getInt("iterations") == ITERATIONS) { "Unsupported backup key derivation parameters" }
        require(envelope.getString("cipher") == CIPHER_NAME) { "Unsupported backup cipher" }

        val salt = decodeBase64Url(envelope.getString("salt"))
        val iv = decodeBase64Url(envelope.getString("iv"))
        val ciphertext = decodeBase64Url(envelope.getString("ciphertext"))
        require(salt.size == SALT_BYTES && iv.size == IV_BYTES && ciphertext.size >= TAG_BITS / 8) {
            "Malformed backup encryption data"
        }

        val key = deriveKey(passphrase, salt)
        val plaintext = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            updateAAD(AAD.toByteArray(Charsets.UTF_8))
            doFinal(ciphertext)
        }
        val payload = JSONObject(String(plaintext, Charsets.UTF_8))
        require(payload.getString("format") == FORMAT) { "Unsupported backup payload" }
        val payloadVersion = payload.getInt("version")
        require(payloadVersion in 1..CURRENT_VERSION && payloadVersion == envelopeVersion) {
            "Unsupported backup payload version"
        }

        val manualProfilesJson = payload.getJSONArray("manualProfiles")
        val subscriptionsJson = payload.getJSONArray("subscriptions")
        val preferencesJson = payload.getJSONArray("subscriptionNodePreferences")
        val settingsJson = payload.getJSONObject("settings")
        val canonicalData = JSONObject().apply {
            put("manualProfiles", manualProfilesJson)
            put("subscriptions", subscriptionsJson)
            put("subscriptionNodePreferences", preferencesJson)
            put("settings", settingsJson)
        }
        val digestText = payload.getString("payloadSha256")
        require(digestText.matches(Regex("[0-9a-fA-F]{64}"))) { "Malformed backup integrity digest" }
        val expectedDigest = decodeHex(digestText)
        val actualDigest = MessageDigest.getInstance("SHA-256")
            .digest(canonicalJson(canonicalData).toByteArray(Charsets.UTF_8))
        require(MessageDigest.isEqual(expectedDigest, actualDigest)) { "Backup integrity verification failed" }

        val profiles = buildList(manualProfilesJson.length()) {
            for (index in 0 until manualProfilesJson.length()) {
                val raw = manualProfilesJson.getJSONObject(index)
                val profile = parseProfile(raw)
                val sanitized = ProtocolGuardrails.sanitizeProfileForSave(profile)
                require(ClientPolicy.isSupported(sanitized)) { "Backup contains an unsupported profile" }
                add(sanitized)
            }
        }
        val subscriptions = buildList(subscriptionsJson.length()) {
            for (index in 0 until subscriptionsJson.length()) {
                add(parseSubscription(subscriptionsJson.getJSONObject(index)))
            }
        }
        val preferences = buildList(preferencesJson.length()) {
            for (index in 0 until preferencesJson.length()) {
                add(parsePreference(preferencesJson.getJSONObject(index)))
            }
        }
        val settings = parseSettings(settingsJson, profiles.mapTo(HashSet()) { it.id })
        BackupPayload(
            createdAtMs = payload.getLong("createdAtMs"),
            appVersion = payload.getString("appVersion"),
            manualProfiles = profiles,
            subscriptions = subscriptions,
            subscriptionNodePreferences = preferences,
            settings = settings
        )
    }

    private fun deriveKey(passphrase: String, salt: ByteArray): SecretKeySpec {
        val passwordChars = passphrase.toCharArray()
        val spec = try {
            PBEKeySpec(passwordChars, salt, ITERATIONS, 256)
        } finally {
            passwordChars.fill('\u0000')
        }
        val keyBytes = try {
            SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        return try {
            SecretKeySpec(keyBytes, "AES")
        } finally {
            keyBytes.fill(0)
        }
    }

    private fun profileJson(profile: ServerProfileEntity): JSONObject = JSONObject().apply {
        put("id", profile.id)
        put("name", profile.name)
        put("protocol", profile.protocol)
        put("address", profile.address)
        put("port", profile.port)
        put("uuidOrPassword", profile.uuidOrPassword)
        put("encryption", profile.encryption)
        put("transport", profile.transport)
        put("security", profile.security)
        put("sni", profile.sni)
        put("alpn", profile.alpn)
        put("fingerprint", profile.fingerprint)
        put("publicKey", profile.publicKey)
        put("shortId", profile.shortId)
        put("path", profile.path)
        put("hostHeader", profile.hostHeader)
        put("serviceName", profile.serviceName)
        put("flow", profile.flow)
        put("allowInsecure", profile.allowInsecure)
        put("cipherSuites", profile.cipherSuites)
        put("finalMask", profile.finalMask)
        put("echConfigList", profile.echConfigList)
        put("echForceQuery", profile.echForceQuery)
        put("mldsa65Verify", profile.mldsa65Verify)
        put("groupName", profile.groupName)
        put("createdAt", profile.createdAt)
        put("isLocked", profile.isLocked)
        put("vaultExpiresAtMs", profile.vaultExpiresAtMs.jsonValue())
        put("vaultKeyScheme", profile.vaultKeyScheme)
        put("featureOverrides", profile.featureOverrides)
        put("sourceType", profile.sourceType)
        put("canonicalFingerprint", profile.canonicalFingerprint.ifBlank { ConfigFingerprint.compute(profile) })
        put("isEnabled", profile.isEnabled)
        put("isPinned", profile.isPinned)
        put("manualCountryCode", ServerCountryResolver.normalizeCode(profile.manualCountryCode).jsonValue())
        put("disabledAt", profile.disabledAt.jsonValue())
    }

    private fun subscriptionJson(subscription: SubscriptionEntity): JSONObject = JSONObject().apply {
        put("id", subscription.id)
        put("name", subscription.name)
        put("url", subscription.url)
        put("autoUpdateHours", subscription.autoUpdateHours)
        put("createdAt", subscription.createdAt)
        put("profileTitle", subscription.profileTitle.jsonValue())
        put("isEnabledForConnection", subscription.isEnabledForConnection)
    }

    private fun preferenceJson(preference: SubscriptionNodePreference): JSONObject = JSONObject().apply {
        put("subscriptionId", preference.subscriptionId)
        put("canonicalFingerprint", preference.canonicalFingerprint)
        put("isEnabled", preference.isEnabled)
        put("isPinned", preference.isPinned)
        put("manualCountryCode", preference.manualCountryCode.jsonValue())
    }

    private fun parseProfile(json: JSONObject): ServerProfileEntity {
        val sourceType = json.requiredString("sourceType")
        require(sourceType == SourceType.MANUAL.name || sourceType == SourceType.LEGACY_UNCLASSIFIED.name) {
            "Backup contains a non-manual profile"
        }
        val parsed = ServerProfileEntity(
            id = json.requiredString("id").also { require(it.isNotBlank() && it.length <= 256) },
            name = json.requiredString("name"),
            protocol = json.requiredString("protocol"),
            address = json.requiredString("address"),
            port = json.requiredInt("port"),
            uuidOrPassword = json.requiredString("uuidOrPassword"),
            encryption = json.requiredString("encryption"),
            transport = json.requiredString("transport"),
            security = json.requiredString("security"),
            sni = json.requiredString("sni"),
            alpn = json.requiredString("alpn"),
            fingerprint = json.requiredString("fingerprint"),
            publicKey = json.requiredString("publicKey"),
            shortId = json.requiredString("shortId"),
            path = json.requiredString("path"),
            hostHeader = json.requiredString("hostHeader"),
            serviceName = json.requiredString("serviceName"),
            flow = json.requiredString("flow"),
            allowInsecure = json.requiredBoolean("allowInsecure"),
            cipherSuites = json.requiredString("cipherSuites"),
            finalMask = json.requiredString("finalMask"),
            echConfigList = json.requiredString("echConfigList"),
            echForceQuery = json.requiredString("echForceQuery"),
            mldsa65Verify = json.requiredString("mldsa65Verify"),
            groupName = json.requiredString("groupName"),
            createdAt = json.requiredLong("createdAt"),
            isLocked = json.requiredBoolean("isLocked"),
            vaultExpiresAtMs = json.nullableLong("vaultExpiresAtMs"),
            vaultKeyScheme = json.requiredString("vaultKeyScheme"),
            featureOverrides = json.requiredString("featureOverrides"),
            sourceType = sourceType,
            canonicalFingerprint = json.requiredString("canonicalFingerprint"),
            isEnabled = json.requiredBoolean("isEnabled"),
            isPinned = json.requiredBoolean("isPinned"),
            manualCountryCode = normalizeCountry(json.nullableString("manualCountryCode")),
            disabledAt = json.nullableLong("disabledAt"),
            healthStatus = HealthStatus.UNTESTED.name,
            latencyMs = null,
            lastJitterMs = 0,
            lastTestedAt = null,
            lastSuccessAt = null,
            lastAttemptAt = null,
            consecutiveFailures = 0,
            lastErrorCategory = ErrorCategory.NONE.name,
            lastErrorMessage = null,
            lastNetworkFingerprint = "",
            cooldownUntilMs = 0L,
            countryCode = null,
            countryVerifiedAt = null,
            exitIp = null
        )
        return parsed.copy(canonicalFingerprint = parsed.canonicalFingerprint.ifBlank { ConfigFingerprint.compute(parsed) })
    }

    private fun parseSubscription(json: JSONObject): SubscriptionEntity {
        val url = json.requiredString("url")
        require(url.toHttpUrlOrNull() != null) { "Backup contains an invalid subscription URL" }
        return SubscriptionEntity(
            id = json.requiredString("id").also { require(it.isNotBlank() && it.length <= 256) },
            name = json.requiredString("name"),
            url = url,
            autoUpdateHours = json.requiredInt("autoUpdateHours"),
            createdAt = json.requiredLong("createdAt"),
            profileTitle = json.nullableString("profileTitle"),
            isEnabledForConnection = json.requiredBoolean("isEnabledForConnection")
        )
    }

    private fun parsePreference(json: JSONObject): SubscriptionNodePreference = SubscriptionNodePreference(
        subscriptionId = json.requiredString("subscriptionId").also { require(it.isNotBlank()) },
        canonicalFingerprint = json.requiredString("canonicalFingerprint").also { require(it.isNotBlank()) },
        isEnabled = json.requiredBoolean("isEnabled"),
        isPinned = json.requiredBoolean("isPinned"),
        manualCountryCode = normalizeCountry(json.nullableString("manualCountryCode"))
    )

    private fun parseSettings(json: JSONObject, manualProfileIds: Set<String>): BackupSettings {
        val selectedCountry = normalizeCountry(json.nullableString("selectedCountryCode"))
        val language = json.requiredString("language").takeIf { it == "fa" || it == "en" } ?: "fa"
        val encodedTheme = json.requiredString("theme")
        val theme = ThemeCodec.decode(encodedTheme) ?: ThemeConfig()
        val activeId = json.nullableString("activeProfileId")?.takeIf { it in manualProfileIds }
        return BackupSettings(
            selectedCountryCode = selectedCountry,
            manualConfigsEnabledForConnection = json.requiredBoolean("manualConfigsEnabledForConnection"),
            language = language,
            theme = theme,
            activeProfileId = activeId
        )
    }

    private fun normalizeCountry(value: String?): String? = ServerCountryResolver.normalizeCode(value)

    private fun String?.jsonValue(): Any = this ?: JSONObject.NULL
    private fun Long?.jsonValue(): Any = this ?: JSONObject.NULL

    private fun JSONObject.requiredString(key: String): String = get(key) as? String
        ?: throw IllegalArgumentException("Backup field '$key' must be a string")

    private fun JSONObject.requiredBoolean(key: String): Boolean = get(key) as? Boolean
        ?: throw IllegalArgumentException("Backup field '$key' must be a boolean")

    private fun JSONObject.requiredInt(key: String): Int {
        val value = get(key) as? Number ?: throw IllegalArgumentException("Backup field '$key' must be an integer")
        val long = value.toLong()
        require(long in Int.MIN_VALUE..Int.MAX_VALUE && value.toDouble() == long.toDouble()) {
            "Backup field '$key' must be an integer"
        }
        return long.toInt()
    }

    private fun JSONObject.requiredLong(key: String): Long = (get(key) as? Number)?.toLong()
        ?: throw IllegalArgumentException("Backup field '$key' must be an integer")

    private fun JSONObject.nullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return get(key) as? String ?: throw IllegalArgumentException("Backup field '$key' must be a string or null")
    }

    private fun JSONObject.nullableLong(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        return (get(key) as? Number)?.toLong()
            ?: throw IllegalArgumentException("Backup field '$key' must be an integer or null")
    }

    private fun canonicalJson(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { key ->
            "${JSONObject.quote(key)}:${canonicalJson(value.get(key))}"
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { index ->
            canonicalJson(value.get(index))
        }
        is String -> JSONObject.quote(value)
        is Number, is Boolean -> value.toString()
        else -> JSONObject.quote(value.toString())
    }

    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun decodeHex(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    private fun encodeBase64Url(bytes: ByteArray): String = Base64Compat.encodeToString(
        bytes,
        urlSafe = true,
        noPadding = true
    )

    private fun decodeBase64Url(value: String): ByteArray {
        require(value.isNotEmpty() && value.matches(Regex("[A-Za-z0-9_-]+")) && value.length % 4 != 1) {
            "Malformed base64url value"
        }
        val decoded = Base64Compat.decode(value)
        require(encodeBase64Url(decoded) == value) { "Malformed base64url value" }
        return decoded
    }
}
