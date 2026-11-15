package com.example.data.model

import com.example.data.local.ServerProfileEntity

data class CountryPolicyEntry(
    val code: String,
    val action: RuleAction
)

data class GeoDatabaseStatus(
    val geoipVersion: String,
    val geositeVersion: String,
    val lastCheckedAt: Long?,
    val builtInRuleCount: Int,
    val source: String
)

/** Which phases a run executes. Individual phases are what the UI exposes as Ping/Download/Upload. */
enum class SpeedTestAction(val label: String) {
    FULL("All"),
    PING("Ping"),
    DOWNLOAD("Download"),
    UPLOAD("Upload")
}

const val SPEED_TEST_SINGLE_THREAD = 1
const val SPEED_TEST_MULTI_THREAD = 4

data class SpeedTestProgress(
    val mode: SpeedTestMode,
    val action: SpeedTestAction = SpeedTestAction.FULL,
    val threads: Int = SPEED_TEST_SINGLE_THREAD,
    val stage: SpeedTestStage,
    val progressFraction: Float,
    val currentMbps: Double,
    val pingMs: Double?,
    val jitterMs: Double?,
    val downloadMbps: Double?,
    val uploadMbps: Double?,
    val exitLabel: String,
    val statusNote: String? = null,
    val warnings: List<String> = emptyList()
)

data class SpeedTestResult(
    val mode: SpeedTestMode,
    val action: SpeedTestAction = SpeedTestAction.FULL,
    val threads: Int = SPEED_TEST_SINGLE_THREAD,
    val pingMs: Double,
    val jitterMs: Double,
    val downloadMbps: Double,
    val uploadMbps: Double,
    val exitLabel: String,
    val timestamp: Long,
    val probeCount: Int,
    val isHandshakeOnly: Boolean = false,
    val warnings: List<String> = emptyList()
)

data class TunnelTelemetry(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val activeNode: ServerProfileEntity? = null,
    val connectedSinceMs: Long = 0L,
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L,
    val rxBytesPerSec: Long = 0L,
    val txBytesPerSec: Long = 0L,
    val activeLatencyMs: Int = -1,
    val networkTransport: String = "Wi-Fi",
    val isFailoverInProgress: Boolean = false,
    val lastError: String? = null,
    val activeAdaptiveStage: Int = 0,
    val adaptiveStageLabel: String = "Stage 0 • Baseline",
    val networkFingerprint: String = "wifi:default",
    val isAdaptingRoute: Boolean = false
)

data class StabilitySettings(
    val activeProfileId: String? = null,
    val theme: ThemeConfig = ThemeConfig(),
    val language: String = "fa",
    val routingMode: RoutingMode = RoutingMode.RULE_BASED,
    val autoConnectOnLaunch: Boolean = false,
    val autoReconnect: Boolean = true,
    val smartFailover: Boolean = true,
    val probeIntervalSec: Int = 15,
    val muxEnabled: Boolean = true,
    val muxConcurrency: Int = 8,
    val tlsFragmentEnabled: Boolean = false,
    val tcpFastOpen: Boolean = false,
    val primaryDns: String = "1.1.1.1",
    val fallbackDns: String = "9.9.9.9",
    val dohEnabled: Boolean = true,
    val mtu: Int = 1400,
    val antiCensorshipPreset: AntiCensorshipPreset = AntiCensorshipPreset.BALANCED_STEALTH,
    val fragmentPackets: String = "tlshello",
    val fragmentLength: String = "10-20",
    val fragmentInterval: String = "10-20",
    val fragmentHost: String = "",
    val fragmentMaxSplit: String = "32-64",
    val noiseEnabled: Boolean = false,
    val noiseType: String = "rand",
    val noisePacketSize: String = "10-20",
    val noiseDelayMs: String = "10-20",
    val noiseApplyTo: String = "ip",
    val utlsFingerprint: String = "randomized",
    val fakeDnsEnabled: Boolean = true,
    val sniffingEnabled: Boolean = true,
    val blockQuicUdp: Boolean = true,
    val xhttpMode: String = "auto",
    val xhttpPaddingBytes: String = "100-1000",
    val dohServerUrl: String = "https://cloudflare-dns.com/dns-query",
    val domainStrategy: String = "IPIfNonMatch",
    val cipherSuites: String = "",
    val finalMaskType: String = "none",
    val echEnabled: Boolean = false,
    val echConfigList: String = "",
    val echForceQuery: String = "half",
    val happyEyeballsEnabled: Boolean = true,
    val happyEyeballsTryDelayMs: Int = 250,
    val allowUnencryptedVless: Boolean = false,
    val subscriptionTopNLimit: Int = 50,
    val warpEndpoint: String = "162.159.192.1:2408",
    val warpPrivateKey: String = "",
    val warpPublicKey: String = "",
    val warpReserved: String = "0,0,0",
    val warpLocalAddress: String = "172.16.0.2/32",
    val awgJc: Int = 4,
    val awgJmin: Int = 40,
    val awgJmax: Int = 70,
    val awgS1: Int = 0,
    val awgS2: Int = 0,
    val awgH1: Int = 1,
    val awgH2: Int = 2,
    val awgH3: Int = 3,
    val awgH4: Int = 4,
    val warpNoisePreset: String = "quic",
    val chainingMode: ChainingMode = ChainingMode.NONE,
    val relayNodeId: String? = null,
    val mixedCaseSni: Boolean = false,
    val tlsPaddingEnabled: Boolean = false,
    val realityFragmentEnabled: Boolean = false,
    val finalMaskFamily: String = "tcp",
    val tcpHandshakeTimeoutMs: Int = 4000,
    val tlsHandshakeTimeoutMs: Int = 4000,
    val dnsPoolStrategy: DnsPoolStrategy = DnsPoolStrategy.PARALLEL_FASTEST,
    val dnsIntegrityFilterEnabled: Boolean = true,
    val dnsDotServer: String = "1.1.1.1:853",
    val dnsTcpEnabled: Boolean = true,
    val dnsDotEnabled: Boolean = true,
    val dnsUdpEnabled: Boolean = true,
    val appSplitTunnelMode: AppSplitTunnelMode = AppSplitTunnelMode.DISABLED,
    val splitTunnelPackages: String = "",
    val strictUidIsolationEnabled: Boolean = true,
    val flagSecureEnabled: Boolean = false,
    val excludeFromRecents: Boolean = false,
    val appLockEnabled: Boolean = false,
    val subscriptionUserAgent: String = "LuxyVPN/3.0",
    val adaptiveFallbackEnabled: Boolean = true,
    val failClosedEch: Boolean = false,
    val stallThresholdSec: Int = 8,
    val domesticBypassEnabled: Boolean = false,
    val domesticCountryCode: String = "IR",
    val blockAdsEnabled: Boolean = false,
    val blockPornEnabled: Boolean = false,
    val socialPolicies: Map<SocialServiceId, RuleAction> = mapOf(
        SocialServiceId.TELEGRAM to RuleAction.PROXY,
        SocialServiceId.YOUTUBE to RuleAction.PROXY,
        SocialServiceId.INSTAGRAM to RuleAction.PROXY,
        SocialServiceId.WHATSAPP to RuleAction.PROXY,
        SocialServiceId.TWITTER to RuleAction.PROXY
    ),
    val countryPolicies: List<CountryPolicyEntry> = emptyList(),
    val geoDbVersion: String = "2026.11-built-in",
    val geoDbLastUpdatedAt: Long? = null,
    val experimentalModeEnabled: Boolean = false
) {
    /**
     * Non-destructive baseline projection: when [experimentalModeEnabled] is false,
     * returns a clean standard Xray configuration with all experimental framing,
     * padding, ECH fail-closed, chaining, AWG shaping, and adaptive escalation disabled
     * without mutating any persisted user sub-settings.
     */
    fun asEffectiveBaseline(): StabilitySettings {
        if (experimentalModeEnabled) return this
        return copy(
            antiCensorshipPreset = AntiCensorshipPreset.DIRECT_LOW_LATENCY,
            tlsFragmentEnabled = false,
            realityFragmentEnabled = false,
            fragmentHost = "",
            fragmentMaxSplit = "",
            noiseEnabled = false,
            mixedCaseSni = false,
            tlsPaddingEnabled = false,
            utlsFingerprint = "chrome",
            cipherSuites = "",
            finalMaskType = "none",
            echEnabled = false,
            echConfigList = "",
            echForceQuery = "none",
            failClosedEch = false,
            allowUnencryptedVless = false,
            chainingMode = ChainingMode.NONE,
            relayNodeId = null,
            awgJc = 0,
            awgJmin = 0,
            awgJmax = 0,
            awgS1 = 0,
            awgS2 = 0,
            awgH1 = 1,
            awgH2 = 2,
            awgH3 = 3,
            awgH4 = 4,
            warpNoisePreset = "off",
            happyEyeballsEnabled = false,
            tcpHandshakeTimeoutMs = 4000,
            tlsHandshakeTimeoutMs = 4000,
            dnsPoolStrategy = DnsPoolStrategy.SEQUENTIAL_FALLBACK,
            dnsIntegrityFilterEnabled = false,
            adaptiveFallbackEnabled = false
        )
    }
}
