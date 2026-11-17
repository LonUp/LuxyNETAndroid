# LuxyNet Android

A minimal native Android Xray client: **import your configuration, then connect**.

## 3.3.0 — Built-in network defaults

- No new controls or setup. A geographic device time zone determines the DIRECT country; an explicit device locale country is the fallback for unknown/shared zones. Language alone is not location. Ambiguous cases keep public traffic proxied; device settings are hints, not physical geolocation.
- Private/local IPv4 and IPv6 ranges and the inferred country's IP ranges go DIRECT. Country-domain suffixes also go DIRECT, except globally marketed/undelegated suffixes. Iran includes the supplied domestic-service domains. DIRECT traffic is outside VPN protection.
- Intercept ordinary TCP/UDP DNS from the TUN. Local/domestic address lookups use Android's system resolver; other address lookups use cached, tunneled Cloudflare DoH with Google DoH fallback, dual-stack answers and a 60-second stale allowance. No parallel duplicate resolver queries. Other DNS record types remain available over proxied TCP DNS.
- Block the packaged advertising-domain category, returning DNS NXDOMAIN, plus unspecified/multicast/reserved destinations unsuitable for Internet forwarding. No blanket QUIC/UDP-443 or BitTorrent block. HTTP/TLS sniffing is routing-only, without destination rewriting.
- Tunnel verification always uses the proxy, even when a DIRECT rule would otherwise match. Unmatched traffic defaults to the selected proxy. Old user routing rules and experimental preferences remain inactive.
- Country-only IPv4/IPv6 geodata and a small ads-only domain set are bundled, versioned and extracted once; no recurring downloads or policy polling. The country database adds about 4.6 MB compressed / 23.9 MB installed. Sources, licenses, checksums and the reproducible generator are in `tools/geodata/` and packaged asset notices. Data is a pinned snapshot, updated with the app—not a real-time threat feed.

## 3.2.0 — Subscription management

- Home keeps clipboard/file import and automatic connection. Its Subscriptions link opens a separate management page; Android Back returns Home.
- View saved subscriptions, server counts, provider-reported used/remaining/total traffic, expiry, remaining time and last successful refresh. Missing quota or expiry is shown as not reported, never invented.
- Edit names and HTTP(S) links, refresh on demand, or confirm deletion of a subscription and its servers. Mutations require disconnection; browsing does not interrupt the VPN. Failed refreshes preserve working servers and show their cached status.

## 3.1.1 — Encrypted VLESS import correction

- Recognize official Xray VLESS `mlkem768x25519plus` encryption, including subscriptions with `security=none`. Stream TLS and VLESS payload encryption are separate layers.
- Preserve the imported encryption string and public-key case through JSON/URI import and native configuration compilation. No experimental setting is required; unencrypted VLESS without TLS/REALITY remains rejected.

## 3.1.0 — Minimal client

- One screen with clipboard import, an optional configuration-file picker, and connect/cancel/disconnect. No server editor, manual ping requirement, tools, statistics, subscription dashboard, themes, or experimental switches.
- Import HTTP(S) subscriptions, Base64 link lists, stable proxy links, and Xray JSON objects/arrays. Subscription imports finish fetching and saving before connection becomes available. Failed refreshes preserve working profiles; input is bounded to 8 MiB.
- Connect automatically: compare TCP response times with four workers and a four-second overall selection window. Completed responses rank first; unmeasured servers remain fallbacks. A single server skips comparison.
- Verify an actual HTTPS response through the local Xray SOCKS inbound before reporting connected. Try up to three ranked candidates within a 25-second connection deadline. TCP latency alone is not proof of authentication, throughput, or the globally fastest server.
- No continuous animations, traffic-statistics sampling, recurring network probes, or background diagnostic scans. This removes unnecessary application work; battery/RAM/performance claims still require device measurements.
- Persian and English resources, localized displayed digits, readable RTL layouts, system-bar insets, and large-font scrolling.

Android requires VPN permission on first connection. Importing a copied subscription and tapping Connect otherwise needs two actions.

## Supported configuration

The bundled core is **official XTLS/Xray-core v26.11.9**, not a fork. See [core provenance, exact pins, licenses and rebuild instructions](docs/adr/0001-tunnel-core-xray-tun.md).

The minimal client accepts VLESS with TLS, TCP REALITY or official ML-KEM payload encryption, VMess, authenticated-encryption Shadowsocks, Trojan TLS, and SOCKS. Supported stream transports: TCP, WebSocket, gRPC, HTTPUpgrade and XHTTP. Ordinary SOCKS is not encrypted unless its imported configuration specifies TLS.

Only representable Xray outbounds are imported. Android owns the TUN, local listeners, DNS and built-in routing; imported app-managed directives are not executed and are reported. Unsupported outbound options are rejected rather than silently approximated. Clash/sing-box configurations, HY2, WireGuard/AWG, MASQUE, ECH and other optional experimental extensions are unavailable. Previous opt-ins cannot re-enable them. Existing unsupported stored profiles are preserved but cannot connect.

## Build and verify

Requires JDK 17 and Android SDK 36:

```sh
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleRelease
```

APK output: `app/build/outputs/apk/release/` (ARM64, ARMv7 and universal variants).

Set `LUXY_SCREENSHOTS_DIR` when running `MinimalHomeTest` and `MinimalActivitySmokeTest` to capture native Robolectric renders, including the real clipboard-to-Room import path. These checks do not replace physical-device VPN authentication, cancellation/revocation, network-handoff or battery testing.
