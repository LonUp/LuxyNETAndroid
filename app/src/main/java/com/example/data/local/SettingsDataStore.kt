package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.model.AntiCensorshipPreset
import com.example.core.ServerCountryResolver
import com.example.data.model.AppSplitTunnelMode
import com.example.data.model.ChainingMode
import com.example.data.model.CountryPolicyEntry
import com.example.data.model.DnsPoolStrategy
import com.example.data.model.RoutingMode
import com.example.data.model.RuleAction
import com.example.data.model.SocialServiceId
import com.example.data.model.StabilitySettings
import com.example.data.model.ThemeCodec
import com.example.data.model.ThemeConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.luxyDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "luxyvpn_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

class SettingsDataStore(private val context: Context) {

    private object Keys {
        val ACTIVE_PROFILE_ID = stringPreferencesKey("active_profile_id")
        val SELECTED_COUNTRY_CODE = stringPreferencesKey("selected_country_code")
        val THEME_CONFIG = stringPreferencesKey("theme_config")
        val LANGUAGE = stringPreferencesKey("language")
        val ROUTING_MODE = stringPreferencesKey("routing_mode")
        val AUTO_CONNECT_ON_LAUNCH = booleanPreferencesKey("auto_connect_on_launch")
        val AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        val SMART_FAILOVER = booleanPreferencesKey("smart_failover")
        val PROBE_INTERVAL_SEC = intPreferencesKey("probe_interval_sec")
        val MUX_ENABLED = booleanPreferencesKey("mux_enabled")
        val MUX_CONCURRENCY = intPreferencesKey("mux_concurrency")
        val TLS_FRAGMENT_ENABLED = booleanPreferencesKey("tls_fragment_enabled")
        val TCP_FAST_OPEN = booleanPreferencesKey("tcp_fast_open")
        val PRIMARY_DNS = stringPreferencesKey("primary_dns")
        val FALLBACK_DNS = stringPreferencesKey("fallback_dns")
        val DOH_ENABLED = booleanPreferencesKey("doh_enabled")
        val MTU = intPreferencesKey("mtu")
        val ANTI_CENSORSHIP_PRESET = stringPreferencesKey("anti_censorship_preset")
        val FRAGMENT_PACKETS = stringPreferencesKey("fragment_packets")
        val FRAGMENT_LENGTH = stringPreferencesKey("fragment_length")
        val FRAGMENT_INTERVAL = stringPreferencesKey("fragment_interval")
        val FRAGMENT_HOST = stringPreferencesKey("fragment_host")
        val FRAGMENT_MAX_SPLIT = stringPreferencesKey("fragment_max_split")
        val NOISE_ENABLED = booleanPreferencesKey("noise_enabled")
        val NOISE_TYPE = stringPreferencesKey("noise_type")
        val NOISE_PACKET_SIZE = stringPreferencesKey("noise_packet_size")
        val NOISE_DELAY_MS = stringPreferencesKey("noise_delay_ms")
        val NOISE_APPLY_TO = stringPreferencesKey("noise_apply_to")
        val UTLS_FINGERPRINT = stringPreferencesKey("utls_fingerprint")
        val FAKE_DNS_ENABLED = booleanPreferencesKey("fake_dns_enabled")
        val SNIFFING_ENABLED = booleanPreferencesKey("sniffing_enabled")
        val BLOCK_QUIC_UDP = booleanPreferencesKey("block_quic_udp")
        val XHTTP_MODE = stringPreferencesKey("xhttp_mode")
        val XHTTP_PADDING_BYTES = stringPreferencesKey("xhttp_padding_bytes")
        val DOH_SERVER_URL = stringPreferencesKey("doh_server_url")
        val DOMAIN_STRATEGY = stringPreferencesKey("domain_strategy")
        val CIPHER_SUITES = stringPreferencesKey("cipher_suites")
        val FINAL_MASK_TYPE = stringPreferencesKey("final_mask_type")
        val ECH_ENABLED = booleanPreferencesKey("ech_enabled")
        val ECH_CONFIG_LIST = stringPreferencesKey("ech_config_list")
        val ECH_FORCE_QUERY = stringPreferencesKey("ech_force_query")
        val HAPPY_EYEBALLS_ENABLED = booleanPreferencesKey("happy_eyeballs_enabled")
        val HAPPY_EYEBALLS_DELAY_MS = intPreferencesKey("happy_eyeballs_delay_ms")
        val ALLOW_UNENCRYPTED_VLESS = booleanPreferencesKey("allow_unencrypted_vless")
        val SUBSCRIPTION_TOP_N = intPreferencesKey("subscription_top_n")
        val WARP_ENDPOINT = stringPreferencesKey("warp_endpoint")
        val WARP_PRIVATE_KEY = stringPreferencesKey("warp_private_key")
        val WARP_PUBLIC_KEY = stringPreferencesKey("warp_public_key")
        val WARP_RESERVED = stringPreferencesKey("warp_reserved")
        val WARP_LOCAL_ADDRESS = stringPreferencesKey("warp_local_address")
        val AWG_JC = intPreferencesKey("awg_jc")
        val AWG_JMIN = intPreferencesKey("awg_jmin")
        val AWG_JMAX = intPreferencesKey("awg_jmax")
        val AWG_S1 = intPreferencesKey("awg_s1")
        val AWG_S2 = intPreferencesKey("awg_s2")
        val AWG_H1 = intPreferencesKey("awg_h1")
        val AWG_H2 = intPreferencesKey("awg_h2")
        val AWG_H3 = intPreferencesKey("awg_h3")
        val AWG_H4 = intPreferencesKey("awg_h4")
        val WARP_NOISE_PRESET = stringPreferencesKey("warp_noise_preset")
        val CHAINING_MODE = stringPreferencesKey("chaining_mode")
        val RELAY_NODE_ID = stringPreferencesKey("relay_node_id")
        val MIXED_CASE_SNI = booleanPreferencesKey("mixed_case_sni")
        val TLS_PADDING_ENABLED = booleanPreferencesKey("tls_padding_enabled")
        val REALITY_FRAGMENT_ENABLED = booleanPreferencesKey("reality_fragment_enabled")
        val FINAL_MASK_FAMILY = stringPreferencesKey("final_mask_family")
        val TCP_HANDSHAKE_TIMEOUT_MS = intPreferencesKey("tcp_handshake_timeout_ms")
        val TLS_HANDSHAKE_TIMEOUT_MS = intPreferencesKey("tls_handshake_timeout_ms")
        val DNS_POOL_STRATEGY = stringPreferencesKey("dns_pool_strategy")
        val DNS_INTEGRITY_FILTER = booleanPreferencesKey("dns_integrity_filter")
        val DNS_DOT_SERVER = stringPreferencesKey("dns_dot_server")
        val DNS_TCP_ENABLED = booleanPreferencesKey("dns_tcp_enabled")
        val DNS_DOT_ENABLED = booleanPreferencesKey("dns_dot_enabled")
        val DNS_UDP_ENABLED = booleanPreferencesKey("dns_udp_enabled")
        val APP_SPLIT_TUNNEL_MODE = stringPreferencesKey("app_split_tunnel_mode")
        val SPLIT_TUNNEL_PACKAGES = stringPreferencesKey("split_tunnel_packages")
        val STRICT_UID_ISOLATION = booleanPreferencesKey("strict_uid_isolation")
        val FLAG_SECURE_ENABLED = booleanPreferencesKey("flag_secure_enabled")
        val EXCLUDE_FROM_RECENTS = booleanPreferencesKey("exclude_from_recents")
        val APP_LOCK_ENABLED = booleanPreferencesKey("app_lock_enabled")
        val SUBSCRIPTION_USER_AGENT = stringPreferencesKey("subscription_user_agent")
        val ADAPTIVE_FALLBACK_ENABLED = booleanPreferencesKey("adaptive_fallback_enabled")
        val FAIL_CLOSED_ECH = booleanPreferencesKey("fail_closed_ech")
        val STALL_THRESHOLD_SEC = intPreferencesKey("stall_threshold_sec")
        val DOMESTIC_BYPASS_ENABLED = booleanPreferencesKey("domestic_bypass_enabled")
        val DOMESTIC_COUNTRY_CODE = stringPreferencesKey("domestic_country_code")
        val BLOCK_ADS_ENABLED = booleanPreferencesKey("block_ads_enabled")
        val BLOCK_PORN_ENABLED = booleanPreferencesKey("block_porn_enabled")
        val SOCIAL_POLICIES_JSON = stringPreferencesKey("social_policies_json")
        val COUNTRY_POLICIES_JSON = stringPreferencesKey("country_policies_json")
        val GEO_DB_VERSION = stringPreferencesKey("geo_db_version")
        val GEO_DB_LAST_UPDATED_AT = longPreferencesKey("geo_db_last_updated_at")
        val EXPERIMENTAL_MODE_ENABLED = booleanPreferencesKey("experimental_mode_enabled")
        val MANUAL_CONFIGS_ENABLED_FOR_CONNECTION = booleanPreferencesKey("manual_configs_enabled_for_connection")
        val DISMISSED_CLEANUP_KEYS = stringSetPreferencesKey("dismissed_cleanup_keys")
    }

    val settingsFlow: Flow<StabilitySettings> = context.luxyDataStore.data.map { prefs ->
        val def = StabilitySettings()
        StabilitySettings(
            activeProfileId = prefs[Keys.ACTIVE_PROFILE_ID],
            selectedCountryCode = ServerCountryResolver.normalizeCode(prefs[Keys.SELECTED_COUNTRY_CODE]),
            theme = ThemeCodec.decode(prefs[Keys.THEME_CONFIG]) ?: ThemeConfig(),
            language = prefs[Keys.LANGUAGE] ?: def.language,
            routingMode = RoutingMode.parse(prefs[Keys.ROUTING_MODE]),
            autoConnectOnLaunch = prefs[Keys.AUTO_CONNECT_ON_LAUNCH] ?: def.autoConnectOnLaunch,
            autoReconnect = prefs[Keys.AUTO_RECONNECT] ?: def.autoReconnect,
            smartFailover = prefs[Keys.SMART_FAILOVER] ?: def.smartFailover,
            probeIntervalSec = prefs[Keys.PROBE_INTERVAL_SEC] ?: def.probeIntervalSec,
            muxEnabled = prefs[Keys.MUX_ENABLED] ?: def.muxEnabled,
            muxConcurrency = prefs[Keys.MUX_CONCURRENCY] ?: def.muxConcurrency,
            tlsFragmentEnabled = prefs[Keys.TLS_FRAGMENT_ENABLED] ?: def.tlsFragmentEnabled,
            tcpFastOpen = prefs[Keys.TCP_FAST_OPEN] ?: def.tcpFastOpen,
            primaryDns = prefs[Keys.PRIMARY_DNS] ?: def.primaryDns,
            fallbackDns = prefs[Keys.FALLBACK_DNS] ?: def.fallbackDns,
            dohEnabled = prefs[Keys.DOH_ENABLED] ?: def.dohEnabled,
            mtu = prefs[Keys.MTU] ?: def.mtu,
            antiCensorshipPreset = AntiCensorshipPreset.parse(prefs[Keys.ANTI_CENSORSHIP_PRESET]),
            fragmentPackets = prefs[Keys.FRAGMENT_PACKETS] ?: def.fragmentPackets,
            fragmentLength = prefs[Keys.FRAGMENT_LENGTH] ?: def.fragmentLength,
            fragmentInterval = prefs[Keys.FRAGMENT_INTERVAL] ?: def.fragmentInterval,
            fragmentHost = prefs[Keys.FRAGMENT_HOST] ?: def.fragmentHost,
            fragmentMaxSplit = prefs[Keys.FRAGMENT_MAX_SPLIT] ?: def.fragmentMaxSplit,
            noiseEnabled = prefs[Keys.NOISE_ENABLED] ?: def.noiseEnabled,
            noiseType = prefs[Keys.NOISE_TYPE] ?: def.noiseType,
            noisePacketSize = prefs[Keys.NOISE_PACKET_SIZE] ?: def.noisePacketSize,
            noiseDelayMs = prefs[Keys.NOISE_DELAY_MS] ?: def.noiseDelayMs,
            noiseApplyTo = prefs[Keys.NOISE_APPLY_TO] ?: def.noiseApplyTo,
            utlsFingerprint = prefs[Keys.UTLS_FINGERPRINT] ?: def.utlsFingerprint,
            fakeDnsEnabled = prefs[Keys.FAKE_DNS_ENABLED] ?: def.fakeDnsEnabled,
            sniffingEnabled = prefs[Keys.SNIFFING_ENABLED] ?: def.sniffingEnabled,
            blockQuicUdp = prefs[Keys.BLOCK_QUIC_UDP] ?: def.blockQuicUdp,
            xhttpMode = prefs[Keys.XHTTP_MODE] ?: def.xhttpMode,
            xhttpPaddingBytes = prefs[Keys.XHTTP_PADDING_BYTES] ?: def.xhttpPaddingBytes,
            dohServerUrl = prefs[Keys.DOH_SERVER_URL] ?: def.dohServerUrl,
            domainStrategy = prefs[Keys.DOMAIN_STRATEGY] ?: def.domainStrategy,
            cipherSuites = prefs[Keys.CIPHER_SUITES] ?: def.cipherSuites,
            finalMaskType = prefs[Keys.FINAL_MASK_TYPE] ?: def.finalMaskType,
            echEnabled = prefs[Keys.ECH_ENABLED] ?: def.echEnabled,
            echConfigList = prefs[Keys.ECH_CONFIG_LIST] ?: def.echConfigList,
            echForceQuery = prefs[Keys.ECH_FORCE_QUERY] ?: def.echForceQuery,
            happyEyeballsEnabled = prefs[Keys.HAPPY_EYEBALLS_ENABLED] ?: def.happyEyeballsEnabled,
            happyEyeballsTryDelayMs = prefs[Keys.HAPPY_EYEBALLS_DELAY_MS] ?: def.happyEyeballsTryDelayMs,
            allowUnencryptedVless = prefs[Keys.ALLOW_UNENCRYPTED_VLESS] ?: def.allowUnencryptedVless,
            subscriptionTopNLimit = prefs[Keys.SUBSCRIPTION_TOP_N] ?: def.subscriptionTopNLimit,
            warpEndpoint = prefs[Keys.WARP_ENDPOINT] ?: def.warpEndpoint,
            warpPrivateKey = prefs[Keys.WARP_PRIVATE_KEY] ?: def.warpPrivateKey,
            warpPublicKey = prefs[Keys.WARP_PUBLIC_KEY] ?: def.warpPublicKey,
            warpReserved = prefs[Keys.WARP_RESERVED] ?: def.warpReserved,
            warpLocalAddress = prefs[Keys.WARP_LOCAL_ADDRESS] ?: def.warpLocalAddress,
            awgJc = prefs[Keys.AWG_JC] ?: def.awgJc,
            awgJmin = prefs[Keys.AWG_JMIN] ?: def.awgJmin,
            awgJmax = prefs[Keys.AWG_JMAX] ?: def.awgJmax,
            awgS1 = prefs[Keys.AWG_S1] ?: def.awgS1,
            awgS2 = prefs[Keys.AWG_S2] ?: def.awgS2,
            awgH1 = prefs[Keys.AWG_H1] ?: def.awgH1,
            awgH2 = prefs[Keys.AWG_H2] ?: def.awgH2,
            awgH3 = prefs[Keys.AWG_H3] ?: def.awgH3,
            awgH4 = prefs[Keys.AWG_H4] ?: def.awgH4,
            warpNoisePreset = prefs[Keys.WARP_NOISE_PRESET] ?: def.warpNoisePreset,
            chainingMode = ChainingMode.parse(prefs[Keys.CHAINING_MODE]),
            relayNodeId = prefs[Keys.RELAY_NODE_ID],
            mixedCaseSni = prefs[Keys.MIXED_CASE_SNI] ?: def.mixedCaseSni,
            tlsPaddingEnabled = prefs[Keys.TLS_PADDING_ENABLED] ?: def.tlsPaddingEnabled,
            realityFragmentEnabled = prefs[Keys.REALITY_FRAGMENT_ENABLED] ?: def.realityFragmentEnabled,
            finalMaskFamily = prefs[Keys.FINAL_MASK_FAMILY] ?: def.finalMaskFamily,
            tcpHandshakeTimeoutMs = prefs[Keys.TCP_HANDSHAKE_TIMEOUT_MS] ?: def.tcpHandshakeTimeoutMs,
            tlsHandshakeTimeoutMs = prefs[Keys.TLS_HANDSHAKE_TIMEOUT_MS] ?: def.tlsHandshakeTimeoutMs,
            dnsPoolStrategy = DnsPoolStrategy.parse(prefs[Keys.DNS_POOL_STRATEGY]),
            dnsIntegrityFilterEnabled = prefs[Keys.DNS_INTEGRITY_FILTER] ?: def.dnsIntegrityFilterEnabled,
            dnsDotServer = prefs[Keys.DNS_DOT_SERVER] ?: def.dnsDotServer,
            dnsTcpEnabled = prefs[Keys.DNS_TCP_ENABLED] ?: def.dnsTcpEnabled,
            dnsDotEnabled = prefs[Keys.DNS_DOT_ENABLED] ?: def.dnsDotEnabled,
            dnsUdpEnabled = prefs[Keys.DNS_UDP_ENABLED] ?: def.dnsUdpEnabled,
            appSplitTunnelMode = AppSplitTunnelMode.parse(prefs[Keys.APP_SPLIT_TUNNEL_MODE]),
            splitTunnelPackages = prefs[Keys.SPLIT_TUNNEL_PACKAGES] ?: def.splitTunnelPackages,
            strictUidIsolationEnabled = prefs[Keys.STRICT_UID_ISOLATION] ?: def.strictUidIsolationEnabled,
            flagSecureEnabled = prefs[Keys.FLAG_SECURE_ENABLED] ?: def.flagSecureEnabled,
            excludeFromRecents = prefs[Keys.EXCLUDE_FROM_RECENTS] ?: def.excludeFromRecents,
            appLockEnabled = prefs[Keys.APP_LOCK_ENABLED] ?: def.appLockEnabled,
            subscriptionUserAgent = prefs[Keys.SUBSCRIPTION_USER_AGENT] ?: def.subscriptionUserAgent,
            adaptiveFallbackEnabled = prefs[Keys.ADAPTIVE_FALLBACK_ENABLED] ?: def.adaptiveFallbackEnabled,
            failClosedEch = prefs[Keys.FAIL_CLOSED_ECH] ?: def.failClosedEch,
            stallThresholdSec = prefs[Keys.STALL_THRESHOLD_SEC] ?: def.stallThresholdSec,
            domesticBypassEnabled = prefs[Keys.DOMESTIC_BYPASS_ENABLED] ?: def.domesticBypassEnabled,
            domesticCountryCode = prefs[Keys.DOMESTIC_COUNTRY_CODE] ?: def.domesticCountryCode,
            blockAdsEnabled = prefs[Keys.BLOCK_ADS_ENABLED] ?: def.blockAdsEnabled,
            blockPornEnabled = prefs[Keys.BLOCK_PORN_ENABLED] ?: def.blockPornEnabled,
            socialPolicies = parseSocialPolicies(prefs[Keys.SOCIAL_POLICIES_JSON], def.socialPolicies),
            countryPolicies = parseCountryPolicies(prefs[Keys.COUNTRY_POLICIES_JSON]),
            geoDbVersion = prefs[Keys.GEO_DB_VERSION] ?: def.geoDbVersion,
            geoDbLastUpdatedAt = prefs[Keys.GEO_DB_LAST_UPDATED_AT],
            manualConfigsEnabledForConnection = prefs[Keys.MANUAL_CONFIGS_ENABLED_FOR_CONNECTION] ?: def.manualConfigsEnabledForConnection,
            dismissedCleanupKeys = prefs[Keys.DISMISSED_CLEANUP_KEYS] ?: def.dismissedCleanupKeys
        )
    }

    suspend fun updateSettings(transform: (StabilitySettings) -> StabilitySettings) {
        val currentPrefs = context.luxyDataStore
        var updatedSnapshot = StabilitySettings()
        val def = StabilitySettings()
        currentPrefs.edit { prefs ->
            val current = StabilitySettings(
                activeProfileId = prefs[Keys.ACTIVE_PROFILE_ID],
                selectedCountryCode = ServerCountryResolver.normalizeCode(prefs[Keys.SELECTED_COUNTRY_CODE]),
                theme = ThemeCodec.decode(prefs[Keys.THEME_CONFIG]) ?: ThemeConfig(),
                language = prefs[Keys.LANGUAGE] ?: "fa",
                routingMode = RoutingMode.parse(prefs[Keys.ROUTING_MODE]),
                autoConnectOnLaunch = prefs[Keys.AUTO_CONNECT_ON_LAUNCH] ?: false,
                autoReconnect = prefs[Keys.AUTO_RECONNECT] ?: true,
                smartFailover = prefs[Keys.SMART_FAILOVER] ?: true,
                probeIntervalSec = prefs[Keys.PROBE_INTERVAL_SEC] ?: 15,
                muxEnabled = prefs[Keys.MUX_ENABLED] ?: true,
                muxConcurrency = prefs[Keys.MUX_CONCURRENCY] ?: 8,
                tlsFragmentEnabled = prefs[Keys.TLS_FRAGMENT_ENABLED] ?: false,
                tcpFastOpen = prefs[Keys.TCP_FAST_OPEN] ?: false,
                primaryDns = prefs[Keys.PRIMARY_DNS] ?: "1.1.1.1",
                fallbackDns = prefs[Keys.FALLBACK_DNS] ?: "9.9.9.9",
                dohEnabled = prefs[Keys.DOH_ENABLED] ?: true,
                mtu = prefs[Keys.MTU] ?: 1400,
                antiCensorshipPreset = AntiCensorshipPreset.parse(prefs[Keys.ANTI_CENSORSHIP_PRESET]),
                fragmentPackets = prefs[Keys.FRAGMENT_PACKETS] ?: "tlshello",
                fragmentLength = prefs[Keys.FRAGMENT_LENGTH] ?: "10-20",
                fragmentInterval = prefs[Keys.FRAGMENT_INTERVAL] ?: "10-20",
                fragmentHost = prefs[Keys.FRAGMENT_HOST] ?: "",
                fragmentMaxSplit = prefs[Keys.FRAGMENT_MAX_SPLIT] ?: "32-64",
                noiseEnabled = prefs[Keys.NOISE_ENABLED] ?: false,
                noiseType = prefs[Keys.NOISE_TYPE] ?: "rand",
                noisePacketSize = prefs[Keys.NOISE_PACKET_SIZE] ?: "10-20",
                noiseDelayMs = prefs[Keys.NOISE_DELAY_MS] ?: "10-20",
                noiseApplyTo = prefs[Keys.NOISE_APPLY_TO] ?: "ip",
                utlsFingerprint = prefs[Keys.UTLS_FINGERPRINT] ?: "randomized",
                fakeDnsEnabled = prefs[Keys.FAKE_DNS_ENABLED] ?: true,
                sniffingEnabled = prefs[Keys.SNIFFING_ENABLED] ?: true,
                blockQuicUdp = prefs[Keys.BLOCK_QUIC_UDP] ?: true,
                xhttpMode = prefs[Keys.XHTTP_MODE] ?: "auto",
                xhttpPaddingBytes = prefs[Keys.XHTTP_PADDING_BYTES] ?: "100-1000",
                dohServerUrl = prefs[Keys.DOH_SERVER_URL] ?: "https://cloudflare-dns.com/dns-query",
                domainStrategy = prefs[Keys.DOMAIN_STRATEGY] ?: "IPIfNonMatch",
                cipherSuites = prefs[Keys.CIPHER_SUITES] ?: "",
                finalMaskType = prefs[Keys.FINAL_MASK_TYPE] ?: "none",
                echEnabled = prefs[Keys.ECH_ENABLED] ?: false,
                echConfigList = prefs[Keys.ECH_CONFIG_LIST] ?: "",
                echForceQuery = prefs[Keys.ECH_FORCE_QUERY] ?: "half",
                happyEyeballsEnabled = prefs[Keys.HAPPY_EYEBALLS_ENABLED] ?: true,
                happyEyeballsTryDelayMs = prefs[Keys.HAPPY_EYEBALLS_DELAY_MS] ?: 250,
                allowUnencryptedVless = prefs[Keys.ALLOW_UNENCRYPTED_VLESS] ?: false,
                subscriptionTopNLimit = prefs[Keys.SUBSCRIPTION_TOP_N] ?: 50,
                warpEndpoint = prefs[Keys.WARP_ENDPOINT] ?: "162.159.192.1:2408",
                warpPrivateKey = prefs[Keys.WARP_PRIVATE_KEY] ?: "",
                warpPublicKey = prefs[Keys.WARP_PUBLIC_KEY] ?: "",
                warpReserved = prefs[Keys.WARP_RESERVED] ?: "0,0,0",
                warpLocalAddress = prefs[Keys.WARP_LOCAL_ADDRESS] ?: "172.16.0.2/32",
                awgJc = prefs[Keys.AWG_JC] ?: 4,
                awgJmin = prefs[Keys.AWG_JMIN] ?: 40,
                awgJmax = prefs[Keys.AWG_JMAX] ?: 70,
                awgS1 = prefs[Keys.AWG_S1] ?: 0,
                awgS2 = prefs[Keys.AWG_S2] ?: 0,
                awgH1 = prefs[Keys.AWG_H1] ?: 1,
                awgH2 = prefs[Keys.AWG_H2] ?: 2,
                awgH3 = prefs[Keys.AWG_H3] ?: 3,
                awgH4 = prefs[Keys.AWG_H4] ?: 4,
                warpNoisePreset = prefs[Keys.WARP_NOISE_PRESET] ?: "quic",
                chainingMode = ChainingMode.parse(prefs[Keys.CHAINING_MODE]),
                relayNodeId = prefs[Keys.RELAY_NODE_ID],
                mixedCaseSni = prefs[Keys.MIXED_CASE_SNI] ?: false,
                tlsPaddingEnabled = prefs[Keys.TLS_PADDING_ENABLED] ?: false,
                realityFragmentEnabled = prefs[Keys.REALITY_FRAGMENT_ENABLED] ?: false,
                finalMaskFamily = prefs[Keys.FINAL_MASK_FAMILY] ?: "tcp",
                tcpHandshakeTimeoutMs = prefs[Keys.TCP_HANDSHAKE_TIMEOUT_MS] ?: 4000,
                tlsHandshakeTimeoutMs = prefs[Keys.TLS_HANDSHAKE_TIMEOUT_MS] ?: 4000,
                dnsPoolStrategy = DnsPoolStrategy.parse(prefs[Keys.DNS_POOL_STRATEGY]),
                dnsIntegrityFilterEnabled = prefs[Keys.DNS_INTEGRITY_FILTER] ?: true,
                dnsDotServer = prefs[Keys.DNS_DOT_SERVER] ?: "1.1.1.1:853",
                dnsTcpEnabled = prefs[Keys.DNS_TCP_ENABLED] ?: true,
                dnsDotEnabled = prefs[Keys.DNS_DOT_ENABLED] ?: true,
                dnsUdpEnabled = prefs[Keys.DNS_UDP_ENABLED] ?: true,
                appSplitTunnelMode = AppSplitTunnelMode.parse(prefs[Keys.APP_SPLIT_TUNNEL_MODE]),
                splitTunnelPackages = prefs[Keys.SPLIT_TUNNEL_PACKAGES] ?: "",
                strictUidIsolationEnabled = prefs[Keys.STRICT_UID_ISOLATION] ?: true,
                flagSecureEnabled = prefs[Keys.FLAG_SECURE_ENABLED] ?: false,
                excludeFromRecents = prefs[Keys.EXCLUDE_FROM_RECENTS] ?: false,
                appLockEnabled = prefs[Keys.APP_LOCK_ENABLED] ?: false,
                subscriptionUserAgent = prefs[Keys.SUBSCRIPTION_USER_AGENT] ?: "LuxyVPN/3.0",
                adaptiveFallbackEnabled = prefs[Keys.ADAPTIVE_FALLBACK_ENABLED] ?: true,
                failClosedEch = prefs[Keys.FAIL_CLOSED_ECH] ?: false,
                stallThresholdSec = prefs[Keys.STALL_THRESHOLD_SEC] ?: 8,
                domesticBypassEnabled = prefs[Keys.DOMESTIC_BYPASS_ENABLED] ?: false,
                domesticCountryCode = prefs[Keys.DOMESTIC_COUNTRY_CODE] ?: "IR",
                blockAdsEnabled = prefs[Keys.BLOCK_ADS_ENABLED] ?: false,
                blockPornEnabled = prefs[Keys.BLOCK_PORN_ENABLED] ?: false,
                socialPolicies = parseSocialPolicies(prefs[Keys.SOCIAL_POLICIES_JSON], StabilitySettings().socialPolicies),
                countryPolicies = parseCountryPolicies(prefs[Keys.COUNTRY_POLICIES_JSON]),
                geoDbVersion = prefs[Keys.GEO_DB_VERSION] ?: "2026.11-built-in",
                geoDbLastUpdatedAt = prefs[Keys.GEO_DB_LAST_UPDATED_AT],
                experimentalModeEnabled = prefs[Keys.EXPERIMENTAL_MODE_ENABLED] ?: false,
                manualConfigsEnabledForConnection = prefs[Keys.MANUAL_CONFIGS_ENABLED_FOR_CONNECTION] ?: true,
                dismissedCleanupKeys = prefs[Keys.DISMISSED_CLEANUP_KEYS] ?: def.dismissedCleanupKeys
            )
            val next = transform(current)
            updatedSnapshot = next

            if (next.activeProfileId != null) prefs[Keys.ACTIVE_PROFILE_ID] = next.activeProfileId else prefs.remove(Keys.ACTIVE_PROFILE_ID)
            val normalizedCountry = ServerCountryResolver.normalizeCode(next.selectedCountryCode)
            if (normalizedCountry != null) prefs[Keys.SELECTED_COUNTRY_CODE] = normalizedCountry else prefs.remove(Keys.SELECTED_COUNTRY_CODE)
            prefs[Keys.THEME_CONFIG] = ThemeCodec.encode(next.theme)
            prefs[Keys.LANGUAGE] = next.language
            prefs[Keys.ROUTING_MODE] = next.routingMode.name
            prefs[Keys.AUTO_CONNECT_ON_LAUNCH] = next.autoConnectOnLaunch
            prefs[Keys.AUTO_RECONNECT] = next.autoReconnect
            prefs[Keys.SMART_FAILOVER] = next.smartFailover
            prefs[Keys.PROBE_INTERVAL_SEC] = next.probeIntervalSec
            prefs[Keys.MUX_ENABLED] = next.muxEnabled
            prefs[Keys.MUX_CONCURRENCY] = next.muxConcurrency
            prefs[Keys.TLS_FRAGMENT_ENABLED] = next.tlsFragmentEnabled
            prefs[Keys.TCP_FAST_OPEN] = next.tcpFastOpen
            prefs[Keys.PRIMARY_DNS] = next.primaryDns
            prefs[Keys.FALLBACK_DNS] = next.fallbackDns
            prefs[Keys.DOH_ENABLED] = next.dohEnabled
            prefs[Keys.MTU] = next.mtu
            prefs[Keys.ANTI_CENSORSHIP_PRESET] = next.antiCensorshipPreset.name
            prefs[Keys.FRAGMENT_PACKETS] = next.fragmentPackets
            prefs[Keys.FRAGMENT_LENGTH] = next.fragmentLength
            prefs[Keys.FRAGMENT_INTERVAL] = next.fragmentInterval
            prefs[Keys.FRAGMENT_HOST] = next.fragmentHost
            prefs[Keys.FRAGMENT_MAX_SPLIT] = next.fragmentMaxSplit
            prefs[Keys.NOISE_ENABLED] = next.noiseEnabled
            prefs[Keys.NOISE_TYPE] = next.noiseType
            prefs[Keys.NOISE_PACKET_SIZE] = next.noisePacketSize
            prefs[Keys.NOISE_DELAY_MS] = next.noiseDelayMs
            prefs[Keys.NOISE_APPLY_TO] = next.noiseApplyTo
            prefs[Keys.UTLS_FINGERPRINT] = next.utlsFingerprint
            prefs[Keys.FAKE_DNS_ENABLED] = next.fakeDnsEnabled
            prefs[Keys.SNIFFING_ENABLED] = next.sniffingEnabled
            prefs[Keys.BLOCK_QUIC_UDP] = next.blockQuicUdp
            prefs[Keys.XHTTP_MODE] = next.xhttpMode
            prefs[Keys.XHTTP_PADDING_BYTES] = next.xhttpPaddingBytes
            prefs[Keys.DOH_SERVER_URL] = next.dohServerUrl
            prefs[Keys.DOMAIN_STRATEGY] = next.domainStrategy
            prefs[Keys.CIPHER_SUITES] = next.cipherSuites
            prefs[Keys.FINAL_MASK_TYPE] = next.finalMaskType
            prefs[Keys.ECH_ENABLED] = next.echEnabled
            prefs[Keys.ECH_CONFIG_LIST] = next.echConfigList
            prefs[Keys.ECH_FORCE_QUERY] = next.echForceQuery
            prefs[Keys.HAPPY_EYEBALLS_ENABLED] = next.happyEyeballsEnabled
            prefs[Keys.HAPPY_EYEBALLS_DELAY_MS] = next.happyEyeballsTryDelayMs
            prefs[Keys.ALLOW_UNENCRYPTED_VLESS] = next.allowUnencryptedVless
            prefs[Keys.SUBSCRIPTION_TOP_N] = next.subscriptionTopNLimit
            prefs[Keys.WARP_ENDPOINT] = next.warpEndpoint
            prefs[Keys.WARP_PRIVATE_KEY] = next.warpPrivateKey
            prefs[Keys.WARP_PUBLIC_KEY] = next.warpPublicKey
            prefs[Keys.WARP_RESERVED] = next.warpReserved
            prefs[Keys.WARP_LOCAL_ADDRESS] = next.warpLocalAddress
            prefs[Keys.AWG_JC] = next.awgJc
            prefs[Keys.AWG_JMIN] = next.awgJmin
            prefs[Keys.AWG_JMAX] = next.awgJmax
            prefs[Keys.AWG_S1] = next.awgS1
            prefs[Keys.AWG_S2] = next.awgS2
            prefs[Keys.AWG_H1] = next.awgH1
            prefs[Keys.AWG_H2] = next.awgH2
            prefs[Keys.AWG_H3] = next.awgH3
            prefs[Keys.AWG_H4] = next.awgH4
            prefs[Keys.WARP_NOISE_PRESET] = next.warpNoisePreset
            prefs[Keys.CHAINING_MODE] = next.chainingMode.name
            if (next.relayNodeId != null) prefs[Keys.RELAY_NODE_ID] = next.relayNodeId else prefs.remove(Keys.RELAY_NODE_ID)
            prefs[Keys.MIXED_CASE_SNI] = next.mixedCaseSni
            prefs[Keys.TLS_PADDING_ENABLED] = next.tlsPaddingEnabled
            prefs[Keys.REALITY_FRAGMENT_ENABLED] = next.realityFragmentEnabled
            prefs[Keys.FINAL_MASK_FAMILY] = next.finalMaskFamily
            prefs[Keys.TCP_HANDSHAKE_TIMEOUT_MS] = next.tcpHandshakeTimeoutMs
            prefs[Keys.TLS_HANDSHAKE_TIMEOUT_MS] = next.tlsHandshakeTimeoutMs
            prefs[Keys.DNS_POOL_STRATEGY] = next.dnsPoolStrategy.name
            prefs[Keys.DNS_INTEGRITY_FILTER] = next.dnsIntegrityFilterEnabled
            prefs[Keys.DNS_DOT_SERVER] = next.dnsDotServer
            prefs[Keys.DNS_TCP_ENABLED] = next.dnsTcpEnabled
            prefs[Keys.DNS_DOT_ENABLED] = next.dnsDotEnabled
            prefs[Keys.DNS_UDP_ENABLED] = next.dnsUdpEnabled
            prefs[Keys.APP_SPLIT_TUNNEL_MODE] = next.appSplitTunnelMode.name
            prefs[Keys.SPLIT_TUNNEL_PACKAGES] = next.splitTunnelPackages
            prefs[Keys.STRICT_UID_ISOLATION] = next.strictUidIsolationEnabled
            prefs[Keys.FLAG_SECURE_ENABLED] = next.flagSecureEnabled
            prefs[Keys.EXCLUDE_FROM_RECENTS] = next.excludeFromRecents
            prefs[Keys.APP_LOCK_ENABLED] = next.appLockEnabled
            prefs[Keys.SUBSCRIPTION_USER_AGENT] = next.subscriptionUserAgent
            prefs[Keys.ADAPTIVE_FALLBACK_ENABLED] = next.adaptiveFallbackEnabled
            prefs[Keys.FAIL_CLOSED_ECH] = next.failClosedEch
            prefs[Keys.STALL_THRESHOLD_SEC] = next.stallThresholdSec
            prefs[Keys.DOMESTIC_BYPASS_ENABLED] = next.domesticBypassEnabled
            prefs[Keys.DOMESTIC_COUNTRY_CODE] = next.domesticCountryCode
            prefs[Keys.BLOCK_ADS_ENABLED] = next.blockAdsEnabled
            prefs[Keys.BLOCK_PORN_ENABLED] = next.blockPornEnabled
            prefs[Keys.SOCIAL_POLICIES_JSON] = encodeSocialPolicies(next.socialPolicies)
            prefs[Keys.COUNTRY_POLICIES_JSON] = encodeCountryPolicies(next.countryPolicies)
            prefs[Keys.GEO_DB_VERSION] = next.geoDbVersion
            if (next.geoDbLastUpdatedAt != null) {
                prefs[Keys.GEO_DB_LAST_UPDATED_AT] = next.geoDbLastUpdatedAt
            } else {
                prefs.remove(Keys.GEO_DB_LAST_UPDATED_AT)
            }
            prefs[Keys.EXPERIMENTAL_MODE_ENABLED] = next.experimentalModeEnabled
            prefs[Keys.DISMISSED_CLEANUP_KEYS] = next.dismissedCleanupKeys
            prefs[Keys.MANUAL_CONFIGS_ENABLED_FOR_CONNECTION] = next.manualConfigsEnabledForConnection
        }
        updatedSnapshot
    }

    private fun parseSocialPolicies(
        rawJson: String?,
        defaults: Map<SocialServiceId, RuleAction>
    ): Map<SocialServiceId, RuleAction> {
        if (rawJson.isNullOrBlank()) return defaults
        return try {
            val obj = JSONObject(rawJson)
            val result = defaults.toMutableMap()
            SocialServiceId.entries.forEach { svc ->
                val rawVal = obj.optString(svc.id, "")
                if (rawVal.isNotBlank()) {
                    result[svc] = RuleAction.parse(rawVal)
                }
            }
            result
        } catch (_: Exception) {
            defaults
        }
    }

    private fun encodeSocialPolicies(map: Map<SocialServiceId, RuleAction>): String {
        val obj = JSONObject()
        map.forEach { (svc, action) -> obj.put(svc.id, action.name) }
        return obj.toString()
    }

    private fun parseCountryPolicies(rawJson: String?): List<CountryPolicyEntry> {
        if (rawJson.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(rawJson)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val code = obj.optString("code", "").trim().uppercase()
                    val action = RuleAction.parse(obj.optString("action", "DIRECT"))
                    if (code.length == 2) add(CountryPolicyEntry(code, action))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun encodeCountryPolicies(list: List<CountryPolicyEntry>): String {
        val arr = JSONArray()
        list.forEach { entry ->
            arr.put(JSONObject().put("code", entry.code).put("action", entry.action.name))
        }
        return arr.toString()
    }
}
