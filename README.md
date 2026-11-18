# LuxyNet Android

A minimal native Android Xray client: **import your configuration, then connect**.

## 3.5.0 — UI redesign and Add Configuration flow

- Redesign Home and Subscriptions with a dark-and-gold visual theme (`#0B0B0D` background, `#161618` cards, `#E2B46B` accent), static dotted globe backdrop, and streamlined Home controls (`LuxyNet` / `Subscriptions` header, power orb, country selector pill, and server count footer).
- Redesign Subscriptions with a compact `+` action button, accurate status pills (`Active` only when servers exist and the latest update succeeded without expiry or quota exhaustion), two-column usage/expiry grid hiding `Time remaining` when expiry is absent, and `Refresh` / `Edit` / `Delete` card actions.
- Add the `Add Configuration` bottom sheet (`Import from clipboard`, `Import from file`, `Add subscription URL`) and inline `Add subscription URL` form with HTTP(S) validation and duplicate-URL deduplication.

## 3.4.0 — Country selection

- Add a location selector pill on Home (`Automatic` by default) and a bottom sheet listing countries derived from imported server remarks (flags, English/Persian country names, safe ISO codes, and city aliases) without inventing locations from hostnames or IPs.
- Persist the selected country across restarts, filter connection candidates to the chosen country, and show a clear unavailable-country notice without silently switching locations when no matching servers exist.
- Confirm before switching locations while connected or connecting, disconnecting and reconnecting cleanly or reporting connection errors honestly.

## 3.3.2 — DNS, routing, failover, and protocol fixes

- Forward non-address DNS records (`HTTPS`/`SVCB`/`TXT`/`SRV`/`MX`/`PTR`) over proxied TCP DNS while returning NXDOMAIN locally for `.local`/`.lan`/`.home.arpa`/`localhost`.
- Prevent local/dotless DNS queries from falling back to public DoH when the system resolver fails, and drop opportunistic Private DNS probes to `10.89.0.2:853`.
- Switch routing `domainStrategy` to `AsIs` (TUN `routeOnly` already preserves destination IPs for `geoip:` rules) and set a 64 KiB per-connection pipe buffer with stats disabled.
- Race verification endpoints concurrently, reuse the TUN descriptor across candidate retries, fix early-cancel foreground service cleanup, prune stale routing asset directories, and pre-warm asset extraction on startup.
- Accept `xtls-rprx-vision-udp443`, `packetEncoding=xudp` in VLESS URIs, and all official XHTTP modes (`auto`, `packet-up`, `stream-up`, `stream-one`).

## 3.3.1 — Iran-specific DNS and direct routing

- Iranian devices resolve IPv4-only because IPv6 transit is repeatedly withdrawn there; other regions stay dual-stack.
- Zarinpal, Varzesh3, Okala, Basalam and Jabama now route DIRECT.

## 3.3.0 — Built-in network defaults

- No new controls or setup. A geographic device time zone determines the DIRECT country; an explicit device locale country is the fallback for unknown/shared zones. Language alone is not location. Ambiguous cases keep public traffic proxied; device settings are hints, not physical geolocation.
- Private/local IPv4 and IPv6 ranges and the inferred country's IP ranges go DIRECT. Country-domain suffixes also go DIRECT, except globally marketed/undelegated suffixes. Iran includes the supplied domestic-service domains. Iranian devices receive IPv4-only DNS answers because Iranian IPv6 transit is intermittently withdrawn. DIRECT traffic is outside VPN protection.
- Intercept ordinary TCP/UDP DNS from the TUN. Local/domestic address lookups use Android's system resolver; other address lookups use cached, tunneled Cloudflare DoH with Google DoH fallback and a 60-second stale allowance. Outside Iran, answers are dual-stack. No parallel duplicate resolver queries. Other DNS record types remain available over proxied TCP DNS.
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
