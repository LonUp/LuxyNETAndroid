# LuxyVPN Android

**LuxyVPN** is a native Android VPN & Xray proxy client built with **Kotlin**, **Jetpack Compose**, **Material 3**, **Room**, and **DataStore**.

## Key Capabilities
- **Xray-compiled outbounds:** VLESS, VMess, Trojan, Shadowsocks, SOCKS, Hysteria2, WireGuard/AmneziaWG, and MASQUE. Other protocol URIs are not imported or offered; legacy unsupported profiles remain blocked.
- **VLESS Encryption:** Experimental Mode accepts the full Xray ML-KEM format. `security=none` is allowed with a valid VLESS Encryption value; otherwise it remains explicitly opt-in.
- **Fastest server:** concurrently measures TCP handshake latency for eligible Xray stream nodes, refreshes their measured RTT, then connects the lowest result. Datagram-only profiles are skipped because they need protocol-specific probes.
- **Zero-Knowledge `.zsx` / `.kage` Config Vault:** PBKDF2-HMAC-SHA256 & X25519-HKDF-AES-256-GCM encryption with authenticated expiry (`isLocked = true`) and automatic endpoint/log/inspector redaction (`<LOCKED-VAULT-REDACTED>`).
- **Dual-Mode Speed Test:** Benchmark **With VPN (Tunnel)** via the local SOCKS5 proxy (`127.0.0.1:10808`) and **Without VPN (Direct)** via `VpnSocketProtector`-protected bypass sockets—even while the VPN remains connected—featuring a 240° Compose `Canvas` speedometer gauge and side-by-side comparison.
- **GeoIP & GeoSite Routing Rules Engine:** Pre-FakeDNS (`198.18.0.0/15` reverse mapping) evaluation supporting `DIRECT`, `PROXY`, and `BLOCK` (`blackhole`) across Domestic Country Bypass presets (`IR`, `CN`, `RU`, `TR`, `AE` + custom ISO), Ads/Trackers (`geosite:category-ads-all`), Adult Content (`geosite:category-porn`), Social Media 3-way policies, Country policies, and custom rules.
- **Adaptive Anti-Censorship Expert Lab:** 5-stage escalation ladder, TLS Fragmentation (`tlshello`/`1-3`), Noise padding, FinalMask, Encrypted Client Hello (ECH + Fail-Closed ECH), Multi-Hop Chaining (`WARP -> Proxy`, `Proxy -> WARP`, `Node -> Node`), CDN Clean IP Scanner, WARP Benchmarker, Multi-Resolver DNS Pool, and Vault-redacted Xray JSON inspector.
- **Multi-Theme Material 3 Engine:** 8 curated color palettes (Obsidian Gold, Catppuccin, Dracula, Nord, Tokyo Night, Solarized, Gruvbox, Material You) with Dark/Light/System modes and OPSEC protections (`FLAG_SECURE`, Exclude from Recents, App Lock).

## Build & Verify
```bash
./gradlew testDebugUnitTest compileDebugKotlin lintDebug
```
