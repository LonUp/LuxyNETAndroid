# Device Test Checklist

Target package: `com.aistudio.luxyvpn.kxmzpq`  
Baseline note: local/LAN and inferred domestic ccTLD traffic route `DIRECT` by design; run leak checks against non-domestic endpoints (`dnsleaktest.com`, `test-ipv6.com`, `ipleak.net`).

## 1. Tunnel, Handoff & Leak Checks
- [ ] **Baseline Wi-Fi connect:** Tap **Connect**, grant VPN permission. Home shows `Connected`; **Connection Details** shows proxy `IP` (not ISP IP) and live `Total` bytes; notification shows `LuxyNet` / `Connected` with no config name.
- [ ] **DNS / IPv4 / IPv6 leak check:** Run `dnsleaktest.com` (Extended), `test-ipv6.com`, and `ipleak.net`. Only DoH/proxy exit resolvers and proxy IPv4/IPv6 appear (zero ISP DNS or ISP IPv6).
- [ ] **Wi-Fi ↔ Cellular handoff:** Switch Wi-Fi off → wait 10s on cellular → switch Wi-Fi back on. **Connection Details** briefly shows `Checking…` then updates exit IP; re-run `dnsleaktest.com` and `test-ipv6.com` with zero leaks.
- [ ] **Country filter & failover:** Select a specific country, drop the active server or toggle Airplane mode 5s. App transitions through `Switching server…` / `Reconnecting…` and recovers (or errors cleanly without staying on a mismatched exit).
- [ ] **Cancel mid-connect:** Tap **Connect** on a slow/unreachable server, then tap **Cancel** during `Connecting…` / `Finding a server…`. State returns to `Not connected`, notification clears, and normal traffic resumes.
- [ ] **Process kill:** Force-stop or swipe-kill while connected. Without Lockdown, tunnel and notification clear cleanly; with Always-on + Lockdown, OS blocks non-VPN traffic until service restarts.
- [ ] **Always-on + Lockdown:** Enable Android **Always-on VPN** and **Block connections without VPN**. Reboot or disconnect: non-VPN traffic fails closed; reconnect and re-verify `dnsleaktest.com` + `test-ipv6.com`.

## 2. Android 7.x (API 24–25) & Encrypted Backup (Disposable Data Only)
- [ ] **Android 7.0/7.1 export:** On API 24–25 with disposable configs, export an encrypted backup using a ≥6-char passphrase (include non-ASCII characters). Export succeeds.
- [ ] **Connected export / blocked restore:** While VPN is `Connected`, **Export encrypted backup** succeeds; **Restore from backup** is blocked (`Disconnect before restoring a backup.`).
- [ ] **Rotation & picker cancel:** Enter passphrase, tap Export, rotate screen, cancel system file picker, reopen Export. Passphrase fields are blank; completing the picker after rotation still finishes export.
- [ ] **Fresh restore + deferred refresh:** Clear app data (or use a second disposable device), restore backup, then refresh the restored subscription. Manual configs, subscriptions, locale, and prior disabled/pinned/manual-country node overrides persist.

## 3. Persian RTL, Font Scaling (≥1.6×) & TalkBack
- [ ] **Layout at ≥1.6× font scale (EN & FA):** Walk Home, **Connection Details**, **Connection Sources**, **Subscriptions**, **Manual Configurations**, and **Smart Cleanup** (**Suggestions** + **Trash**). No clipped buttons, overlapping text, or undersized (<48dp) touch targets.
- [ ] **RTL technical text:** In Persian (`fa`), IPs, `protocol · host:port`, dates, and technical error details render LTR without reversed octets or punctuation.
- [ ] **TalkBack navigation:** Orb, location pill, source switches, expand/collapse rows, and cleanup tabs announce clear labels and states.
- [ ] **Rotation state:** Rotate inside **Smart Cleanup** (Trash tab with items selected) and **Manual Configurations** (multi-select active). Active tab and selections remain intact.

## 4. Scale (1,000+ Configs) & 8h Standby Battery
- [ ] **Low-memory 1,000+ import:** On a ≤3–4 GB RAM phone, import 1,000+ configs. Import completes without OOM; scrolling **Manual Configurations**, filtering, and opening **Connection Sources** stay responsive.
- [ ] **8h standby battery:** Connect VPN, leave **Connection Details** expanded, lock screen for 8h idle. Android Battery usage shows minimal wakeups/CPU; unlocking resumes accurate byte totals.

## 5. 3.8.3 Hardening, Signing & Upgrade Check
- [ ] **POST_NOTIFICATIONS prompt (Android 13+ / API 33+):** On a fresh install (or with notification permission unset), tap **Connect** and grant VPN permission. Verify the runtime `POST_NOTIFICATIONS` prompt appears before connecting. Test both outcomes:
  - **Grant:** Tunnel connects and the `LuxyNet` / `Connected` foreground notification is visible.
  - **Deny:** Tunnel still connects without crashing (notification stays suppressed by OS).
  - **Always-on idle start:** Trigger Always-on VPN while idle and verify the initial foreground notification shows `Connecting` rather than `Disconnected`.
- [ ] **Cleartext `http://` subscription rejection:** Attempt to import (clipboard/URL), add, edit, or refresh a public `http://` subscription URL (e.g. `http://example.com/sub`). Verify it is rejected with `"Subscription URL must use HTTPS"` (EN) / `"لینک اشتراک باید از نوع HTTPS باشد؛ نشانی‌های http ناامن هستند"` (FA). Verify loopback `http://127.0.0.1` / `http://localhost` remains allowed.
- [ ] **Doze + subscription-refresh alarm:** Add an HTTPS subscription with `autoUpdateHours > 0` (e.g. `1` hour). Verify an alarm is scheduled (`adb shell dumpsys alarm | grep com.aistudio.luxyvpn.kxmzpq`), force Doze (`adb shell dumpsys deviceidle force-idle`), trigger/wait past the interval, and verify `SubscriptionRefreshReceiver` refreshes the subscription (`lastUpdatedAt` updates) and re-arms the next alarm. Reboot the device and verify `SubscriptionBootReceiver` re-arms the alarm. Verify `autoUpdateHours <= 0` schedules no alarm.
- [ ] **Upgrade over v3.8.2 with data preserved:** Install the `3.8.3` (`versionCode 30803`) release APK over `v3.8.2` (`versionCode 30802`) without uninstalling; verify cert SHA-256 matches `D2:BD:CE:80:CA:E9:6E:F8:1B:71:54:D8:05:92:8F:27:B4:27:4D:FE:71:CE:B7:C8:1D:E1:16:0E:45:F0:3E:74`, and verify existing manual configs, subscriptions, node preferences, Trash items, and app settings remain intact.
