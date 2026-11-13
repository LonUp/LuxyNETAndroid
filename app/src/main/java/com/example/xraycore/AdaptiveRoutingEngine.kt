package com.example.xraycore

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.os.Build
import android.telephony.TelephonyManager
import com.example.core.EncryptedConfigVault
import com.example.data.local.NetworkStrategyDao
import com.example.data.local.NetworkStrategyEntity
import com.example.data.local.ServerProfileEntity
import com.example.data.model.AntiCensorshipPreset
import com.example.data.model.ChainingMode
import com.example.data.model.DnsPoolStrategy
import com.example.data.model.ProxyProtocol
import com.example.data.model.StabilitySettings
import java.security.MessageDigest

data class NetworkDescriptor(
    val transportType: String,
    val wifiSsid: String = "",
    val wifiBssid: String = "",
    val cellularOperator: String = "",
    val radioType: String = "",
    val interfaceName: String = "",
    val dnsHint: String = ""
)

data class AdaptiveStageSpec(
    val stage: Int,
    val title: String,
    val summary: String,
    val presetName: String
)

/**
 * Tracks live uplink vs downlink byte progress to detect DPI post-handshake stalls
 * (uplink packets departing while downlink remains zero across the threshold window).
 */
class LiveStallTracker(
    private val stallThresholdMs: Long
) {
    private var uplinkWithoutDownlinkSinceMs: Long? = null

    fun reset() {
        uplinkWithoutDownlinkSinceMs = null
    }

    fun sample(deltaTxBytes: Long, deltaRxBytes: Long, nowMs: Long): Boolean {
        if (deltaRxBytes > 0L) {
            uplinkWithoutDownlinkSinceMs = null
            return false
        }
        if (deltaTxBytes > 0L && deltaRxBytes == 0L) {
            val started = uplinkWithoutDownlinkSinceMs
            if (started == null) {
                uplinkWithoutDownlinkSinceMs = nowMs
                return false
            }
            if (nowMs - started >= stallThresholdMs.coerceAtLeast(1000L)) {
                uplinkWithoutDownlinkSinceMs = nowMs
                return true
            }
        }
        return false
    }
}

/**
 * Per-Network Adaptive Routing & 5-Stage Resilience Fallback Engine:
 * - Privacy-preserving SHA-256 network fingerprinting (Wi-Fi / Cellular / Ethernet)
 * - Automated 5-stage fallback ladder for hot-reloading XrayConfigCompiler without tearing down TUN
 * - Strictly honors Phase 1 isLocked vault redaction rules when persisting strategy metadata
 */
object AdaptiveRoutingEngine {

    val STAGES: List<AdaptiveStageSpec> = listOf(
        AdaptiveStageSpec(
            stage = 0,
            title = "Stage 0 • Baseline",
            summary = "Direct / Standard TLS or REALITY low-latency profile",
            presetName = AntiCensorshipPreset.DIRECT_LOW_LATENCY.name
        ),
        AdaptiveStageSpec(
            stage = 1,
            title = "Stage 1 • Fragment + FinalMask",
            summary = "TLS ClientHello Fragmentation + FinalMask + Mixed-Case SNI",
            presetName = AntiCensorshipPreset.MAX_STEALTH.name
        ),
        AdaptiveStageSpec(
            stage = 2,
            title = "Stage 2 • CDN + Fail-Closed ECH",
            summary = "CDN Edge / XHTTP Padding + Strict Fail-Closed ECH",
            presetName = AntiCensorshipPreset.CDN_FRONTING.name
        ),
        AdaptiveStageSpec(
            stage = 3,
            title = "Stage 3 • WARP / AWG / MASQUE Hop",
            summary = "Multi-hop WARP/AmneziaWG/MASQUE datagram transport wrap",
            presetName = AntiCensorshipPreset.STRICT_DPI_BYPASS.name
        ),
        AdaptiveStageSpec(
            stage = 4,
            title = "Stage 4 • DNS Transport Fallback",
            summary = "DNSTT / MasterDNS tunnel or Parallel DoH + Fragment emergency fallback",
            presetName = AntiCensorshipPreset.STRICT_DPI_BYPASS.name
        )
    )

    fun stageSpec(stage: Int): AdaptiveStageSpec =
        STAGES[stage.coerceIn(0, STAGES.lastIndex)]

    fun shortFingerprint(fullSha256: String): String =
        if (fullSha256.isBlank()) "UNKNOWN" else fullSha256.take(8).uppercase()

    fun computeFingerprint(descriptor: NetworkDescriptor): String {
        val canonical = when (descriptor.transportType.lowercase()) {
            "wi-fi", "wifi" -> {
                val ssid = descriptor.wifiSsid.trim().lowercase().removeSurrounding("\"")
                val bssid = descriptor.wifiBssid.trim().lowercase()
                val fallback = "${descriptor.interfaceName}|${descriptor.dnsHint}".lowercase()
                "wifi|${ssid.ifEmpty { "anon" }}|${bssid.ifEmpty { fallback }}"
            }
            "cellular", "mobile" -> {
                val op = descriptor.cellularOperator.trim().lowercase()
                val radio = descriptor.radioType.trim().lowercase()
                val fallback = "${descriptor.interfaceName}|${descriptor.dnsHint}".lowercase()
                "cellular|${op.ifEmpty { "mccmnc" }}|${radio.ifEmpty { fallback }}"
            }
            else -> {
                "other|${descriptor.transportType.lowercase()}|${descriptor.interfaceName.lowercase()}|${descriptor.dnsHint.lowercase()}"
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    @Suppress("DEPRECATION")
    fun resolveNetworkDescriptor(
        context: Context,
        connectivityManager: ConnectivityManager,
        network: Network? = connectivityManager.activeNetwork
    ): NetworkDescriptor {
        val caps = network?.let { connectivityManager.getNetworkCapabilities(it) }
        val linkProps = network?.let { connectivityManager.getLinkProperties(it) }
        val iface = linkProps?.interfaceName.orEmpty()
        val dnsHint = linkProps?.dnsServers?.joinToString(",") { it.hostAddress.orEmpty() }.orEmpty()

        val transport = when {
            caps == null -> "Offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Network"
        }

        var ssid = ""
        var bssid = ""
        if (transport == "Wi-Fi" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val wifiInfo = caps?.transportInfo as? WifiInfo
            if (wifiInfo != null) {
                ssid = wifiInfo.ssid?.takeIf { it != "<unknown ssid>" }.orEmpty()
                bssid = wifiInfo.bssid?.takeIf { it != "02:00:00:00:00:00" }.orEmpty()
            }
        }

        var operator = ""
        var radio = ""
        if (transport == "Cellular") {
            try {
                val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                operator = tm?.networkOperator.orEmpty()
                radio = tm?.dataNetworkType?.toString().orEmpty()
            } catch (_: SecurityException) {
            } catch (_: Exception) {
            }
        }

        return NetworkDescriptor(
            transportType = transport,
            wifiSsid = ssid,
            wifiBssid = bssid,
            cellularOperator = operator,
            radioType = radio,
            interfaceName = iface,
            dnsHint = dnsHint
        )
    }

    fun applyStageToSettings(
        base: StabilitySettings,
        stage: Int,
        hasWarpConfigured: Boolean = base.warpPublicKey.isNotBlank() || base.warpEndpoint.isNotBlank()
    ): StabilitySettings {
        val clamped = stage.coerceIn(0, 4)
        return when (clamped) {
            0 -> base.copy(
                tlsFragmentEnabled = false,
                noiseEnabled = false,
                mixedCaseSni = false,
                finalMaskType = "none",
                failClosedEch = base.failClosedEch
            )
            1 -> base.copy(
                tlsFragmentEnabled = true,
                fragmentPackets = "1-3",
                fragmentLength = "5-12",
                fragmentInterval = "8-18",
                fragmentMaxSplit = "64-128",
                finalMaskType = if (base.finalMaskType == "none") "header-custom" else base.finalMaskType,
                mixedCaseSni = true,
                utlsFingerprint = if (base.utlsFingerprint == "chrome") "randomized" else base.utlsFingerprint,
                antiCensorshipPreset = AntiCensorshipPreset.MAX_STEALTH
            )
            2 -> base.copy(
                tlsFragmentEnabled = true,
                fragmentPackets = "tlshello",
                fragmentLength = "10-20",
                echEnabled = true,
                echForceQuery = "full",
                failClosedEch = true,
                xhttpMode = "packet-up",
                xhttpPaddingBytes = "200-1200",
                dohEnabled = true,
                antiCensorshipPreset = AntiCensorshipPreset.CDN_FRONTING
            )
            3 -> base.copy(
                tlsFragmentEnabled = true,
                chainingMode = if (hasWarpConfigured && base.chainingMode == ChainingMode.NONE) {
                    ChainingMode.WARP_TO_PROXY
                } else {
                    base.chainingMode
                },
                awgJc = base.awgJc.coerceAtLeast(6),
                warpNoisePreset = if (base.warpNoisePreset == "off") "quic" else base.warpNoisePreset,
                finalMaskType = if (base.finalMaskType == "none") "header-wireguard" else base.finalMaskType,
                antiCensorshipPreset = AntiCensorshipPreset.STRICT_DPI_BYPASS
            )
            else -> base.copy(
                tlsFragmentEnabled = true,
                noiseEnabled = true,
                noiseType = "rand",
                dohEnabled = true,
                dnsPoolStrategy = DnsPoolStrategy.PARALLEL_FASTEST,
                dnsIntegrityFilterEnabled = true,
                blockQuicUdp = true,
                antiCensorshipPreset = AntiCensorshipPreset.STRICT_DPI_BYPASS
            )
        }
    }

    fun selectFallbackDnsTunnelNode(nodes: List<ServerProfileEntity>): ServerProfileEntity? =
        nodes.firstOrNull {
            val p = ProxyProtocol.fromString(it.protocol)
            p == ProxyProtocol.DNSTT || p == ProxyProtocol.MASTERDNS
        }

    fun redactEndpointForStrategy(profile: ServerProfileEntity?): String {
        if (profile == null) return ""
        return if (profile.isLocked) EncryptedConfigVault.REDACTED_VALUE
        else "${profile.address}:${profile.port}"
    }

    suspend fun recordStageOutcome(
        dao: NetworkStrategyDao,
        fingerprint: String,
        transportType: String,
        stage: Int,
        success: Boolean,
        activeNode: ServerProfileEntity?,
        nowMs: Long = System.currentTimeMillis()
    ): NetworkStrategyEntity {
        val existing = dao.getByFingerprint(fingerprint)
        val clampedStage = stage.coerceIn(0, 4)
        val spec = stageSpec(clampedStage)
        val safeEndpoint = redactEndpointForStrategy(activeNode)
        val updated = if (existing == null) {
            NetworkStrategyEntity(
                networkFingerprint = fingerprint,
                transportType = transportType,
                preferredEscalationLevel = clampedStage,
                preferredPreset = spec.presetName,
                lastCleanEndpoint = safeEndpoint,
                lastSuccessAt = if (success) nowMs else 0L,
                successCount = if (success) 1 else 0,
                stallCount = if (success) 0 else 1
            )
        } else {
            existing.copy(
                transportType = transportType,
                preferredEscalationLevel = if (success) clampedStage else existing.preferredEscalationLevel,
                preferredPreset = if (success) spec.presetName else existing.preferredPreset,
                lastCleanEndpoint = if (success && safeEndpoint.isNotBlank()) safeEndpoint else existing.lastCleanEndpoint,
                lastSuccessAt = if (success) nowMs else existing.lastSuccessAt,
                successCount = if (success) existing.successCount + 1 else existing.successCount,
                stallCount = if (success) existing.stallCount else existing.stallCount + 1
            )
        }
        dao.upsert(updated)
        return updated
    }
}
