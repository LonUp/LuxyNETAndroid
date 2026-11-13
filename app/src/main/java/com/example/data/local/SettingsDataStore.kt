package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.model.AntiCensorshipPreset
import com.example.data.model.AppSplitTunnelMode
import com.example.data.model.ChainingMode
import com.example.data.model.ColorPalette
import com.example.data.model.DnsPoolStrategy
import com.example.data.model.RoutingMode
import com.example.data.model.StabilitySettings
import com.example.data.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.luxyVpnDataStore: DataStore<Preferences> by preferencesDataStore(name = "luxyvpn_settings")

class SettingsDataStore(private val context: Context) {

    private object Keys {
        val ACTIVE_PROFILE_ID = stringPreferencesKey("active_profile_id")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val COLOR_PALETTE = stringPreferencesKey("color_palette")
        val ROUTING_MODE = stringPreferencesKey("routing_mode")
        val AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        val SMART_FAILOVER = booleanPreferencesKey("smart_failover")
        val PROBE_INTERVAL_SEC = intPreferencesKey("probe_interval_sec")
        val FAILOVER_THRESHOLD = intPreferencesKey("failover_threshold")
        val MUX_ENABLED = booleanPreferencesKey("mux_enabled")
        val MUX_CONCURRENCY = intPreferencesKey("mux_concurrency")
        val TLS_FRAGMENT = booleanPreferencesKey("tls_fragment")
        val TCP_FAST_OPEN = booleanPreferencesKey("tcp_fast_open")
        val PRIMARY_DNS = stringPreferencesKey("primary_dns")
        val FALLBACK_DNS = stringPreferencesKey("fallback_dns")
        val DOH_ENABLED = booleanPreferencesKey("doh_enabled")
        val MTU = intPreferencesKey("mtu")

        // LuxyVPN Xray Core & Anti-Censorship Keys
        val ANTI_CENSORSHIP_PRESET = stringPreferencesKey("anti_censorship_preset")
        val FRAGMENT_PACKETS = stringPreferencesKey("fragment_packets")
        val FRAGMENT_LENGTH = stringPreferencesKey("fragment_length")
        val FRAGMENT_INTERVAL = stringPreferencesKey("fragment_interval")
        val NOISE_ENABLED = booleanPreferencesKey("noise_enabled")
        val NOISE_PACKET_SIZE = stringPreferencesKey("noise_packet_size")
        val NOISE_DELAY_MS = stringPreferencesKey("noise_delay_ms")
        val UTLS_FINGERPRINT = stringPreferencesKey("utls_fingerprint")
        val FAKEDNS_ENABLED = booleanPreferencesKey("fakedns_enabled")
        val SNIFFING_ENABLED = booleanPreferencesKey("sniffing_enabled")
        val BLOCK_QUIC_UDP = booleanPreferencesKey("block_quic_udp")
        val XHTTP_MODE = stringPreferencesKey("xhttp_mode")
        val XHTTP_PADDING_BYTES = stringPreferencesKey("xhttp_padding_bytes")
        val DOH_SERVER_URL = stringPreferencesKey("doh_server_url")
        val DOMAIN_STRATEGY = stringPreferencesKey("domain_strategy")

        // Advanced Anti-Censorship & Obfuscation Keys
        val FRAGMENT_HOST = stringPreferencesKey("fragment_host")
        val FRAGMENT_MAX_SPLIT = stringPreferencesKey("fragment_max_split")
        val NOISE_TYPE = stringPreferencesKey("noise_type")
        val NOISE_APPLY_TO = stringPreferencesKey("noise_apply_to")
        val CIPHER_SUITES = stringPreferencesKey("cipher_suites")
        val FINAL_MASK_TYPE = stringPreferencesKey("final_mask_type")
        val ECH_ENABLED = booleanPreferencesKey("ech_enabled")
        val ECH_CONFIG_LIST = stringPreferencesKey("ech_config_list")
        val ECH_FORCE_QUERY = stringPreferencesKey("ech_force_query")
        val HAPPY_EYEBALLS_ENABLED = booleanPreferencesKey("happy_eyeballs_enabled")
        val HAPPY_EYEBALLS_DELAY_MS = intPreferencesKey("happy_eyeballs_delay_ms")
        val ALLOW_UNENCRYPTED_VLESS = booleanPreferencesKey("allow_unencrypted_vless")
        val SUBSCRIPTION_TOP_N_LIMIT = intPreferencesKey("subscription_top_n_limit")

        // Phase 2: WireGuard, AmneziaWG, MASQUE, Multi-Hop Chaining & TLS/Socket Extensions
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
        val AWG_H1 = longPreferencesKey("awg_h1")
        val AWG_H2 = longPreferencesKey("awg_h2")
        val AWG_H3 = longPreferencesKey("awg_h3")
        val AWG_H4 = longPreferencesKey("awg_h4")
        val WARP_NOISE_PRESET = stringPreferencesKey("warp_noise_preset")
        val CHAINING_MODE = stringPreferencesKey("chaining_mode")
        val RELAY_NODE_ID = stringPreferencesKey("relay_node_id")
        val MIXED_CASE_SNI = booleanPreferencesKey("mixed_case_sni")
        val TLS_PADDING_ENABLED = booleanPreferencesKey("tls_padding_enabled")
        val REALITY_FRAGMENT_ENABLED = booleanPreferencesKey("reality_fragment_enabled")
        val FINAL_MASK_FAMILY = stringPreferencesKey("final_mask_family")
        val TCP_HANDSHAKE_TIMEOUT_MS = intPreferencesKey("tcp_handshake_timeout_ms")
        val TLS_HANDSHAKE_TIMEOUT_MS = intPreferencesKey("tls_handshake_timeout_ms")

        // Phase 3: Multi-Resolver DNS Pool, UID Split-Tunnel Isolation & OpSec Privacy
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

        // Phase 4: Adaptive Fallback & Fail-Closed ECH
        val ADAPTIVE_FALLBACK_ENABLED = booleanPreferencesKey("adaptive_fallback_enabled")
        val FAIL_CLOSED_ECH = booleanPreferencesKey("fail_closed_ech")
        val STALL_THRESHOLD_SEC = intPreferencesKey("stall_threshold_sec")

        // Phase 5: Beginner UX Preferences
        val AUTO_CONNECT_ON_LAUNCH = booleanPreferencesKey("auto_connect_on_launch")
    }

    val settingsFlow: Flow<StabilitySettings> = context.luxyVpnDataStore.data.map { prefs ->
        val rawFragment = prefs[Keys.TLS_FRAGMENT] ?: true
        val rawTfo = prefs[Keys.TCP_FAST_OPEN] ?: false
        val effectiveTfo = if (rawFragment) false else rawTfo
        StabilitySettings(
            activeProfileId = prefs[Keys.ACTIVE_PROFILE_ID],
            themeMode = ThemeMode.fromString(prefs[Keys.THEME_MODE]),
            colorPalette = ColorPalette.fromString(prefs[Keys.COLOR_PALETTE]),
            routingMode = RoutingMode.fromString(prefs[Keys.ROUTING_MODE]),
            autoConnectOnLaunch = prefs[Keys.AUTO_CONNECT_ON_LAUNCH] ?: false,
            autoReconnect = prefs[Keys.AUTO_RECONNECT] ?: true,
            smartFailover = prefs[Keys.SMART_FAILOVER] ?: true,
            probeIntervalSec = prefs[Keys.PROBE_INTERVAL_SEC] ?: 10,
            failoverFailureThreshold = prefs[Keys.FAILOVER_THRESHOLD] ?: 3,
            muxEnabled = prefs[Keys.MUX_ENABLED] ?: true,
            muxConcurrency = prefs[Keys.MUX_CONCURRENCY] ?: 8,
            tlsFragmentEnabled = rawFragment,
            tcpFastOpen = effectiveTfo,
            primaryDns = prefs[Keys.PRIMARY_DNS] ?: "1.1.1.1",
            fallbackDns = prefs[Keys.FALLBACK_DNS] ?: "9.9.9.9",
            dohEnabled = prefs[Keys.DOH_ENABLED] ?: true,
            mtu = prefs[Keys.MTU] ?: 1420,
            antiCensorshipPreset = AntiCensorshipPreset.fromString(prefs[Keys.ANTI_CENSORSHIP_PRESET]),
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
            fakeDnsEnabled = prefs[Keys.FAKEDNS_ENABLED] ?: true,
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
            subscriptionTopNLimit = prefs[Keys.SUBSCRIPTION_TOP_N_LIMIT] ?: 50,
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
            awgH1 = prefs[Keys.AWG_H1] ?: 1L,
            awgH2 = prefs[Keys.AWG_H2] ?: 2L,
            awgH3 = prefs[Keys.AWG_H3] ?: 3L,
            awgH4 = prefs[Keys.AWG_H4] ?: 4L,
            warpNoisePreset = prefs[Keys.WARP_NOISE_PRESET] ?: "quic",
            chainingMode = ChainingMode.fromString(prefs[Keys.CHAINING_MODE]),
            relayNodeId = prefs[Keys.RELAY_NODE_ID],
            mixedCaseSni = prefs[Keys.MIXED_CASE_SNI] ?: false,
            tlsPaddingEnabled = prefs[Keys.TLS_PADDING_ENABLED] ?: false,
            realityFragmentEnabled = prefs[Keys.REALITY_FRAGMENT_ENABLED] ?: false,
            finalMaskFamily = prefs[Keys.FINAL_MASK_FAMILY] ?: "tcp",
            tcpHandshakeTimeoutMs = prefs[Keys.TCP_HANDSHAKE_TIMEOUT_MS] ?: 4000,
            tlsHandshakeTimeoutMs = prefs[Keys.TLS_HANDSHAKE_TIMEOUT_MS] ?: 4000,
            dnsPoolStrategy = DnsPoolStrategy.fromString(prefs[Keys.DNS_POOL_STRATEGY]),
            dnsIntegrityFilterEnabled = prefs[Keys.DNS_INTEGRITY_FILTER] ?: true,
            dnsDotServer = prefs[Keys.DNS_DOT_SERVER] ?: "1.1.1.1:853",
            dnsTcpEnabled = prefs[Keys.DNS_TCP_ENABLED] ?: true,
            dnsDotEnabled = prefs[Keys.DNS_DOT_ENABLED] ?: true,
            dnsUdpEnabled = prefs[Keys.DNS_UDP_ENABLED] ?: true,
            appSplitTunnelMode = AppSplitTunnelMode.fromString(prefs[Keys.APP_SPLIT_TUNNEL_MODE]),
            splitTunnelPackages = prefs[Keys.SPLIT_TUNNEL_PACKAGES] ?: "",
            strictUidIsolationEnabled = prefs[Keys.STRICT_UID_ISOLATION] ?: true,
            flagSecureEnabled = prefs[Keys.FLAG_SECURE_ENABLED] ?: false,
            excludeFromRecents = prefs[Keys.EXCLUDE_FROM_RECENTS] ?: false,
            appLockEnabled = prefs[Keys.APP_LOCK_ENABLED] ?: false,
            subscriptionUserAgent = prefs[Keys.SUBSCRIPTION_USER_AGENT] ?: "LuxyVPN/3.0",
            adaptiveFallbackEnabled = prefs[Keys.ADAPTIVE_FALLBACK_ENABLED] ?: true,
            failClosedEch = prefs[Keys.FAIL_CLOSED_ECH] ?: false,
            stallThresholdSec = prefs[Keys.STALL_THRESHOLD_SEC] ?: 8
        )
    }

    suspend fun setActiveProfileId(id: String?) {
        context.luxyVpnDataStore.edit { prefs ->
            if (id == null) prefs.remove(Keys.ACTIVE_PROFILE_ID)
            else prefs[Keys.ACTIVE_PROFILE_ID] = id
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.luxyVpnDataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setColorPalette(palette: ColorPalette) {
        context.luxyVpnDataStore.edit { it[Keys.COLOR_PALETTE] = palette.name }
    }

    suspend fun setRoutingMode(mode: RoutingMode) {
        context.luxyVpnDataStore.edit { it[Keys.ROUTING_MODE] = mode.name }
    }

    suspend fun setAutoConnectOnLaunch(enabled: Boolean) {
        context.luxyVpnDataStore.edit { it[Keys.AUTO_CONNECT_ON_LAUNCH] = enabled }
    }

    suspend fun setAutoReconnect(enabled: Boolean) {
        context.luxyVpnDataStore.edit { it[Keys.AUTO_RECONNECT] = enabled }
    }

    suspend fun setSmartFailover(enabled: Boolean) {
        context.luxyVpnDataStore.edit { it[Keys.SMART_FAILOVER] = enabled }
    }

    suspend fun setProbeIntervalSec(seconds: Int) {
        context.luxyVpnDataStore.edit { it[Keys.PROBE_INTERVAL_SEC] = seconds.coerceIn(5, 60) }
    }

    suspend fun setMuxEnabled(enabled: Boolean) {
        context.luxyVpnDataStore.edit {
            it[Keys.MUX_ENABLED] = enabled
            it[Keys.ANTI_CENSORSHIP_PRESET] = AntiCensorshipPreset.CUSTOM.name
        }
    }

    suspend fun setMuxConcurrency(concurrency: Int) {
        context.luxyVpnDataStore.edit {
            it[Keys.MUX_CONCURRENCY] = concurrency.coerceIn(1, 32)
            it[Keys.ANTI_CENSORSHIP_PRESET] = AntiCensorshipPreset.CUSTOM.name
        }
    }

    suspend fun setTlsFragmentEnabled(enabled: Boolean) {
        context.luxyVpnDataStore.edit {
            it[Keys.TLS_FRAGMENT] = enabled
            if (enabled) {
                it[Keys.TCP_FAST_OPEN] = false
            } else {
                it[Keys.REALITY_FRAGMENT_ENABLED] = false
            }
            it[Keys.ANTI_CENSORSHIP_PRESET] = AntiCensorshipPreset.CUSTOM.name
        }
    }

    suspend fun setTcpFastOpen(enabled: Boolean) {
        context.luxyVpnDataStore.edit {
            it[Keys.TCP_FAST_OPEN] = enabled
            if (enabled) {
                it[Keys.TLS_FRAGMENT] = false
                it[Keys.REALITY_FRAGMENT_ENABLED] = false
            }
            it[Keys.ANTI_CENSORSHIP_PRESET] = AntiCensorshipPreset.CUSTOM.name
        }
    }

    suspend fun setDnsConfig(primary: String, fallback: String, doh: Boolean, dohUrl: String = "https://cloudflare-dns.com/dns-query") {
        context.luxyVpnDataStore.edit {
            it[Keys.PRIMARY_DNS] = primary.trim().ifBlank { "1.1.1.1" }
            it[Keys.FALLBACK_DNS] = fallback.trim().ifBlank { "9.9.9.9" }
            it[Keys.DOH_ENABLED] = doh
            if (dohUrl.isNotBlank()) it[Keys.DOH_SERVER_URL] = dohUrl.trim()
        }
    }

    suspend fun setMtu(mtu: Int) {
        context.luxyVpnDataStore.edit { it[Keys.MTU] = mtu.coerceIn(1280, 1500) }
    }

    suspend fun setFragmentParams(
        packets: String,
        length: String,
        interval: String,
        host: String? = null,
        maxSplit: String? = null
    ) {
        context.luxyVpnDataStore.edit {
            it[Keys.FRAGMENT_PACKETS] = packets
            it[Keys.FRAGMENT_LENGTH] = length
            it[Keys.FRAGMENT_INTERVAL] = interval
            if (host != null) it[Keys.FRAGMENT_HOST] = host.trim()
            if (maxSplit != null) it[Keys.FRAGMENT_MAX_SPLIT] = maxSplit.trim()
            it[Keys.ANTI_CENSORSHIP_PRESET] = AntiCensorshipPreset.CUSTOM.name
        }
    }

    suspend fun setNoiseConfig(
        enabled: Boolean,
        packetSize: String = "10-20",
        delayMs: String = "10-20",
        noiseType: String? = null,
        applyTo: String? = null
    ) {
        context.luxyVpnDataStore.edit {
            it[Keys.NOISE_ENABLED] = enabled
            it[Keys.NOISE_PACKET_SIZE] = packetSize
            it[Keys.NOISE_DELAY_MS] = delayMs
            if (noiseType != null) it[Keys.NOISE_TYPE] = noiseType
            if (applyTo != null) it[Keys.NOISE_APPLY_TO] = applyTo
            it[Keys.ANTI_CENSORSHIP_PRESET] = AntiCensorshipPreset.CUSTOM.name
        }
    }

    suspend fun setUtlsFingerprint(fingerprint: String) {
        context.luxyVpnDataStore.edit {
            it[Keys.UTLS_FINGERPRINT] = fingerprint
            it[Keys.ANTI_CENSORSHIP_PRESET] = AntiCensorshipPreset.CUSTOM.name
        }
    }

    suspend fun setFakeDnsEnabled(enabled: Boolean) {
        context.luxyVpnDataStore.edit {
            it[Keys.FAKEDNS_ENABLED] = enabled
            it[Keys.ANTI_CENSORSHIP_PRESET] = AntiCensorshipPreset.CUSTOM.name
        }
    }

    suspend fun setSniffingEnabled(enabled: Boolean) {
        context.luxyVpnDataStore.edit {
            it[Keys.SNIFFING_ENABLED] = enabled
            it[Keys.ANTI_CENSORSHIP_PRESET] = AntiCensorshipPreset.CUSTOM.name
        }
    }

    suspend fun setBlockQuicUdp(enabled: Boolean) {
        context.luxyVpnDataStore.edit {
            it[Keys.BLOCK_QUIC_UDP] = enabled
            it[Keys.ANTI_CENSORSHIP_PRESET] = AntiCensorshipPreset.CUSTOM.name
        }
    }

    suspend fun setXhttpConfig(mode: String, paddingBytes: String) {
        context.luxyVpnDataStore.edit {
            it[Keys.XHTTP_MODE] = mode
            it[Keys.XHTTP_PADDING_BYTES] = paddingBytes
            it[Keys.ANTI_CENSORSHIP_PRESET] = AntiCensorshipPreset.CUSTOM.name
        }
    }

    suspend fun setCipherSuites(cipherSuites: String) {
        context.luxyVpnDataStore.edit {
            it[Keys.CIPHER_SUITES] = cipherSuites.trim()
            it[Keys.ANTI_CENSORSHIP_PRESET] = AntiCensorshipPreset.CUSTOM.name
        }
    }

    suspend fun setFinalMaskType(finalMaskType: String) {
        context.luxyVpnDataStore.edit {
            it[Keys.FINAL_MASK_TYPE] = finalMaskType
            it[Keys.ANTI_CENSORSHIP_PRESET] = AntiCensorshipPreset.CUSTOM.name
        }
    }

    suspend fun setEchConfig(enabled: Boolean, configList: String? = null, forceQuery: String? = null) {
        context.luxyVpnDataStore.edit {
            it[Keys.ECH_ENABLED] = enabled
            if (configList != null) it[Keys.ECH_CONFIG_LIST] = configList.trim()
            if (forceQuery != null) {
                it[Keys.ECH_FORCE_QUERY] = forceQuery
                if (forceQuery.equals("full", ignoreCase = true) && enabled) {
                    it[Keys.FAIL_CLOSED_ECH] = true
                }
            }
            it[Keys.ANTI_CENSORSHIP_PRESET] = AntiCensorshipPreset.CUSTOM.name
        }
    }

    suspend fun setHappyEyeballsEnabled(enabled: Boolean, tryDelayMs: Int = 250) {
        context.luxyVpnDataStore.edit {
            it[Keys.HAPPY_EYEBALLS_ENABLED] = enabled
            it[Keys.HAPPY_EYEBALLS_DELAY_MS] = tryDelayMs.coerceIn(50, 2000)
            it[Keys.ANTI_CENSORSHIP_PRESET] = AntiCensorshipPreset.CUSTOM.name
        }
    }

    suspend fun setAllowUnencryptedVless(enabled: Boolean) {
        context.luxyVpnDataStore.edit {
            it[Keys.ALLOW_UNENCRYPTED_VLESS] = enabled
            it[Keys.ANTI_CENSORSHIP_PRESET] = AntiCensorshipPreset.CUSTOM.name
        }
    }

    suspend fun setSubscriptionTopNLimit(limit: Int) {
        context.luxyVpnDataStore.edit {
            it[Keys.SUBSCRIPTION_TOP_N_LIMIT] = limit.coerceIn(0, 500)
        }
    }

    suspend fun setWarpEndpoint(endpoint: String) {
        context.luxyVpnDataStore.edit {
            it[Keys.WARP_ENDPOINT] = endpoint.trim().ifBlank { "162.159.192.1:2408" }
        }
    }

    suspend fun setWarpConfig(
        endpoint: String,
        privateKey: String,
        publicKey: String,
        reserved: String,
        localAddress: String
    ) {
        context.luxyVpnDataStore.edit {
            it[Keys.WARP_ENDPOINT] = endpoint.trim().ifBlank { "162.159.192.1:2408" }
            it[Keys.WARP_PRIVATE_KEY] = privateKey.trim()
            it[Keys.WARP_PUBLIC_KEY] = publicKey.trim()
            it[Keys.WARP_RESERVED] = reserved.trim().ifBlank { "0,0,0" }
            it[Keys.WARP_LOCAL_ADDRESS] = localAddress.trim().ifBlank { "172.16.0.2/32" }
        }
    }

    suspend fun setAmneziaWgParams(
        jc: Int,
        jmin: Int,
        jmax: Int,
        s1: Int,
        s2: Int,
        h1: Long,
        h2: Long,
        h3: Long,
        h4: Long
    ) {
        val safeJmin = jmin.coerceIn(0, 1280)
        val safeJmax = jmax.coerceIn(safeJmin, 1280)
        context.luxyVpnDataStore.edit {
            it[Keys.AWG_JC] = jc.coerceIn(0, 128)
            it[Keys.AWG_JMIN] = safeJmin
            it[Keys.AWG_JMAX] = safeJmax
            it[Keys.AWG_S1] = s1.coerceIn(0, 1024)
            it[Keys.AWG_S2] = s2.coerceIn(0, 1024)
            it[Keys.AWG_H1] = h1.coerceAtLeast(1L)
            it[Keys.AWG_H2] = h2.coerceAtLeast(1L)
            it[Keys.AWG_H3] = h3.coerceAtLeast(1L)
            it[Keys.AWG_H4] = h4.coerceAtLeast(1L)
        }
    }

    suspend fun setWarpNoisePreset(preset: String) {
        context.luxyVpnDataStore.edit {
            it[Keys.WARP_NOISE_PRESET] = preset.lowercase().ifBlank { "quic" }
        }
    }

    suspend fun setChainingConfig(mode: ChainingMode, relayNodeId: String?) {
        context.luxyVpnDataStore.edit {
            it[Keys.CHAINING_MODE] = mode.name
            if (relayNodeId.isNullOrBlank()) {
                it.remove(Keys.RELAY_NODE_ID)
            } else {
                it[Keys.RELAY_NODE_ID] = relayNodeId
            }
        }
    }

    suspend fun setAdvancedTlsOptions(
        mixedCaseSni: Boolean,
        tlsPaddingEnabled: Boolean,
        realityFragmentEnabled: Boolean
    ) {
        context.luxyVpnDataStore.edit {
            it[Keys.MIXED_CASE_SNI] = mixedCaseSni
            it[Keys.TLS_PADDING_ENABLED] = tlsPaddingEnabled
            it[Keys.REALITY_FRAGMENT_ENABLED] = realityFragmentEnabled
            it[Keys.ANTI_CENSORSHIP_PRESET] = AntiCensorshipPreset.CUSTOM.name
        }
    }

    suspend fun setSocketMaskAndTimeouts(
        finalMaskFamily: String,
        tcpTimeoutMs: Int,
        tlsTimeoutMs: Int
    ) {
        context.luxyVpnDataStore.edit {
            it[Keys.FINAL_MASK_FAMILY] = finalMaskFamily.lowercase().ifBlank { "tcp" }
            it[Keys.TCP_HANDSHAKE_TIMEOUT_MS] = tcpTimeoutMs.coerceIn(1000, 30000)
            it[Keys.TLS_HANDSHAKE_TIMEOUT_MS] = tlsTimeoutMs.coerceIn(1000, 30000)
            it[Keys.ANTI_CENSORSHIP_PRESET] = AntiCensorshipPreset.CUSTOM.name
        }
    }

    suspend fun setDnsPoolConfig(
        strategy: DnsPoolStrategy,
        integrityFilterEnabled: Boolean,
        dotServer: String,
        dotEnabled: Boolean,
        tcpEnabled: Boolean,
        udpEnabled: Boolean
    ) {
        context.luxyVpnDataStore.edit {
            it[Keys.DNS_POOL_STRATEGY] = strategy.name
            it[Keys.DNS_INTEGRITY_FILTER] = integrityFilterEnabled
            it[Keys.DNS_DOT_SERVER] = dotServer.trim().ifBlank { "1.1.1.1:853" }
            it[Keys.DNS_DOT_ENABLED] = dotEnabled
            it[Keys.DNS_TCP_ENABLED] = tcpEnabled
            it[Keys.DNS_UDP_ENABLED] = udpEnabled
        }
    }

    suspend fun setSplitTunnelConfig(
        mode: AppSplitTunnelMode,
        packagesCsv: String,
        strictUidIsolation: Boolean
    ) {
        context.luxyVpnDataStore.edit {
            it[Keys.APP_SPLIT_TUNNEL_MODE] = mode.name
            it[Keys.SPLIT_TUNNEL_PACKAGES] = packagesCsv.trim()
            it[Keys.STRICT_UID_ISOLATION] = strictUidIsolation
        }
    }

    suspend fun setPrivacyOpSecConfig(
        flagSecureEnabled: Boolean,
        excludeFromRecents: Boolean,
        appLockEnabled: Boolean,
        subscriptionUserAgent: String
    ) {
        context.luxyVpnDataStore.edit {
            it[Keys.FLAG_SECURE_ENABLED] = flagSecureEnabled
            it[Keys.EXCLUDE_FROM_RECENTS] = excludeFromRecents
            it[Keys.APP_LOCK_ENABLED] = appLockEnabled
            it[Keys.SUBSCRIPTION_USER_AGENT] = subscriptionUserAgent.trim().ifBlank { "LuxyVPN/3.0" }
        }
    }

    suspend fun setAdaptiveRoutingConfig(
        adaptiveFallbackEnabled: Boolean,
        failClosedEch: Boolean,
        stallThresholdSec: Int
    ) {
        context.luxyVpnDataStore.edit {
            it[Keys.ADAPTIVE_FALLBACK_ENABLED] = adaptiveFallbackEnabled
            it[Keys.FAIL_CLOSED_ECH] = failClosedEch
            it[Keys.STALL_THRESHOLD_SEC] = stallThresholdSec.coerceIn(4, 30)
            if (failClosedEch) {
                it[Keys.ECH_ENABLED] = true
                it[Keys.ECH_FORCE_QUERY] = "full"
            }
        }
    }

    suspend fun applyAntiCensorshipPreset(preset: AntiCensorshipPreset) {
        context.luxyVpnDataStore.edit {
            it[Keys.ANTI_CENSORSHIP_PRESET] = preset.name
            when (preset) {
                AntiCensorshipPreset.BALANCED_STEALTH -> {
                    it[Keys.TLS_FRAGMENT] = true
                    it[Keys.FRAGMENT_PACKETS] = "tlshello"
                    it[Keys.FRAGMENT_LENGTH] = "10-20"
                    it[Keys.FRAGMENT_INTERVAL] = "10-20"
                    it[Keys.FRAGMENT_MAX_SPLIT] = "32-64"
                    it[Keys.NOISE_ENABLED] = false
                    it[Keys.UTLS_FINGERPRINT] = "randomized"
                    it[Keys.FINAL_MASK_TYPE] = "none"
                    it[Keys.CIPHER_SUITES] = ""
                    it[Keys.ECH_ENABLED] = false
                    it[Keys.HAPPY_EYEBALLS_ENABLED] = true
                    it[Keys.FAKEDNS_ENABLED] = true
                    it[Keys.SNIFFING_ENABLED] = true
                    it[Keys.BLOCK_QUIC_UDP] = true
                    it[Keys.MUX_ENABLED] = true
                    it[Keys.MUX_CONCURRENCY] = 8
                    it[Keys.DOH_ENABLED] = true
                    it[Keys.TCP_FAST_OPEN] = false
                }
                AntiCensorshipPreset.STRICT_DPI_BYPASS -> {
                    it[Keys.TLS_FRAGMENT] = true
                    it[Keys.FRAGMENT_PACKETS] = "1-3"
                    it[Keys.FRAGMENT_LENGTH] = "3-10"
                    it[Keys.FRAGMENT_INTERVAL] = "10-20"
                    it[Keys.FRAGMENT_MAX_SPLIT] = "64-128"
                    it[Keys.NOISE_ENABLED] = true
                    it[Keys.NOISE_TYPE] = "rand"
                    it[Keys.NOISE_PACKET_SIZE] = "20-50"
                    it[Keys.NOISE_DELAY_MS] = "10-20"
                    it[Keys.UTLS_FINGERPRINT] = "random"
                    it[Keys.FINAL_MASK_TYPE] = "header-custom"
                    it[Keys.CIPHER_SUITES] = "TLS_AES_128_GCM_SHA256:TLS_CHACHA20_POLY1305_SHA256"
                    it[Keys.ECH_ENABLED] = true
                    it[Keys.ECH_FORCE_QUERY] = "half"
                    it[Keys.HAPPY_EYEBALLS_ENABLED] = true
                    it[Keys.FAKEDNS_ENABLED] = true
                    it[Keys.SNIFFING_ENABLED] = true
                    it[Keys.BLOCK_QUIC_UDP] = true
                    it[Keys.MUX_ENABLED] = true
                    it[Keys.MUX_CONCURRENCY] = 4
                    it[Keys.XHTTP_MODE] = "packet-up"
                    it[Keys.XHTTP_PADDING_BYTES] = "100-1000"
                    it[Keys.DOH_ENABLED] = true
                    it[Keys.TCP_FAST_OPEN] = false
                }
                AntiCensorshipPreset.CDN_FRONTING -> {
                    it[Keys.TLS_FRAGMENT] = true
                    it[Keys.FRAGMENT_PACKETS] = "tlshello"
                    it[Keys.FRAGMENT_LENGTH] = "20-40"
                    it[Keys.FRAGMENT_INTERVAL] = "5-15"
                    it[Keys.NOISE_ENABLED] = false
                    it[Keys.UTLS_FINGERPRINT] = "chrome"
                    it[Keys.FINAL_MASK_TYPE] = "none"
                    it[Keys.CIPHER_SUITES] = "TLS_AES_128_GCM_SHA256:TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256"
                    it[Keys.ECH_ENABLED] = true
                    it[Keys.ECH_FORCE_QUERY] = "half"
                    it[Keys.HAPPY_EYEBALLS_ENABLED] = true
                    it[Keys.FAKEDNS_ENABLED] = true
                    it[Keys.SNIFFING_ENABLED] = true
                    it[Keys.BLOCK_QUIC_UDP] = true
                    it[Keys.MUX_ENABLED] = true
                    it[Keys.MUX_CONCURRENCY] = 8
                    it[Keys.XHTTP_MODE] = "packet-up"
                    it[Keys.XHTTP_PADDING_BYTES] = "200-1200"
                    it[Keys.DOH_ENABLED] = true
                    it[Keys.TCP_FAST_OPEN] = false
                }
                AntiCensorshipPreset.MAX_STEALTH -> {
                    it[Keys.TLS_FRAGMENT] = true
                    it[Keys.FRAGMENT_PACKETS] = "1-3"
                    it[Keys.FRAGMENT_LENGTH] = "5-12"
                    it[Keys.FRAGMENT_INTERVAL] = "8-18"
                    it[Keys.FRAGMENT_MAX_SPLIT] = "64-128"
                    it[Keys.NOISE_ENABLED] = true
                    it[Keys.NOISE_TYPE] = "rand"
                    it[Keys.NOISE_PACKET_SIZE] = "24-64"
                    it[Keys.NOISE_DELAY_MS] = "10-20"
                    it[Keys.NOISE_APPLY_TO] = "ip"
                    it[Keys.UTLS_FINGERPRINT] = "unsafe"
                    it[Keys.FINAL_MASK_TYPE] = "header-custom"
                    it[Keys.CIPHER_SUITES] = "TLS_AES_128_GCM_SHA256:TLS_CHACHA20_POLY1305_SHA256"
                    it[Keys.ECH_ENABLED] = true
                    it[Keys.ECH_FORCE_QUERY] = "half"
                    it[Keys.HAPPY_EYEBALLS_ENABLED] = true
                    it[Keys.HAPPY_EYEBALLS_DELAY_MS] = 200
                    it[Keys.FAKEDNS_ENABLED] = true
                    it[Keys.SNIFFING_ENABLED] = true
                    it[Keys.BLOCK_QUIC_UDP] = true
                    it[Keys.MUX_ENABLED] = true
                    it[Keys.MUX_CONCURRENCY] = 6
                    it[Keys.XHTTP_MODE] = "packet-up"
                    it[Keys.XHTTP_PADDING_BYTES] = "200-1200"
                    it[Keys.DOH_ENABLED] = true
                    it[Keys.TCP_FAST_OPEN] = false
                }
                AntiCensorshipPreset.DIRECT_LOW_LATENCY -> {
                    it[Keys.TLS_FRAGMENT] = false
                    it[Keys.NOISE_ENABLED] = false
                    it[Keys.UTLS_FINGERPRINT] = "chrome"
                    it[Keys.FINAL_MASK_TYPE] = "none"
                    it[Keys.CIPHER_SUITES] = ""
                    it[Keys.ECH_ENABLED] = false
                    it[Keys.HAPPY_EYEBALLS_ENABLED] = true
                    it[Keys.FAKEDNS_ENABLED] = false
                    it[Keys.SNIFFING_ENABLED] = true
                    it[Keys.BLOCK_QUIC_UDP] = false
                    it[Keys.MUX_ENABLED] = false
                    it[Keys.TCP_FAST_OPEN] = true
                }
                AntiCensorshipPreset.CUSTOM -> Unit
            }
        }
    }
}
