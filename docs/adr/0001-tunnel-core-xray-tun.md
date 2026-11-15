# ADR 0001 — Tunnel core: Xray with native TUN inbound

- **Status:** Accepted (2026-11-15)
- **Context:** LuxyNET 3.0 had no tunnel engine. `LuxyCoreRuntime` was a local SOCKS5 server that dialled destinations directly; `XrayConfigCompiler` produced JSON that nothing executed; `LuxyVpnService` established a TUN device whose fd was never consumed. Every protocol in the model (VLESS/VMess/Trojan/SS/HY2/TUIC/WG/MASQUE/DNSTT) was declarative only.

## Decision

Ship **Xray-core** through `libv2ray.aar` (`2dust/AndroidLibXrayLite` v26.11.9) and let the core own the TUN device through its `tun` inbound, using `CoreController.startLoop(configJson, tunFd)`.

- Core: Xray-core `v1.260327.1` (MPL-2.0) — permissive for a proprietary client.
- Wrapper: AndroidLibXrayLite (LGPL-3.0), consumed as a prebuilt AAR (dynamic linking, unmodified).
- No separate tun2socks: this build carries `proxy/tun` (`AndroidTun`, `ReadParts`, `Segment`) and accepts the `VpnService` fd directly.
- `geoip.dat` / `geosite.dat` / `geoip-only-cn-private.dat` ship inside the AAR and are copied to app-private storage on first start.

## Alternatives

| Option | Verdict |
|---|---|
| sing-box `libbox` | Rejected: GPL-3.0 copyleft; would force source release of the whole client. Also would obsolete the existing 39 KB Xray config compiler. |
| Xray + external tun2socks (`hev-socks5-tunnel`) | Unnecessary: the AAR already embeds the TUN inbound. One process fewer, one failure mode fewer. |
| Kotlin userspace TCP/IP over TUN | Rejected: months of work, worse performance, no protocol coverage. |
| Keep the simulated runtime | Rejected: not a VPN. |

## Consequences

- `libv2ray.aar` (58 MB) is vendored in `app/libs/`; APK grows ~33 MB per ABI. ABI splits become mandatory before publishing.
- The runtime path is now: `VpnService.Builder.establish()` → `startLoop(config, fd)` → TUN traffic → Xray outbound. The app's own UID is excluded from the VPN (`addDisallowedApplication`), so core dial-out does not loop.
- Config must be **valid Xray JSON**: `tun` inbound, `stats`/`policy` for counters, and no invented keys that change meaning. Unknown keys are ignored by the core, but invented *defaults* are now visible as no-ops and must be pruned.
- License obligations: notify upstream of modifications to Xray (MPL §3.1 does not apply — we do not modify it) and keep the LGPL wrapper dynamically linked and replaceable.
- Tests that exercised the simulated runtime stay valid through an explicit `simulated` runtime injected into `LuxyVpnCore`.
