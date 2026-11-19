package com.example.core

import com.example.data.local.ServerProfileEntity
import com.example.data.model.SecurityType
import com.example.data.model.TransportType
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Locale

/**
 * Identifies CDN, Anycast, and multi-hop/relay endpoints where the entry IP's GeoIP
 * cannot be assumed to match the actual VPN exit country.
 */
object CdnAndAnycastDetector {

    private val cdnDomainSuffixes = listOf(
        "workers.dev",
        "pages.dev",
        "trycloudflare.com",
        "cloudflare.com",
        "cloudflare.net",
        "cloudflare-dns.com",
        "cloudflareclient.com",
        "fastly.net",
        "fastlylb.net",
        "akamaiedge.net",
        "akamaihd.net",
        "edgesuite.net",
        "edgekey.net",
        "cloudfront.net",
        "azureedge.net",
        "cdn77.org",
        "bunnycdn.com",
        "b-cdn.net",
        "gcorelabs.com",
        "arvancloud.ir",
        "arvancloud.com",
        "derak.cloud"
    )

    private val relayNameTokens = listOf(
        "relay", "tunnel", "bridge", "cdn", "anycast", "cloudflare", "cf-", "-cf",
        "تانل", "رله", "پل", "کلودفلر"
    )

    private val arrowRegex = Regex("(?:->|=>|→|➔|➡)")

    private data class Cidr4(val network: Int, val mask: Int)
    private data class Cidr6(val hiPrefix: Long, val hiMask: Long)

    private val cdnV4Cidrs: List<Cidr4> = listOf(
        // Cloudflare
        "1.1.1.0/24", "1.0.0.0/24",
        "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22",
        "104.16.0.0/12", "108.162.192.0/18", "131.0.72.0/22",
        "141.101.64.0/18", "162.158.0.0/15", "172.64.0.0/13",
        "173.245.48.0/20", "188.114.96.0/20", "190.93.240.0/20",
        "197.234.240.0/22", "198.41.128.0/17",
        // Fastly
        "151.101.0.0/16", "199.232.0.0/16",
        // Public Anycast DNS / CDN edge
        "8.8.8.0/24", "8.8.4.0/24", "9.9.9.0/24"
    ).map(::parseCidr4)

    private val cdnV6Cidrs: List<Cidr6> = listOf(
        "2606:4700::/32", "2803:f800::/32", "2400:cb00::/32",
        "2405:8100::/32", "2405:b500::/32", "2a06:98c0::/29", "2c0f:f248::/32"
    ).map(::parseCidr6)

    fun isCdnOrAnycastHost(host: String): Boolean {
        val clean = ConfigFingerprint.normalizeHost(host)
        if (clean.isEmpty()) return false
        if (isNumericIpLiteral(clean)) {
            val addr = runCatching { InetAddress.getByName(clean) }.getOrNull() ?: return false
            return isCdnOrAnycastIp(addr)
        }
        return cdnDomainSuffixes.any { suffix ->
            clean == suffix || clean.endsWith(".$suffix")
        }
    }

    fun isCdnOrAnycastIp(addr: InetAddress): Boolean = when (addr) {
        is Inet4Address -> {
            val b = addr.address
            val ip = ((b[0].toInt() and 0xFF) shl 24) or
                ((b[1].toInt() and 0xFF) shl 16) or
                ((b[2].toInt() and 0xFF) shl 8) or
                (b[3].toInt() and 0xFF)
            cdnV4Cidrs.any { (ip and it.mask) == it.network }
        }
        is Inet6Address -> {
            val b = addr.address
            val hi = ((b[0].toLong() and 0xFF) shl 56) or
                ((b[1].toLong() and 0xFF) shl 48) or
                ((b[2].toLong() and 0xFF) shl 40) or
                ((b[3].toLong() and 0xFF) shl 32) or
                ((b[4].toLong() and 0xFF) shl 24) or
                ((b[5].toLong() and 0xFF) shl 16) or
                ((b[6].toLong() and 0xFF) shl 8) or
                (b[7].toLong() and 0xFF)
            cdnV6Cidrs.any { (hi and it.hiMask) == it.hiPrefix }
        }
        else -> false
    }

    /**
     * Returns true when the profile's endpoint GeoIP should NOT be trusted as the exit country
     * (e.g. CDN/Anycast endpoint, CDN-fronted WS/XHTTP/HTTPUpgrade, or multi-hop relay remark).
     */
    fun shouldSkipEndpointGeoIp(profile: ServerProfileEntity): Boolean {
        if (isCdnOrAnycastHost(profile.address)) return true

        val lowerName = profile.name.lowercase(Locale.ROOT)
        if (arrowRegex.containsMatchIn(profile.name)) return true
        if (relayNameTokens.any { lowerName.contains(it) }) return true

        val security = SecurityType.parse(profile.security)
        val transport = TransportType.parse(profile.transport)
        // REALITY uses third-party SNI camouflage (e.g. www.cloudflare.com) while connecting
        // directly to the VPS IP, so SNI alone does not indicate CDN fronting for REALITY.
        if (security != SecurityType.REALITY &&
            transport in setOf(TransportType.WS, TransportType.XHTTP, TransportType.HTTPUPGRADE, TransportType.GRPC)
        ) {
            if (isCdnOrAnycastHost(profile.hostHeader) || isCdnOrAnycastHost(profile.sni)) {
                return true
            }
        }
        return false
    }

    private fun isNumericIpLiteral(host: String): Boolean {
        if (host.all { it in '0'..'9' || it == '.' }) {
            val parts = host.split('.')
            return parts.size == 4 && parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }
        }
        return host.contains(':') && host.all {
            it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.'
        }
    }

    private fun parseCidr4(cidr: String): Cidr4 {
        val (ipStr, pfxStr) = cidr.split('/')
        val b = InetAddress.getByName(ipStr).address
        val ip = ((b[0].toInt() and 0xFF) shl 24) or
            ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or
            (b[3].toInt() and 0xFF)
        val p = pfxStr.toInt()
        val mask = if (p == 0) 0 else (-1 shl (32 - p))
        return Cidr4(ip and mask, mask)
    }

    private fun parseCidr6(cidr: String): Cidr6 {
        val (ipStr, pfxStr) = cidr.split('/')
        val b = InetAddress.getByName(ipStr).address
        val hi = ((b[0].toLong() and 0xFF) shl 56) or
            ((b[1].toLong() and 0xFF) shl 48) or
            ((b[2].toLong() and 0xFF) shl 40) or
            ((b[3].toLong() and 0xFF) shl 32) or
            ((b[4].toLong() and 0xFF) shl 24) or
            ((b[5].toLong() and 0xFF) shl 16) or
            ((b[6].toLong() and 0xFF) shl 8) or
            (b[7].toLong() and 0xFF)
        val p = pfxStr.toInt().coerceIn(0, 64)
        val mask = if (p == 0) 0L else (-1L shl (64 - p))
        return Cidr6(hi and mask, mask)
    }
}
