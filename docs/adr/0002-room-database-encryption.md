# ADR 0002 — Room database encryption (deferred, pending approval)

- **Status:** Proposed — awaiting operator approval before implementation.

## Context

`LuxyVpnDatabase` (Room, schema v9) persists everything the app knows about a user's
connectivity: subscription URLs (which embed private panel tokens), server profiles
(addresses, UUIDs/pswd), node enable/pin preferences, per-node country/exit-IP
attribution, stability events and the deleted-profile Trash backup rows. On a rooted
or temporarily accessible device, `databases/luxyvpn` is currently plaintext SQLite.

Adjacent secrets already have homes outside Room: the core's `xray.xudp.basekey`
lives in DataStore (`SettingsDataStore`), export backups are AES-GCM encrypted with a
user passphrase (`LuxyBackupManager`, PBKDF2-HmacSHA256), and keystore passwords
have been removed from the build (commit `6457434`). The remaining plaintext store
is Room.

## Options considered

| Option | Mechanism | Pros | Cons |
|---|---|---|---|
| **A. SQLCipher via `net.zetandroid:android-database-sqlcipher` / `com.commonsware:android-database-sqlcipher`** | Drop-in `SupportSQLiteOpenHelper.Factory` wrapping passphrase-keyed AES-256 full-database encryption | Mature, battle-tested, one-line integration point (`SupportFactory`), key stays in Android Keystore | Ships a native `libsqlcipher.so` per ABI (~7–8 MB each, ~15 MB before splits); adds a native dependency of LGPL/Apache provenance that must be vendored like `libv2ray.aar`; database becomes unreadable without the key (debug/forensics friction); schema migrations unchanged but corrupt-file recovery is harder |
| **B. SQLCipher fork `net.zetandroid:database-sqlcipher` latest + Keystore-wrapped passphrase** | Same as A, passphrase randomly generated, wrapped by a Keystore AES key | Key never on disk in plaintext; device-bound | Same binary cost; first-open key provisioning needs a migration path for existing plaintext DBs (recreate + restore from backup, or in-place re-encrypt which SQLCipher does not provide) |
| **C. Field-level encryption (encrypt token/UUID columns with Keystore key)** | Encrypt sensitive columns only | No new native libs; keeps DB inspectable | Easy to miss a column; encrypted columns lose queryability (fingerprint dedupe reads UUIDs — would need hash companions); weaker guarantee: schema, country, exit-IP, subscription URLs' hosts remain plaintext |
| **D. Do nothing; rely on app-private storage** | Android app-private storage is already enforced sandboxing | Zero cost | Root access defeats it; backups via `adb backup`/cloud transfer would carry plaintext (manifest already sets `allowBackup=false`, `dataExtractionRules` restricts D2D) |

## Decision (proposed, not yet applied)

**Option B** — SQLCipher full-database encryption with a Keystore-wrapped random
passphrase, integrated through Room's `SupportSQLiteOpenHelper.Factory` seam:

1. Generate a 32-byte random passphrase on first open, store it wrapped: AES-GCM key
   in Android Keystore (`setUserAuthenticationRequired(false)` — key usable without
   unlock to not break boot-time/auto-connect), wrapped blob in `SettingsDataStore`.
2. Room builder: `.openHelperFactory(SupportFactory(passphrase))`.
3. Existing installs: the migration is one-shot — on first launch after upgrade,
   export nothing (Room data is reproducible: subscriptions re-fetch, manual profiles
   are the only loss) — simplest correct path is **rename plaintext file → create
   encrypted DB → re-run schema → import manual profiles + subscriptions from the
   renamed file**; if that proves fragile in testing, fall back to "fresh DB +
   user-initiated backup/restore prompt".
4. Trash/backup payloads stay inside the encrypted DB; `LuxyBackupManager` remains
   the export path (passphrase-encrypted, shareable).

## Why this needs approval before coding

- **Binary cost:** +~15 MB universal APK before splits (SQLCipher is per-ABI native);
  current release APKs are 42–43 MB per ABI — this is a ~35% size increase. Item 21
  of the parity plan ("app size discipline") pulls the other way.
- **Provenance:** SQLCipher (BSD, Zetetic) + its bundled OpenSSL/BoringSSL — needs
  the same vendoring/license audit discipline as ADR 0001.
- **Migration risk:** profile loss on a botched migration is a real user-facing
  failure mode; the lossless path needs a device test matrix.
- **Alternative rejected for now:** option C was assessed as giving the illusion of
  encryption while the highest-value fields (subscription URLs with panel tokens)
  stay exposed either way — either encrypt them (losing URL dedupe) or don't.

## Consequences if accepted

- New module dependency + vendored AARs; license texts in `docs/licenses/`.
- `MigrationAndDeduplicationTest` and every DAO test must run against an encrypted
  in-memory DB to pin the factory seam.
- Debug builds may opt into plaintext via `buildConfigField` to keep inspection
  workflows, but release always encrypts.
- `docs/production-audit.md` and the device-test checklist gain an
  "database is SQLCipher" verification line.
