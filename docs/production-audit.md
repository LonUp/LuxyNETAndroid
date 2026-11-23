# Production readiness audit

Paths below are relative to `app/src/main/java/com/example/` unless otherwise noted. The audit used test databases, local fixtures and simulated connections. A successful local release build does not establish live tunnel protection.

## Findings and verification

| Priority | Confirmed defect | Correction |
| --- | --- | --- |
| High | Returning to a previously used network could reuse its cached exit country. Inspecting an unrelated VPN's routes could misattribute DNS or IPv6 posture. | Force fresh exit verification on handoff and match the configured tunnel address before attributing routes. Unknown exit country stays inconclusive after connectivity succeeds. These checks do not certify all device traffic. |
| High | Delayed health, country and refresh results wrote stale records. They could recreate a trashed profile or reverse a user's source or country choice. | Merge probe results against the current row in a transaction. Apply refresh success and failure to the current subscription and report persisted node counts. |
| High | Passphrases could enter saved UI state; fresh restores lost preferences for subscription nodes that had not yet been downloaded. | Keep the picker passphrase in volatile memory. Retain unmatched node preferences through refresh and re-export using the additive Room 8→9 migration. Serialize restore with conflicting operations; keep connected export available. |
| Medium | Notification text disclosed configuration names, URL authorities and private-key fields escaped redaction, and oversized verification responses were read fully before truncation. | Localize notification state without naming the profile, redact those fields, and cap exit response reads at 4 KiB. |
| Medium | Background traffic polling continued with details expanded; source counts rescanned profiles per subscription on traffic updates. | Stop background polling, use elapsed sample time for rates, and group counts once per relevant source change. Host regressions exercise a 1,000-profile import and counts for 1,001 profiles across manual and subscription sources. |
| Medium | Extreme quota and expiry values overflowed. Mixed-direction technical values and enlarged totals could be hard to read. | Bound the arithmetic, isolate technical text in RTL, and adapt layout and touch targets to font scaling without changing navigation. |

The release APKs for arm64-v8a, armeabi-v7a and universal passed `apksigner verify` and `zipalign -c -P 16 4` using the local `my-upload-key.jks` (`CN=LuxyNET Dev`, SHA-256 `d2bdce80...f03e74`), matching the certificate on the published `v3.8.2` GitHub release asset (`luxynet-3.8.2-arm64-v8a.apk`). CI release builds require `KEYSTORE_BASE64` / `STORE_PASSWORD` / `KEY_PASSWORD` secrets for the intended distribution key. Debug lint reported 0 errors and 56 remaining non-security warnings after fixing `ImplicitSamInstance`, `AppBundleLocaleChanges`, and `ModifierParameter` (down from 108 total issues / 107 warnings + 1 info). The instrumentation APK (`app-debug-androidTest.apk`) compiled with 4 Espresso/Compose UI tests covering clipboard import, connect/disconnect, encrypted backup export/restore, and Smart Cleanup Trash; no device or emulator was attached to execute it. Compose screenshots and tunnel/network fixtures are host evidence only.

Final `:app:testDebugUnitTest`: **344 passed across 45 classes, 0 failed, 0 errors, 0 skipped** (including `MigrationAndDeduplicationTest.migration8To9PreservesAllDataAndAddsPendingNodePreferences` upgrading from a populated v8 SQLite database). An earlier host run reported an Espresso idle timeout in the connected location sheet and stopped during system memory pressure. A separate attempt failed to extract Robolectric native libraries on the full `/tmp` filesystem. The complete suite passed with disk-backed temporary storage and bounded, recycled test workers; no assertion was disabled. The timeout was not established as an application defect.

## Changed files

| Area | Files |
| --- | --- |
| Android lifecycle | `MainActivity.kt`, `vpn/LuxyVpnService.kt`, `vpn/TunnelManager.kt` |
| Verification and privacy | `core/ExitCountryVerifier.kt`, `core/TunnelLeakChecker.kt`, `core/SensitiveDataRedactor.kt` |
| Backup | `core/BackupKeyDerivation.kt`, `core/EncryptedConfigVault.kt`, `core/LuxyBackupManager.kt`, `data/repository/BackupRepositoryHelper.kt` |
| Sources, quota and persistence | `core/SubscriptionUserInfo.kt`, `data/import/ProfileImporter.kt`, `data/local/Daos.kt`, `data/local/Entities.kt`, `data/local/LuxyVpnDatabase.kt`, `data/repository/LuxyVpnRepository.kt` |
| UI and localization | `i18n/BidiText.kt`, `ui/BackupRestoreDialogs.kt`, `ui/ConnectionDetailsCard.kt`, `ui/LuxyVpnApp.kt`, `ui/LuxyVpnViewModel.kt`, `ui/ManualConfigurationsOverlay.kt`, `ui/SmartCleanupSheet.kt`, `ui/SubscriptionsPage.kt`, `app/src/main/res/{values,values-fa}/strings.xml` |
| Documentation (repo root) | `README.md`, `docs/production-audit.md` |

Changed regression files under `app/src/test/java/com/example/`:

- Tunnel: `TunnelReliabilityAndReconnectTest.kt`, `TunnelVerificationAndLeakTest.kt`, `vpn/TunnelManagerExitVerificationTest.kt`.
- Data and security: `AutomaticSelectionAndHealthTest.kt`, `BackupAndRestoreTest.kt`, `BackupKeyDerivationTest.kt`, `CountrySelectionViewModelTest.kt`, `ErrorClassifierAndRedactionTest.kt`, `LuxyVpnCoreTests.kt`, `MigrationAndDeduplicationTest.kt`, `MinimalImportTest.kt`, `SubscriptionUserInfoTest.kt`, `ViewModelIntegrationTest.kt`.
- UI and localization: `ConnectionDetailsAndCleanupUiTest.kt`, `MinimalActivitySmokeTest.kt`, `MinimalHomeTest.kt`, `SmartCleanupTrashUiTest.kt`, `SourceAndManualUiTest.kt`, `SubscriptionNavigationTest.kt`, `SubscriptionsPageTest.kt`, `i18n/BidiTextTest.kt`.
- Instrumentation (`app/src/androidTest/`): `AndroidManifest.xml`, `java/com/example/LuxyVpnInstrumentationTest.kt`, `java/com/example/TestBackupDocumentProvider.kt`.

## Release gates that host tests cannot establish

1. On physical devices, establish a tunnel and independently observe DNS, IPv4 and IPv6 traffic before, during and after Wi-Fi/cellular handoffs. Repeat with always-on/lockdown, explicit cancellation and process death. Verify the status shown to the user at each step.
2. Exercise upgrade and encrypted backup restore with a disposable copy of representative data, including Android 7.x. Exercise connected export and system file-picker cancellation. Do not restore over a production profile library.
3. Measure memory, frame time and standby battery on a low-memory handset with more than 1,000 configurations. A successful host import tests persistence, not device performance.
4. Check TalkBack, notification channels, sheet scrolling and enlarged text on a small physical display in both languages. Use the intended distribution certificate before publishing.
