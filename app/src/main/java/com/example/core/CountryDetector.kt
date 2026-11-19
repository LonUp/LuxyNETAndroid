package com.example.core

import com.example.data.local.ServerProfileEntity
import com.example.data.model.CountryConfidence
import com.example.data.model.CountrySource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress

/**
 * Layered country detection:
 * - Stage 1: Metadata / remark extraction (unverified -> ESTIMATED / METADATA).
 * - Stage 2: Endpoint GeoIP (IP literal or bounded DNS resolution), skipping CDN/Anycast/multi-hop.
 * - Stage 3: Actual exit verification via proxy outbound (handled by [ExitCountryVerifier]).
 */
class CountryDetector(
    private val geoIpLookup: (InetAddress) -> String? = { null },
    private val dnsResolver: suspend (String) -> List<InetAddress> = { host ->
        withTimeoutOrNull(1_500L) {
            withContext(Dispatchers.IO) {
                runCatching { InetAddress.getAllByName(host).toList() }.getOrDefault(emptyList())
            }
        }.orEmpty()
    }
) {
    data class DetectionResult(
        val countryCode: String?,
        val confidence: CountryConfidence,
        val source: CountrySource
    )

    /**
     * Fast synchronous Stage 1 + Stage 2a (IP-literal GeoIP only, zero network I/O).
     * Preserves existing VERIFIED exit results and manual assignments.
     */
    fun detectOffline(profile: ServerProfileEntity): ServerProfileEntity {
        val existingConfidence = CountryConfidence.parse(profile.countryConfidence)
        if (existingConfidence == CountryConfidence.VERIFIED &&
            ServerCountryResolver.normalizeCode(profile.countryCode) != null
        ) {
            return profile
        }

        // Stage 1: Metadata / remark
        val metadataCode = ServerCountryResolver.resolveCode(profile.name)
        if (metadataCode != null) {
            return profile.copy(
                countryCode = metadataCode,
                countryConfidence = CountryConfidence.ESTIMATED.name,
                countrySource = CountrySource.METADATA.name
            )
        }

        // Stage 2a: IP-literal GeoIP (skip CDN/Anycast/multi-hop)
        if (!CdnAndAnycastDetector.shouldSkipEndpointGeoIp(profile)) {
            val host = ConfigFingerprint.normalizeHost(profile.address)
            if (isNumericIpLiteral(host)) {
                val addr = runCatching { InetAddress.getByName(host) }.getOrNull()
                if (addr != null && !CdnAndAnycastDetector.isCdnOrAnycastIp(addr)) {
                    val geoCode = ServerCountryResolver.normalizeCode(geoIpLookup(addr))
                    if (geoCode != null) {
                        return profile.copy(
                            countryCode = geoCode,
                            countryConfidence = CountryConfidence.ESTIMATED.name,
                            countrySource = CountrySource.GEOIP.name
                        )
                    }
                }
            }
        }

        return if (profile.countryCode != null && existingConfidence == CountryConfidence.ESTIMATED) {
            profile
        } else {
            profile.copy(
                countryCode = null,
                countryConfidence = CountryConfidence.UNKNOWN.name,
                countrySource = CountrySource.NONE.name
            )
        }
    }

    /**
     * Full Stage 1 + Stage 2 (including bounded DNS resolution for domain endpoints).
     * Never overwrites VERIFIED exit results.
     */
    suspend fun detectWithEndpointGeoIp(profile: ServerProfileEntity): ServerProfileEntity {
        val offline = detectOffline(profile)
        if (CountryConfidence.parse(offline.countryConfidence) == CountryConfidence.VERIFIED) {
            return offline
        }
        if (offline.countryCode != null) return offline
        if (CdnAndAnycastDetector.shouldSkipEndpointGeoIp(offline)) return offline

        val host = ConfigFingerprint.normalizeHost(offline.address)
        if (host.isEmpty() || isNumericIpLiteral(host)) return offline

        val resolved = dnsResolver(host)
        if (resolved.isEmpty()) return offline
        if (resolved.any { CdnAndAnycastDetector.isCdnOrAnycastIp(it) }) return offline

        val countries = resolved.mapNotNull { addr ->
            ServerCountryResolver.normalizeCode(geoIpLookup(addr))
        }.distinct()

        // Only assign an estimated country when all resolved non-private IPs agree on one country.
        val singleCountry = countries.singleOrNull() ?: return offline
        return offline.copy(
            countryCode = singleCountry,
            countryConfidence = CountryConfidence.ESTIMATED.name,
            countrySource = CountrySource.GEOIP.name
        )
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
}
