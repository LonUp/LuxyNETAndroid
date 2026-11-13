package com.example.vpn

import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import android.system.OsConstants
import com.example.data.local.RoutingRuleEntity
import com.example.data.model.AppSplitTunnelMode
import com.example.data.model.RoutingMode
import com.example.data.model.RuleAction
import com.example.data.model.RuleMatchType
import java.net.InetSocketAddress

data class OriginalDestinationFlow(
    val originalDomain: String?,
    val originalIp: String,
    val resolvedOrFakeIp: String = originalIp,
    val port: Int,
    val protocol: Int = OsConstants.IPPROTO_TCP,
    val sourceIp: String = "10.88.0.2",
    val sourcePort: Int = 0
)

data class PreFakeDnsDecision(
    val action: RuleAction,
    val matchedRuleName: String?,
    val evaluatedOriginalDestination: Boolean,
    val hadFakeDnsRewrite: Boolean
)

enum class UidFlowDecision {
    ALLOW,
    BYPASS_SELF,
    DENY_UNRESOLVED_UID,
    DENY_EXCLUDED_UID
}

data class ParsedIpv4Flow(
    val protocol: Int,
    val sourceIp: String,
    val sourcePort: Int,
    val destIp: String,
    val destPort: Int
)

fun interface ConnectionOwnerResolver {
    fun getConnectionOwnerUid(
        protocol: Int,
        localAddress: InetSocketAddress,
        remoteAddress: InetSocketAddress
    ): Int
}

class AndroidConnectionOwnerResolver(
    private val connectivityManager: ConnectivityManager
) : ConnectionOwnerResolver {
    override fun getConnectionOwnerUid(
        protocol: Int,
        localAddress: InetSocketAddress,
        remoteAddress: InetSocketAddress
    ): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return Process.INVALID_UID
        }
        return try {
            connectivityManager.getConnectionOwnerUid(protocol, localAddress, remoteAddress)
        } catch (_: Exception) {
            Process.INVALID_UID
        }
    }
}

/**
 * Split-Tunnel Hardening & Android 10+ Socket UID Isolation engine:
 * 1. Evaluates routing & bypass rules against the original destination domain/IP
 *    BEFORE FakeDNS virtual IP rewriting occurs.
 * 2. Resolves socket owner UID via ConnectivityManager.getConnectionOwnerUid (API 29+)
 *    and enforces fail-closed UID allowlist/blocklist policies before handshake completion.
 */
object SplitTunnelEngine {

    private const val FAKEDNS_PREFIX_FIRST = 198
    private const val FAKEDNS_PREFIX_SECOND_MIN = 18
    private const val FAKEDNS_PREFIX_SECOND_MAX = 19

    fun isFakeDnsIp(ip: String): Boolean {
        val parts = ip.trim().split(".")
        if (parts.size != 4) return false
        val o0 = parts[0].toIntOrNull() ?: return false
        val o1 = parts[1].toIntOrNull() ?: return false
        return o0 == FAKEDNS_PREFIX_FIRST && o1 in FAKEDNS_PREFIX_SECOND_MIN..FAKEDNS_PREFIX_SECOND_MAX
    }

    /**
     * Evaluates routing rules strictly against flow.originalDomain & flow.originalIp BEFORE
     * FakeDNS rewrites flow.resolvedOrFakeIp into 198.18.0.0/15.
     */
    fun evaluatePreFakeDnsRouting(
        flow: OriginalDestinationFlow,
        routingMode: RoutingMode,
        rules: List<RoutingRuleEntity>,
        blockQuicUdp: Boolean
    ): PreFakeDnsDecision {
        val hadFakeRewrite = isFakeDnsIp(flow.resolvedOrFakeIp) && flow.resolvedOrFakeIp != flow.originalIp

        if (blockQuicUdp && flow.protocol == OsConstants.IPPROTO_UDP && flow.port == 443) {
            return PreFakeDnsDecision(
                action = RuleAction.BLOCK,
                matchedRuleName = "QUIC-Guard (UDP/443)",
                evaluatedOriginalDestination = true,
                hadFakeDnsRewrite = hadFakeRewrite
            )
        }

        if (routingMode == RoutingMode.GLOBAL) {
            return PreFakeDnsDecision(
                action = RuleAction.PROXY,
                matchedRuleName = "Global Tunnel",
                evaluatedOriginalDestination = true,
                hadFakeDnsRewrite = hadFakeRewrite
            )
        }

        val originalDomain = flow.originalDomain?.trim()?.lowercase()?.trimEnd('.').orEmpty()
        val originalIp = flow.originalIp.trim()

        if (routingMode == RoutingMode.BYPASS_LAN && isPrivateOrLanIp(originalIp)) {
            return PreFakeDnsDecision(
                action = RuleAction.DIRECT,
                matchedRuleName = "Bypass LAN",
                evaluatedOriginalDestination = true,
                hadFakeDnsRewrite = hadFakeRewrite
            )
        }

        for (rule in rules) {
            if (!rule.isEnabled) continue
            val pattern = rule.pattern.trim().lowercase()
            val matched = when (rule.matchType) {
                RuleMatchType.DOMAIN_SUFFIX.name -> {
                    val clean = pattern.removePrefix(".")
                    originalDomain.isNotEmpty() && (originalDomain == clean || originalDomain.endsWith(".$clean"))
                }
                RuleMatchType.DOMAIN_KEYWORD.name -> {
                    originalDomain.isNotEmpty() && originalDomain.contains(pattern)
                }
                RuleMatchType.GEOSITE.name -> {
                    val clean = pattern.removePrefix("geosite:")
                    originalDomain.isNotEmpty() && (
                        (clean == "cn" && originalDomain.endsWith(".cn")) ||
                            (clean == "ir" && originalDomain.endsWith(".ir")) ||
                            originalDomain.contains(clean)
                        )
                }
                RuleMatchType.IP_CIDR.name -> {
                    originalIp.isNotEmpty() && ipMatchesCidr(originalIp, rule.pattern.trim())
                }
                RuleMatchType.GEOIP.name -> {
                    val clean = pattern.removePrefix("geoip:")
                    clean == "private" && isPrivateOrLanIp(originalIp)
                }
                else -> false
            }

            if (matched) {
                val action = when (rule.action) {
                    RuleAction.DIRECT.name -> RuleAction.DIRECT
                    RuleAction.BLOCK.name -> RuleAction.BLOCK
                    else -> RuleAction.PROXY
                }
                return PreFakeDnsDecision(
                    action = action,
                    matchedRuleName = rule.name,
                    evaluatedOriginalDestination = true,
                    hadFakeDnsRewrite = hadFakeRewrite
                )
            }
        }

        return PreFakeDnsDecision(
            action = RuleAction.PROXY,
            matchedRuleName = null,
            evaluatedOriginalDestination = true,
            hadFakeDnsRewrite = hadFakeRewrite
        )
    }

    fun resolveFlowOwnerUid(
        flow: OriginalDestinationFlow,
        resolver: ConnectionOwnerResolver
    ): Int {
        if (flow.sourcePort !in 1..65535 || flow.port !in 1..65535) return Process.INVALID_UID
        return try {
            resolver.getConnectionOwnerUid(
                flow.protocol,
                InetSocketAddress(flow.sourceIp, flow.sourcePort),
                InetSocketAddress(flow.resolvedOrFakeIp.ifBlank { flow.originalIp }, flow.port)
            )
        } catch (_: Exception) {
            Process.INVALID_UID
        }
    }

    /**
     * Enforces per-app UID split-tunnel isolation.
     * Unresolved UIDs (< 0 / INVALID_UID) are rejected fail-closed when strictUidIsolation is enabled
     * so excluded or unattributed processes cannot leak or probe before handshake completion.
     */
    fun evaluateUidFlowPolicy(
        uid: Int,
        selfUid: Int,
        mode: AppSplitTunnelMode,
        allowedUids: Set<Int>,
        blockedUids: Set<Int>,
        strictUidIsolation: Boolean
    ): UidFlowDecision {
        if (uid == selfUid && selfUid > 0) {
            return UidFlowDecision.BYPASS_SELF
        }
        if (uid < 0 || uid == Process.INVALID_UID) {
            return if (strictUidIsolation) UidFlowDecision.DENY_UNRESOLVED_UID else UidFlowDecision.ALLOW
        }
        return when (mode) {
            AppSplitTunnelMode.DISABLED -> {
                if (uid in blockedUids) UidFlowDecision.DENY_EXCLUDED_UID else UidFlowDecision.ALLOW
            }
            AppSplitTunnelMode.ALLOWLIST -> {
                if (uid in allowedUids) UidFlowDecision.ALLOW else UidFlowDecision.DENY_EXCLUDED_UID
            }
            AppSplitTunnelMode.BLOCKLIST -> {
                if (uid in blockedUids) UidFlowDecision.DENY_EXCLUDED_UID else UidFlowDecision.ALLOW
            }
        }
    }

    fun parsePackageList(packagesCsv: String): List<String> =
        packagesCsv.split(",", "\n", ";", " ")
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.contains(".") }
            .distinct()

    fun parseIpv4FlowHeader(packet: ByteArray, length: Int): ParsedIpv4Flow? {
        if (length < 24 || length > packet.size) return null
        val version = (packet[0].toInt() ushr 4) and 0x0F
        if (version != 4) return null
        val ihlBytes = (packet[0].toInt() and 0x0F) * 4
        if (ihlBytes < 20 || length < ihlBytes + 4) return null
        val proto = packet[9].toInt() and 0xFF
        if (proto != OsConstants.IPPROTO_TCP && proto != OsConstants.IPPROTO_UDP) return null

        val srcIp = "${packet[12].toInt() and 0xFF}.${packet[13].toInt() and 0xFF}." +
            "${packet[14].toInt() and 0xFF}.${packet[15].toInt() and 0xFF}"
        val dstIp = "${packet[16].toInt() and 0xFF}.${packet[17].toInt() and 0xFF}." +
            "${packet[18].toInt() and 0xFF}.${packet[19].toInt() and 0xFF}"
        val srcPort = ((packet[ihlBytes].toInt() and 0xFF) shl 8) or (packet[ihlBytes + 1].toInt() and 0xFF)
        val dstPort = ((packet[ihlBytes + 2].toInt() and 0xFF) shl 8) or (packet[ihlBytes + 3].toInt() and 0xFF)

        return ParsedIpv4Flow(
            protocol = proto,
            sourceIp = srcIp,
            sourcePort = srcPort,
            destIp = dstIp,
            destPort = dstPort
        )
    }

    fun isPrivateOrLanIp(ip: String): Boolean {
        val octets = ip.trim().split(".")
        if (octets.size != 4) return false
        val o0 = octets[0].toIntOrNull() ?: return false
        val o1 = octets[1].toIntOrNull() ?: return false
        return when {
            o0 == 10 -> true
            o0 == 127 -> true
            o0 == 169 && o1 == 254 -> true
            o0 == 172 && o1 in 16..31 -> true
            o0 == 192 && o1 == 168 -> true
            else -> false
        }
    }

    fun ipMatchesCidr(ip: String, cidr: String): Boolean {
        val parts = cidr.split("/")
        val baseInt = ipv4ToInt(parts[0]) ?: return false
        val ipInt = ipv4ToInt(ip) ?: return false
        val prefix = if (parts.size == 2) parts[1].toIntOrNull() ?: return false else 32
        if (prefix !in 0..32) return false
        val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
        return (ipInt and mask) == (baseInt and mask)
    }

    private fun ipv4ToInt(ip: String): Int? {
        val parts = ip.trim().split(".")
        if (parts.size != 4) return null
        val octets = parts.map { it.toIntOrNull() ?: return null }
        if (octets.any { it !in 0..255 }) return null
        return (octets[0] shl 24) or (octets[1] shl 16) or (octets[2] shl 8) or octets[3]
    }
}
