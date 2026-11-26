# Release process

The authoritative release gate is **local**: `scripts/release-gate.sh` on the release
workstation. Hosted GitHub Actions is intentionally disabled — the account's Actions
billing has failed since 2026-11-20 (every push/tag run dies before any job starts with
"recent account payments have failed or your spending limit needs to be increased"), so
hosted CI cannot be a gate. The workflow file is kept for reference, disabled both in the
Actions UI (`disabled_manually`) and by a `workflow_dispatch`-only trigger.

## Rules

1. **No `gh release create` without a green local gate on the exact commit being
   released.** The gate runs the full sequence below and fails fast.
2. Credentials (keystore, passwords) and host limits are passed by environment at
   invocation — **never committed**. Required:
   - `JAVA_HOME`, `ANDROID_SDK_ROOT`, `GRADLE_USER_HOME`, `JAVA_TMP` (disk-backed temp dir)
   - `KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_PASSWORD` (release signing; `KEY_ALIAS`
     defaults to `upload`)
3. Tags are cut on the gated commit; a tag is **never moved** after a release references it.

## Running the gate

```sh
git clone <repo> gate-clone && cd gate-clone && git checkout <tag>   # clean clone preferred
export JAVA_HOME=… ANDROID_SDK_ROOT=… GRADLE_USER_HOME=… JAVA_TMP=/var/tmp/luxy-gate-tmp
export KEYSTORE_PATH=… STORE_PASSWORD=… KEY_PASSWORD=…
scripts/release-gate.sh <tag>
```

The script, in order (exit 1 on the first failure):

| Step | Check |
|---|---|
| 1 | `git status --porcelain` empty; the given tag points at HEAD |
| 2 | `:app:testDebugUnitTest` — if a class fails, that class is retried once with `--rerun-tasks`; a second failure, or a later full-suite failure, aborts. Robolectric/Compose `AppNotIdleException` flakes on loaded hosts are the known retry case |
| 3 | `:app:lintDebug` — 0 errors required |
| 4 | `:app:assembleRelease` (R8, ABI splits, signed) |
| 5 | every APK: `aapt2 dump badging` versionCode/versionName must match `app/build.gradle.kts`; `apksigner verify --print-certs` cert SHA-256 must equal `d2bdce80…f03e74` |
| 6 | writes `release-gate-<version>.txt` (commit, tag, test counts, lint summary, APK sizes + SHA-256, cert) in the repo root (gitignored) |

Host limits come from the environment (`GATE_MAX_WORKERS=1`, Gradle JVM
`-Xmx1536m -XX:MaxMetaspaceSize=512m`, `JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=$JAVA_TMP`) —
this box has 4 cores / 8 GB RAM, and unlimited parallelism caused the long suite runtimes
and the 2026-11-25 idling flake.

## After the gate

- Attach the gate-verified APKs to the draft release (`gh release create <tag> --draft`).
- Cross-check the draft asset SHA-256 values against `release-gate-<version>.txt`; a
  mismatch means the draft was built from a different commit and must be reported, not
  replaced silently.
- Physical device checks remain mandatory (`docs/device-test-checklist.md`); the gate
  cannot cover tunnel behaviour, notification prompts, doze alarms or upgrade installs.
