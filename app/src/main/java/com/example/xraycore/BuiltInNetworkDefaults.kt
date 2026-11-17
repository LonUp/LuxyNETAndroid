package com.example.xraycore

import org.json.JSONArray
import org.json.JSONObject

/** Fixed app policy, never merged with imported routing or obsolete experimental preferences. */
internal object BuiltInNetworkDefaults {
    const val VPN_DNS = "10.89.0.2"
    private const val ADS = "geosite:category-ads-all"
    private val localDomains = listOf("domain:localhost", "domain:local", "domain:lan", "domain:home.arpa")

    fun dns(country: String?): JSONObject {
        val domestic = localDomains + BuiltInRegion.directDomains(country)
        return JSONObject()
            .put("tag", "dns-proxy")
            .put("queryStrategy", "UseIP")
            .put("disableCache", false)
            .put("serveStale", true)
            .put("serveExpiredTTL", 60)
            // Sequential fallback avoids duplicating every query on mobile networks.
            .put("enableParallelQuery", false)
            .put("servers", JSONArray()
                .put(JSONObject().put("address", "localhost")
                    .put("domains", JSONArray(domestic)).put("skipFallback", true))
                // IP-literal DoH endpoints avoid a bootstrap DNS lookup outside the tunnel.
                .put(JSONObject().put("address", "https://1.1.1.1/dns-query").put("timeoutMs", 3000))
                .put(JSONObject().put("address", "https://8.8.8.8/dns-query").put("timeoutMs", 3000)))
    }

    fun sniffing(): JSONObject = JSONObject().put("enabled", true)
        .put("destOverride", JSONArray(listOf("http", "tls")))
        // Inspect only for routing: never replace an application's actual destination.
        .put("routeOnly", true)

    fun supportingOutbounds(): List<JSONObject> = listOf(
        JSONObject().put("tag", "direct-out").put("protocol", "freedom")
            .put("settings", JSONObject())
            .put("streamSettings", JSONObject().put("sockopt", JSONObject().put("domainStrategy", "UseIP"))),
        JSONObject().put("tag", "block-out").put("protocol", "blackhole")
            .put("settings", JSONObject()),
        JSONObject().put("tag", "dns-out").put("protocol", "dns")
            // Non-address records (including HTTPS/SVCB) remain usable, via the proxy.
            .put("settings", JSONObject().put("network", "tcp").put("address", "1.1.1.1").put("port", 53)
                .put("rules", JSONArray()
                    .put(JSONObject().put("domain", JSONArray(listOf(ADS)))
                        .put("action", "return").put("rCode", 3))
                    .put(JSONObject().put("qType", "1,28").put("action", "hijack"))))
            .put("streamSettings", JSONObject().put("sockopt", JSONObject().put("dialerProxy", "proxy-out")))
    )

    fun routing(country: String?): JSONObject {
        val ips = mutableListOf("geoip:private", "100.64.0.0/10")
        if (country != null) ips += "geoip:$country"
        val rules = JSONArray()
            // Never let a DIRECT rule satisfy the successful-tunnel check or leak public DNS.
            .put(rule("proxy-out").put("inboundTag", JSONArray(listOf("connection-check", "dns-proxy"))))
            .put(rule("dns-out").put("inboundTag", JSONArray(listOf("tun-in")))
                .put("network", "tcp,udp").put("port", "53"))
            .put(rule("block-out").put("domain", JSONArray(listOf(ADS))))
            .put(rule("block-out").put("ip", JSONArray(listOf(
                "0.0.0.0/8", "224.0.0.0/4", "240.0.0.0/4", "::/128", "ff00::/8"
            ))))
            .put(rule("direct-out").put("domain", JSONArray(localDomains + BuiltInRegion.directDomains(country))))
            .put(rule("direct-out").put("ip", JSONArray(ips)))
        // No catch-all rule: IPIfNonMatch must reach its DNS-assisted second pass.
        // Xray's first outbound (proxy-out) remains the unmatched-traffic default.
        return JSONObject().put("domainStrategy", "IPIfNonMatch").put("rules", rules)
    }

    private fun rule(outbound: String): JSONObject = JSONObject().put("type", "field").put("outboundTag", outbound)
}
