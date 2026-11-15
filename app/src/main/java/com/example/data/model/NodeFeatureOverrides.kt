package com.example.data.model

import org.json.JSONObject

/** Inherit the global setting, force it on, or force it off for one profile. */
enum class TriState {
    INHERIT,
    ON,
    OFF;

    fun resolve(global: Boolean): Boolean = when (this) {
        INHERIT -> global
        ON -> true
        OFF -> false
    }

    companion object {
        fun parse(raw: String?): TriState =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: INHERIT
    }
}

/** Whether a feature may be turned on for this protocol / transport / security combination. */
enum class FeatureGate {
    /** Standard Xray capability. */
    ALLOWED,
    /** Only when Experimental Mode is on. Standard mode forces it off. */
    EXPERIMENTAL,
    /** Incompatible. Always off, regardless of the global setting. */
    FORBIDDEN
}

data class FeatureMatrix(
    val mux: FeatureGate,
    val muxReason: String,
    val fragment: FeatureGate,
    val fragmentReason: String,
    val realityFragment: FeatureGate,
    val realityFragmentReason: String,
    val ech: FeatureGate,
    val echReason: String,
    val noise: FeatureGate,
    val noiseReason: String,
    val tlsPadding: FeatureGate,
    val tlsPaddingReason: String,
    val tcpFastOpen: FeatureGate,
    val tcpFastOpenReason: String,
    val allowUnencrypted: FeatureGate,
    val mixedCaseSni: FeatureGate,
    val flowAllowed: Boolean,
    val flowReason: String,
    val encryptionOptions: List<String>,
    val encryptionLockedToNone: Boolean
)

/**
 * Per-profile overrides for features that otherwise live only in global settings.
 * Empty strings and [TriState.INHERIT] mean "use the global value".
 * Stored as JSON on the profile so adding a knob does not need another Room column.
 */
data class NodeFeatureOverrides(
    val mux: TriState = TriState.INHERIT,
    val muxConcurrency: Int = 0,
    val fragment: TriState = TriState.INHERIT,
    val fragmentPackets: String = "",
    val fragmentLength: String = "",
    val fragmentInterval: String = "",
    val fragmentMaxSplit: String = "",
    val fragmentHost: String = "",
    val realityFragment: TriState = TriState.INHERIT,
    val ech: TriState = TriState.INHERIT,
    val echForceQuery: String = "",
    val failClosedEch: TriState = TriState.INHERIT,
    val noise: TriState = TriState.INHERIT,
    val noiseType: String = "",
    val noisePacketSize: String = "",
    val noiseDelayMs: String = "",
    val tlsPadding: TriState = TriState.INHERIT,
    val tcpFastOpen: TriState = TriState.INHERIT,
    val allowUnencryptedVless: TriState = TriState.INHERIT,
    val mixedCaseSni: TriState = TriState.INHERIT,
    val packetEncoding: String = "",
    val spiderX: String = ""
) {
    fun isEmpty(): Boolean =
        mux == TriState.INHERIT && muxConcurrency == 0 &&
            fragment == TriState.INHERIT && fragmentPackets.isBlank() && fragmentLength.isBlank() &&
            fragmentInterval.isBlank() && fragmentMaxSplit.isBlank() && fragmentHost.isBlank() &&
            realityFragment == TriState.INHERIT &&
            ech == TriState.INHERIT && echForceQuery.isBlank() && failClosedEch == TriState.INHERIT &&
            noise == TriState.INHERIT && noiseType.isBlank() && noisePacketSize.isBlank() && noiseDelayMs.isBlank() &&
            tlsPadding == TriState.INHERIT && tcpFastOpen == TriState.INHERIT &&
            allowUnencryptedVless == TriState.INHERIT && mixedCaseSni == TriState.INHERIT &&
            packetEncoding.isBlank() && spiderX.isBlank()

    fun encode(): String {
        if (isEmpty()) return ""
        return JSONObject().apply {
            put("mux", mux.name)
            if (muxConcurrency > 0) put("muxConcurrency", muxConcurrency)
            put("fragment", fragment.name)
            putIfSet("fragmentPackets", fragmentPackets)
            putIfSet("fragmentLength", fragmentLength)
            putIfSet("fragmentInterval", fragmentInterval)
            putIfSet("fragmentMaxSplit", fragmentMaxSplit)
            putIfSet("fragmentHost", fragmentHost)
            put("realityFragment", realityFragment.name)
            put("ech", ech.name)
            putIfSet("echForceQuery", echForceQuery)
            put("failClosedEch", failClosedEch.name)
            put("noise", noise.name)
            putIfSet("noiseType", noiseType)
            putIfSet("noisePacketSize", noisePacketSize)
            putIfSet("noiseDelayMs", noiseDelayMs)
            put("tlsPadding", tlsPadding.name)
            put("tcpFastOpen", tcpFastOpen.name)
            put("allowUnencryptedVless", allowUnencryptedVless.name)
            put("mixedCaseSni", mixedCaseSni.name)
            putIfSet("packetEncoding", packetEncoding)
            putIfSet("spiderX", spiderX)
        }.toString()
    }

    private fun JSONObject.putIfSet(key: String, value: String) {
        if (value.isNotBlank()) put(key, value)
    }

    companion object {
        fun decode(raw: String?): NodeFeatureOverrides {
            if (raw.isNullOrBlank()) return NodeFeatureOverrides()
            return try {
                val json = JSONObject(raw)
                NodeFeatureOverrides(
                    mux = TriState.parse(json.optString("mux")),
                    muxConcurrency = json.optInt("muxConcurrency", 0),
                    fragment = TriState.parse(json.optString("fragment")),
                    fragmentPackets = json.optString("fragmentPackets"),
                    fragmentLength = json.optString("fragmentLength"),
                    fragmentInterval = json.optString("fragmentInterval"),
                    fragmentMaxSplit = json.optString("fragmentMaxSplit"),
                    fragmentHost = json.optString("fragmentHost"),
                    realityFragment = TriState.parse(json.optString("realityFragment")),
                    ech = TriState.parse(json.optString("ech")),
                    echForceQuery = json.optString("echForceQuery"),
                    failClosedEch = TriState.parse(json.optString("failClosedEch")),
                    noise = TriState.parse(json.optString("noise")),
                    noiseType = json.optString("noiseType"),
                    noisePacketSize = json.optString("noisePacketSize"),
                    noiseDelayMs = json.optString("noiseDelayMs"),
                    tlsPadding = TriState.parse(json.optString("tlsPadding")),
                    tcpFastOpen = TriState.parse(json.optString("tcpFastOpen")),
                    allowUnencryptedVless = TriState.parse(json.optString("allowUnencryptedVless")),
                    mixedCaseSni = TriState.parse(json.optString("mixedCaseSni")),
                    packetEncoding = json.optString("packetEncoding"),
                    spiderX = json.optString("spiderX")
                )
            } catch (_: Exception) {
                NodeFeatureOverrides()
            }
        }
    }
}
