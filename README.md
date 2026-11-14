# LuxyVPN Android

**LuxyVPN** is a native Android VPN & Xray proxy client built with **Kotlin**, **Jetpack Compose**, **Material 3**, **Room**, and **DataStore**.

## Key Capabilities
- **10+ Proxy Protocols & Dynamic Guardrails:** VLESS, VMess, Trojan, Shadowsocks, SOCKS5, Hysteria2, WireGuard / AmneziaWG (`Jc`, `Jmin`, `Jmax`, `S1–S2`, `H1–H4`), MASQUE, DNSTT, and MasterDNS.
- **Zero-Knowledge `.zsx` / `.kage` Config Vault:** PBKDF2-HMAC-SHA256 & X25519-HKDF-AES-256-GCM encryption with authenticated expiry (`isLocked = true`) and automatic endpoint/log/inspector redaction (`<LOCKED-VAULT-REDACTED>`).
- **Dual-Mode Speed Test:** Benchmark **With VPN (Tunnel)** via the local SOCKS5 proxy (`127.0.0.1:10808`) and **Without VPN (Direct)** via `VpnSocketProtector`-protected bypass sockets—even while the VPN remains connected—featuring a 240° Compose `Canvas` speedometer gauge and side-by-side comparison.
- **GeoIP & GeoSite Routing Rules Engine:** Pre-FakeDNS (`198.18.0.0/15` reverse mapping) evaluation supporting `DIRECT`, `PROXY`, and `BLOCK` (`blackhole`) across Domestic Country Bypass presets (`IR`, `CN`, `RU`, `TR`, `AE` + custom ISO), Ads/Trackers (`geosite:category-ads-all`), Adult Content (`geosite:category-porn`), Social Media 3-way policies, Country policies, and custom rules.
- **Adaptive Anti-Censorship Expert Lab:** 5-stage escalation ladder, TLS Fragmentation (`tlshello`/`1-3`), Noise padding, FinalMask, Encrypted Client Hello (ECH + Fail-Closed ECH), Multi-Hop Chaining (`WARP -> Proxy`, `Proxy -> WARP`, `Node -> Node`), CDN Clean IP Scanner, WARP Benchmarker, Multi-Resolver DNS Pool, and Vault-redacted Xray JSON inspector.
- **Multi-Theme Material 3 Engine:** 8 curated color palettes (Obsidian Gold, Catppuccin, Dracula, Nord, Tokyo Night, Solarized, Gruvbox, Material You) with Dark/Light/System modes and OPSEC protections (`FLAG_SECURE`, Exclude from Recents, App Lock).

## Build & Verify
```bash
./gradlew testDebugUnitTest compileDebugKotlin lintDebug
```
