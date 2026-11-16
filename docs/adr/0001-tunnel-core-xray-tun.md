# ADR 0001 — Tunnel core: Xray with native TUN inbound

- **Status:** Accepted (2026-11-15)
- **Context:** LuxyNET 3.0 had no tunnel engine. `LuxyCoreRuntime` was a local SOCKS5 server that dialled destinations directly; `XrayConfigCompiler` produced JSON that nothing executed; `LuxyVpnService` established a TUN device whose fd was never consumed. Every protocol in the model (VLESS/VMess/Trojan/SS/HY2/TUIC/WG/MASQUE/DNSTT) was declarative only.

## Decision

Ship **Xray-core** through `app/libs/libv2ray.aar`, using the `2dust/AndroidLibXrayLite` v26.11.9 JNI wrapper and letting the core own the TUN device through its `tun` inbound (`CoreController.startLoop(configJson, tunFd)`).

- Core source: [`patterniha/Xray-core`](https://github.com/patterniha/Xray-core), pinned at commit `aaf81a1cb2e20eb89e44de0cec473fc9170d2166` (MPL-2.0).
- Wrapper: `2dust/AndroidLibXrayLite` v26.11.9 (LGPL-3.0); wrapper code is unchanged and dynamically linked. Its Xray module is replaced with the pinned fork during the AAR build.
- Rebuild with Go 1.27.4, Android NDK 28.2.13676358, and `gomobile`; build targets are `android/arm` and `android/arm64`.
- AAR SHA-256: `ce62be590a77f2ff325b54cf7fcd4d99093ad47fb574609bf980496e046c3cb8`.

Rebuild the vendored AAR from the pinned sources (with `ANDROID_HOME` and `ANDROID_NDK_HOME` set):

```sh
git clone --depth 1 --branch v26.11.9 https://github.com/2dust/AndroidLibXrayLite.git
git clone --depth 1 https://github.com/patterniha/Xray-core.git
git -C Xray-core checkout aaf81a1cb2e20eb89e44de0cec473fc9170d2166
cd AndroidLibXrayLite
go mod edit -replace=github.com/xtls/xray-core=../Xray-core
go get -tool golang.org/x/mobile/cmd/gobind
go install golang.org/x/mobile/cmd/gomobile@v0.0.0-20261011034528-197770444867
go install golang.org/x/mobile/cmd/gobind@v0.0.0-20261011034528-197770444867
gomobile init
GOFLAGS=-p=2 gomobile bind -v -target=android/arm,android/arm64 -androidapi 24 -trimpath -ldflags='-s -w -buildid= -checklinkname=0' -o /path/to/project/app/libs/libv2ray.aar ./
```

- No separate tun2socks: this build carries `proxy/tun` (`AndroidTun`, `ReadParts`, `Segment`) and accepts the `VpnService` fd directly.
- `geoip.dat` and `geosite.dat` ship inside the AAR and are copied to app-private storage on first start.

## Alternatives

| Option | Verdict |
|---|---|
| sing-box `libbox` | Rejected: GPL-3.0 copyleft; would force source release of the whole client. Also would obsolete the existing 39 KB Xray config compiler. |
| Xray + external tun2socks (`hev-socks5-tunnel`) | Unnecessary: the AAR already embeds the TUN inbound. One process fewer, one failure mode fewer. |
| Kotlin userspace TCP/IP over TUN | Rejected: months of work, worse performance, no protocol coverage. |
| Keep the simulated runtime | Rejected: not a VPN. |

## Consequences

- `libv2ray.aar` (32 MB compressed, arm/arm64 only) is vendored in `app/libs/`; ABI splits remain enabled to avoid shipping both native cores to every device.
- The runtime path is now: `VpnService.Builder.establish()` → `startLoop(config, fd)` → TUN traffic → Xray outbound. The app's own UID is excluded from the VPN (`addDisallowedApplication`), so core dial-out does not loop.
- Config must be **valid Xray JSON**: `tun` inbound, `stats`/`policy` for counters, and no invented keys that change meaning. Unknown keys are ignored by the core, but invented *defaults* are now visible as no-ops and must be pruned.
- License obligations: notify upstream of modifications to Xray (MPL §3.1 does not apply — we do not modify it) and keep the LGPL wrapper dynamically linked and replaceable.
- Tests that exercised the simulated runtime stay valid through an explicit `simulated` runtime injected into `LuxyVpnCore`.
