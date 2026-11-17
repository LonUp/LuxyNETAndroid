# ADR 0001 — Official Xray core with native Android TUN

- **Status:** Accepted; minimal-client cutover 2026-11-17.

## Decision

Bundle the **unmodified official [XTLS/Xray-core](https://github.com/XTLS/Xray-core)**, release `v26.11.9`, commit [`676eaf66f8f05d4d558ee7a74b4736623d3feeb9`](https://github.com/XTLS/Xray-core/commit/676eaf66f8f05d4d558ee7a74b4736623d3feeb9). No patterniha or other fork is used.

- JNI wrapper: [2dust/AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite), `v26.11.9`, commit [`b5a377087e74eaa34e86718710b04c50f4b767f5`](https://github.com/2dust/AndroidLibXrayLite/commit/b5a377087e74eaa34e86718710b04c50f4b767f5). Wrapper Go source is unchanged; its module replacement points to the official checkout.
- Artifact: `app/libs/libv2ray.aar`; SHA-256 **`4c057093d5daf39e06c38cf8864e9279328a45cecd1296371d914522935aa63e`**.
- Native targets: Android ARMv7 and ARM64, API 24; Go 1.27.4, NDK `28.2.13676358`, `golang.org/x/mobile` `v0.0.0-20261011034528-197770444867`.
- The core's Android `tun` inbound duplicates the application's TUN descriptor. Stop the native core before closing the application's descriptor.
- Native startup and teardown run on IO workers, never on Android lifecycle/main callbacks. Monotonic attempt IDs guard both compiled-config handoff and native stop; delayed cleanup cannot kill a newer connection. Startup deadlines use a monotonic clock.
- There is no tun2socks process or simulated/direct fallback runtime.

## Minimal runtime contract

`ClientPolicy` independently gates imports, stored-profile selection and compilation. Allowed protocols are VLESS, VMess, Trojan, authenticated-encryption Shadowsocks and SOCKS; supported stream transports are TCP, WebSocket, gRPC, HTTPUpgrade and XHTTP. TLS certificate verification cannot be disabled. VLESS without payload encryption requires TLS or TCP REALITY. Official `mlkem768x25519plus` VLESS encryption (native/xorpub/random, 0rtt/1rtt) is accepted independently of stream TLS, with case-sensitive public keys preserved through compilation. This is a core protocol option, not a user experimental opt-in. HY2, WireGuard/AWG, MASQUE, ECH, final masks and other optional experimental enhancements are rejected, never silently downgraded. Ordinary SOCKS retains the imported cleartext proxy semantics; TLS is used only when configured.

Every settings boundary in the tunnel manager and compiler projects persisted settings through the fixed minimal baseline. Previous experimental opt-ins, relays, custom rules, split tunneling and adaptive stages cannot reactivate capabilities. Stored profiles and database schema remain intact; unsupported profiles are ineligible rather than rewritten.

All IPv4/IPv6 device traffic uses the selected proxy outbound. The app UID is excluded from the VPN to prevent core dial-out loops. A loopback-only SOCKS inbound is reserved for the one-shot HTTPS data-path verification before publishing `CONNECTED`: up to three ranked candidates, two independent HTTPS 204 endpoints, cancellable calls, and a 25-second overall connection deadline. Failed verification produces an honest error or tries the next candidate. There are no recurring network probes, keepalive-ping watchdogs or periodic settings reads.

The minimal interface has no traffic or duration display. Traffic statistics collection, native counter reads and the one-second sampler are disabled and removed. Native shutdown notifications are callback-driven; there is no periodic state or network watchdog.

Global routing needs neither GeoIP nor GeoSite data. The wrapper's `assets` directory is omitted from the AAR, and startup performs no unused database extraction. Full official native core code is retained.

## Rebuild

With Go 1.27.4, a JDK on `PATH`, and `ANDROID_HOME` / `ANDROID_NDK_HOME` set:

```sh
git clone https://github.com/XTLS/Xray-core.git
git -C Xray-core checkout 676eaf66f8f05d4d558ee7a74b4736623d3feeb9
git clone https://github.com/2dust/AndroidLibXrayLite.git
git -C AndroidLibXrayLite checkout b5a377087e74eaa34e86718710b04c50f4b767f5
cd AndroidLibXrayLite
go mod edit -replace=github.com/xtls/xray-core=../Xray-core
mv assets ../unused-geodata
go install golang.org/x/mobile/cmd/gomobile@v0.0.0-20261011034528-197770444867
go install golang.org/x/mobile/cmd/gobind@v0.0.0-20261011034528-197770444867
gomobile init
GOFLAGS=-p=2 gomobile bind -v -target=android/arm,android/arm64 -androidapi 24 \
  -trimpath -ldflags='-s -w -buildid= -checklinkname=0' \
  -o /path/to/project/app/libs/libv2ray.aar ./
```

The recorded checksum identifies the artifact built for this cutover, not a guarantee that ZIP/JDK metadata from every build environment is byte-identical. Kotlin tests and Android packaging are verified separately by the release build; this ADR does not claim a device/server tunnel smoke test.

## Licensing and source availability

- Official Xray core: MPL-2.0; license text in `docs/licenses/XRAY-CORE-MPL-2.0.txt`.
- AndroidLibXrayLite: LGPL-3.0; license text in `docs/licenses/ANDROIDLIBXRAYLITE-LGPL-3.0.txt`, with the incorporated GPL-3.0 text in `docs/licenses/GPL-3.0.txt`.
- Go runtime: BSD-style license in `docs/licenses/GO-BSD.txt`. The pinned upstream module graphs retain their dependency license notices.
- Native code is shipped in replaceable JNI shared libraries inside the AAR; the wrapper and core are linked together within those libraries. The public pinned sources and rebuild instructions above identify the exact corresponding sources and changes (module replacement and omission of unused assets). Preserve upstream notices and provide the materials and replacement/relinking rights required by the LGPL when redistributing. Rebuilding and signing a replacement APK does not require the distributor's private signing key.
