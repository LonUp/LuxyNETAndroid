package com.example.core

import com.example.data.local.RoutingRuleEntity
import com.example.data.model.GeoDatabaseStatus
import com.example.data.model.RoutingMode
import com.example.data.model.RuleAction
import com.example.data.model.RuleMatchType
import com.example.data.model.SocialServiceId
import com.example.data.model.StabilitySettings

data class DomesticCountryPreset(
    val code: String,
    val label: String,
    val nativeHint: String
)

data class SplitTunnelDecision(
    val action: RuleAction,
    val matchedRule: RoutingRuleEntity?,
    val evaluatedPreFakeDns: Boolean,
    val reason: String
)

object GeoRulesEngine {
    val DOMESTIC_COUNTRY_PRESETS = listOf(
        DomesticCountryPreset("IR", "Iran (IR)", ".ir / local banks & portals"),
        DomesticCountryPreset("CN", "China (CN)", ".cn / mainland CDNs"),
        DomesticCountryPreset("RU", "Russia (RU)", ".ru / local services"),
        DomesticCountryPreset("TR", "Turkey (TR)", ".tr / local services"),
        DomesticCountryPreset("AE", "UAE (AE)", ".ae / local services")
    )

    private val ADS_DOMAINS = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "adservice.google.com", "pagead2.googlesyndication.com", "app-measurement.com",
        "adnxs.com", "ads.twitter.com", "analytics.tiktok.com", "taboola.com",
        "outbrain.com", "criteo.com", "pubmatic.com", "rubiconproject.com",
        "scorecardresearch.com", "yektanet.com", "tapsell.ir", "adcolony.com",
        "unityads.unity3d.com"
    )

    private val PORN_DOMAINS = listOf(
        "pornhub.com", "xvideos.com", "xnxx.com", "xhamster.com",
        "redtube.com", "youporn.com", "onlyfans.com", "chaturbate.com",
        "spankbang.com", "brazzers.com", "livejasmin.com", "stripchat.com"
    )

    private val GEOSITE_COUNTRY_DOMAINS: Map<String, List<String>> = mapOf(
        "ir" to listOf(".ir", "shaparak.ir", "bmi.ir", "mellat.ir", "digikala.com", "aparat.com", "snapp.ir", "cafebazaar.ir"),
        "cn" to listOf(".cn", "baidu.com", "qq.com", "weixin.qq.com", "taobao.com", "tmall.com", "jd.com", "bilibili.com", "alipay.com"),
        "ru" to listOf(".ru", "vk.com", "yandex.ru", "sberbank.ru", "gosuslugi.ru", "mail.ru", "ozon.ru", "wildberries.ru"),
        "tr" to listOf(".tr", "trendyol.com", "hepsiburada.com", "sahibinden.com", "turkiye.gov.tr"),
        "ae" to listOf(".ae", "etisalat.ae", "du.ae", "u.ae", "noon.com"),
        "us" to listOf(".us", ".gov", ".mil"),
        "de" to listOf(".de"),
        "gb" to listOf(".uk", ".co.uk")
    )

    private val GEOIP_COUNTRY_CIDRS: Map<String, List<String>> = mapOf(
        "private" to listOf("127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16"),
        "telegram" to listOf("91.108.4.0/22", "91.108.56.0/22", "149.154.160.0/20", "185.76.151.0/24"),
        "ir" to listOf("5.22.0.0/16", "5.52.0.0/16", "5.160.0.0/14", "31.56.0.0/14", "37.98.0.0/16", "78.38.0.0/15", "185.105.0.0/16"),
        "cn" to listOf("1.2.4.0/24", "14.0.0.0/8", "36.96.0.0/12", "114.114.114.0/24", "119.28.0.0/15", "223.5.5.0/24"),
        "ru" to listOf("5.8.0.0/16", "46.39.0.0/16", "77.88.0.0/18", "87.250.224.0/19", "95.165.0.0/16", "185.4.64.0/22"),
        "tr" to listOf("78.160.0.0/12", "85.96.0.0/12", "176.232.0.0/13", "195.142.0.0/16"),
        "ae" to listOf("2.48.0.0/14", "5.30.0.0/15", "86.96.0.0/14", "94.200.0.0/13"),
        "us" to listOf("8.8.8.0/24", "17.0.0.0/8", "104.16.0.0/12", "172.217.0.0/16"),
        "de" to listOf("46.4.0.0/16", "88.198.0.0/16", "138.201.0.0/16"),
        "gb" to listOf("51.140.0.0/14", "81.128.0.0/10", "195.92.0.0/16")
    )

    private val ADS_REGEX = Regex("(^|\\.)(ads?|adservice|doubleclick|tracker|telemetry)\\.", RegexOption.IGNORE_CASE)
    private val PORN_REGEX = Regex("\\b(porn|xxx|xvideos|xnxx|xhamster|redtube|chaturbate)\\b", RegexOption.IGNORE_CASE)

    fun normalizeCountryCode(raw: String): String =
        raw.trim().filter { it in 'a'..'z' || it in 'A'..'Z' }.uppercase().take(2)

    fun isValidCountryCode(code: String): Boolean =
        code.length == 2 && code.all { it in 'A'..'Z' }

    fun parseIpv4ToLong(ip: String): Long? {
        val parts = ip.trim().split('.')
        if (parts.size != 4) return null
        var num = 0L
        for (part in parts) {
            if (part.isEmpty() || !part.all { it.isDigit() }) return null
            val oct = part.toIntOrNull() ?: return null
            if (oct !in 0..255) return null
            num = (num shl 8) or oct.toLong()
        }
        return num and 0xFFFFFFFFL
    }

    fun cidrContainsIp(cidr: String, ip: String): Boolean {
        val ipNum = parseIpv4ToLong(ip) ?: return false
        val parts = cidr.trim().split('/')
        val netNum = parseIpv4ToLong(parts.getOrNull(0).orEmpty()) ?: return false
        val prefix = if (parts.size > 1) parts[1].toIntOrNull() ?: return false else 32
        if (prefix !in 0..32) return false
        val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        return (ipNum and mask) == (netNum and mask)
    }

    fun isFakeDnsIpv4(ip: String): Boolean = cidrContainsIp("198.18.0.0/15", ip)

    fun isPrivateOrLanCidr(pattern: String): Boolean {
        val baseIp = pattern.trim().substringBefore('/').trim()
        if (baseIp.isEmpty()) return false
        return cidrContainsIp("127.0.0.0/8", baseIp) ||
            cidrContainsIp("10.0.0.0/8", baseIp) ||
            cidrContainsIp("172.16.0.0/12", baseIp) ||
            cidrContainsIp("192.168.0.0/16", baseIp) ||
            cidrContainsIp("169.254.0.0/16", baseIp)
    }

    fun domainMatchesSuffix(host: String, suffix: String): Boolean {
        val h = host.trim().lowercase().trimEnd('.')
        val s = suffix.trim().lowercase().trimStart('.').trimEnd('.')
        if (h.isEmpty() || s.isEmpty()) return false
        return h == s || h.endsWith(".$s")
    }

    fun domainMatchesGeosite(host: String, geositeTag: String): Boolean {
        val tag = geositeTag.trim().lowercase().removePrefix("geosite:")
        val h = host.trim().lowercase()
        if (h.isEmpty() || tag.isEmpty()) return false

        if (tag == "category-ads-all" || tag == "category-ads") {
            return ADS_DOMAINS.any { domainMatchesSuffix(h, it) } || ADS_REGEX.containsMatchIn(h)
        }
        if (tag == "category-porn") {
            return PORN_DOMAINS.any { domainMatchesSuffix(h, it) } || PORN_REGEX.containsMatchIn(h)
        }
        val social = SocialServiceId.fromId(tag)
        if (social != null) {
            return social.domains.any { domainMatchesSuffix(h, it) }
        }
        val countryDomains = GEOSITE_COUNTRY_DOMAINS[tag]
        if (countryDomains != null) {
            return countryDomains.any { domainMatchesSuffix(h, it) }
        }
        if (tag.length == 2 && tag.all { it in 'a'..'z' }) {
            return domainMatchesSuffix(h, tag)
        }
        return false
    }

    fun ipMatchesGeoip(ip: String, geoipTag: String): Boolean {
        val tag = geoipTag.trim().lowercase().removePrefix("geoip:")
        if (ip.isBlank() || tag.isEmpty()) return false
        val cidrs = GEOIP_COUNTRY_CIDRS[tag] ?: return false
        return cidrs.any { cidrContainsIp(it, ip) }
    }

    fun ruleMatchesDestination(
        rule: RoutingRuleEntity,
        domain: String?,
        destIp: String?
    ): Boolean {
        if (!rule.isEnabled) return false
        val cleanDomain = domain?.trim()?.lowercase().orEmpty()
        val cleanIp = destIp?.trim().orEmpty()
        val effectiveIp = if (cleanIp.isNotEmpty() && !isFakeDnsIpv4(cleanIp)) cleanIp else ""

        return when (RuleMatchType.parse(rule.matchType)) {
            RuleMatchType.GEOSITE -> cleanDomain.isNotEmpty() && domainMatchesGeosite(cleanDomain, rule.pattern)
            RuleMatchType.GEOIP -> effectiveIp.isNotEmpty() && ipMatchesGeoip(effectiveIp, rule.pattern)
            RuleMatchType.DOMAIN_SUFFIX -> cleanDomain.isNotEmpty() && domainMatchesSuffix(cleanDomain, rule.pattern)
            RuleMatchType.DOMAIN_KEYWORD -> cleanDomain.isNotEmpty() && cleanDomain.contains(rule.pattern.trim().lowercase())
            RuleMatchType.IP_CIDR -> effectiveIp.isNotEmpty() && cidrContainsIp(rule.pattern, effectiveIp)
        }
    }

    /**
     * Strict evaluation priority:
     * 0: BLOCK (highest)
     * 1: Custom user-defined rules
     * 2: Social Media rules
     * 3: Country & Domestic rules
     * 4: Baseline RFC1918 LAN rules (priority < 70)
     */
    fun ruleTierRank(rule: RoutingRuleEntity): Int {
        if (RuleAction.parse(rule.action) == RuleAction.BLOCK) return 0
        val key = rule.managedKey.orEmpty()
        if (key.isEmpty()) {
            return if (rule.priority < 70) 4 else 1
        }
        if (key.startsWith("geo-social-")) return 2
        if (key.startsWith("geo-country-") || key.startsWith("geo-domestic-")) return 3
        return 1
    }

    fun sortRulesForCompilation(rules: List<RoutingRuleEntity>): List<RoutingRuleEntity> {
        return rules
            .filter { it.isEnabled }
            .sortedWith(
                compareBy<RoutingRuleEntity>({ ruleTierRank(it) }, { it.priority }, { it.name })
            )
    }

    fun evaluateSplitTunnelDestination(
        domain: String?,
        destIp: String?,
        routingMode: RoutingMode,
        rules: List<RoutingRuleEntity>
    ): SplitTunnelDecision {
        val isFakeIp = !destIp.isNullOrBlank() && isFakeDnsIpv4(destIp)
        val cleanDomain = domain?.trim().orEmpty()

        if (routingMode == RoutingMode.GLOBAL) {
            return SplitTunnelDecision(
                action = RuleAction.PROXY,
                matchedRule = null,
                evaluatedPreFakeDns = true,
                reason = "Global Tunnel mode routes all destinations via proxy"
            )
        }

        val ordered = sortRulesForCompilation(rules)
        for (rule in ordered) {
            if (routingMode == RoutingMode.BYPASS_LAN) {
                val matchType = RuleMatchType.parse(rule.matchType)
                val isPrivateLan = matchType == RuleMatchType.GEOIP && rule.pattern.equals("private", ignoreCase = true)
                val isRfc1918 = matchType == RuleMatchType.IP_CIDR && isPrivateOrLanCidr(rule.pattern)
                if (!isPrivateLan && !isRfc1918 && RuleAction.parse(rule.action) != RuleAction.BLOCK) {
                    continue
                }
            }

            if (ruleMatchesDestination(rule, cleanDomain, destIp)) {
                val action = RuleAction.parse(rule.action)
                return SplitTunnelDecision(
                    action = action,
                    matchedRule = rule,
                    evaluatedPreFakeDns = cleanDomain.isNotEmpty() || !isFakeIp,
                    reason = "${action.name} matched ${rule.matchType}:${rule.pattern}${if (isFakeIp) " (pre-FakeDNS domain match)" else ""}"
                )
            }
        }

        return SplitTunnelDecision(
            action = RuleAction.PROXY,
            matchedRule = null,
            evaluatedPreFakeDns = true,
            reason = "Default catch-all routes via active proxy outbound"
        )
    }

    fun buildManagedGeoRules(
        settings: StabilitySettings,
        existingRules: List<RoutingRuleEntity>
    ): List<RoutingRuleEntity> {
        val customRules = existingRules.filter { !it.managedKey.orEmpty().startsWith("geo-") }
        val managed = mutableListOf<RoutingRuleEntity>()

        if (settings.blockAdsEnabled) {
            managed += RoutingRuleEntity(
                id = "managed-geo-ads-block",
                managedKey = "geo-ads-block",
                name = "Block Ads & Trackers",
                matchType = RuleMatchType.GEOSITE.name,
                pattern = "category-ads-all",
                action = RuleAction.BLOCK.name,
                isEnabled = true,
                priority = 5
            )
        }

        if (settings.blockPornEnabled) {
            managed += RoutingRuleEntity(
                id = "managed-geo-porn-block",
                managedKey = "geo-porn-block",
                name = "Block Adult / Porn Content",
                matchType = RuleMatchType.GEOSITE.name,
                pattern = "category-porn",
                action = RuleAction.BLOCK.name,
                isEnabled = true,
                priority = 6
            )
        }

        SocialServiceId.entries.forEachIndexed { idx, service ->
            val action = settings.socialPolicies[service] ?: RuleAction.PROXY
            if (action == RuleAction.PROXY) return@forEachIndexed
            val actionLabel = if (action == RuleAction.BLOCK) "Block" else "Direct"
            managed += RoutingRuleEntity(
                id = "managed-geo-social-${service.id}-site",
                managedKey = "geo-social-${service.id}-site",
                name = "${service.label} ($actionLabel)",
                matchType = RuleMatchType.GEOSITE.name,
                pattern = service.geositeTag,
                action = action.name,
                isEnabled = true,
                priority = 120 + idx * 2
            )
            if (service.geoipTag != null) {
                managed += RoutingRuleEntity(
                    id = "managed-geo-social-${service.id}-ip",
                    managedKey = "geo-social-${service.id}-ip",
                    name = "${service.label} IP ($actionLabel)",
                    matchType = RuleMatchType.GEOIP.name,
                    pattern = service.geoipTag,
                    action = action.name,
                    isEnabled = true,
                    priority = 121 + idx * 2
                )
            }
        }

        settings.countryPolicies.forEachIndexed { idx, entry ->
            val cc = normalizeCountryCode(entry.code)
            if (!isValidCountryCode(cc)) return@forEachIndexed
            val ccLower = cc.lowercase()
            val labelAction = if (entry.action == RuleAction.BLOCK) "Block" else "Direct"
            managed += RoutingRuleEntity(
                id = "managed-geo-country-$ccLower-site",
                managedKey = "geo-country-$ccLower-site",
                name = "Country $cc GeoSite ($labelAction)",
                matchType = RuleMatchType.GEOSITE.name,
                pattern = ccLower,
                action = entry.action.name,
                isEnabled = true,
                priority = 200 + idx * 3
            )
            managed += RoutingRuleEntity(
                id = "managed-geo-country-$ccLower-ip",
                managedKey = "geo-country-$ccLower-ip",
                name = "Country $cc GeoIP ($labelAction)",
                matchType = RuleMatchType.GEOIP.name,
                pattern = ccLower,
                action = entry.action.name,
                isEnabled = true,
                priority = 201 + idx * 3
            )
        }

        if (settings.domesticBypassEnabled) {
            val cc = normalizeCountryCode(settings.domesticCountryCode.ifBlank { "IR" })
            if (isValidCountryCode(cc)) {
                val ccLower = cc.lowercase()
                managed += RoutingRuleEntity(
                    id = "managed-geo-domestic-site",
                    managedKey = "geo-domestic-site",
                    name = "Domestic $cc GeoSite Bypass",
                    matchType = RuleMatchType.GEOSITE.name,
                    pattern = ccLower,
                    action = RuleAction.DIRECT.name,
                    isEnabled = true,
                    priority = 260
                )
                managed += RoutingRuleEntity(
                    id = "managed-geo-domestic-ip",
                    managedKey = "geo-domestic-ip",
                    name = "Domestic $cc GeoIP Bypass",
                    matchType = RuleMatchType.GEOIP.name,
                    pattern = ccLower,
                    action = RuleAction.DIRECT.name,
                    isEnabled = true,
                    priority = 261
                )
                managed += RoutingRuleEntity(
                    id = "managed-geo-domestic-tld",
                    managedKey = "geo-domestic-tld",
                    name = "Domestic .$ccLower TLD Bypass",
                    matchType = RuleMatchType.DOMAIN_SUFFIX.name,
                    pattern = ".$ccLower",
                    action = RuleAction.DIRECT.name,
                    isEnabled = true,
                    priority = 262
                )
            }
        }

        return managed + customRules
    }

    fun getBuiltInGeoDatabaseStatus(settings: StabilitySettings): GeoDatabaseStatus {
        val countryCount = GEOIP_COUNTRY_CIDRS.size
        val domainCount = ADS_DOMAINS.size +
            PORN_DOMAINS.size +
            SocialServiceId.entries.sumOf { it.domains.size } +
            GEOSITE_COUNTRY_DOMAINS.values.sumOf { it.size }
        return GeoDatabaseStatus(
            geoipVersion = settings.geoDbVersion.ifBlank { "2026.11-built-in" },
            geositeVersion = settings.geoDbVersion.ifBlank { "2026.11-built-in" },
            lastCheckedAt = settings.geoDbLastUpdatedAt,
            builtInRuleCount = countryCount + domainCount,
            source = "Loyalsoldier / v2fly + LuxyVPN Offline Ruleset"
        )
    }
}
