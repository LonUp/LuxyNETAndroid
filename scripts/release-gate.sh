#!/usr/bin/env bash
# Authoritative local release gate (hosted GitHub Actions is disabled — billing).
# Fails fast, in order: clean tree & tag → unit tests → lint → release build →
# per-APK metadata/signature verification → report.
#
# Credentials and host limits are passed via the environment, never committed:
#   KEYSTORE_PATH, STORE_PASSWORD, KEY_PASSWORD, KEY_ALIAS  — release signing
#   GATE_JAVA_OPTS   (default: -Xmx1536m -XX:MaxMetaspaceSize=512m)
#   GATE_MAX_WORKERS (default: 1)
#
# Usage: scripts/release-gate.sh [tag]
#   tag optional; if given it must be an annotated/lightweight tag pointing at HEAD.
# Report is written as release-gate-<version>.txt in the repo root (gitignored).

set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

BUILD_TOOLS="${ANDROID_HOME:-$ANDROID_SDK_ROOT}/build-tools/36.0.0"
EXPECTED_CERT="d2bdce80cae96ef81b7154d805928f27b4274dfe71ceb7c81de1160e45f03e74"
JAVA_TMP="${JAVA_TMP:?Set JAVA_TMP to a disk-backed temp dir, e.g. /var/tmp/luxy-gate-tmp}"
mkdir -p "$JAVA_TMP"

GRADLE="${GRADLE:-./gradlew}"
JAVA_HOME="${JAVA_HOME:?Set JAVA_HOME to the JDK used for the build}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT}"
GRADLE_USER_HOME="${GRADLE_USER_HOME:?Set GRADLE_USER_HOME}"
export GRADLE_USER_HOME
export JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$JAVA_TMP"
GRADLE_FLAGS=(--no-daemon --console=plain --offline
  "--max-workers=${GATE_MAX_WORKERS:-1}")
GRADLE_JVM_OPTS="-Xmx1536m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8"
export GRADLE_OPTS="${GRADLE_OPTS:-} -Dorg.gradle.jvmargs=$GRADLE_JVM_OPTS"

step() { printf '\n=== %s ===\n' "$1" >&2; }

die() { printf '\nGATE FAILED: %s\n' "$1" >&2; exit 1; }

# --- 1. tree & tag ----------------------------------------------------------
step "Clean tree and tag"
[ -z "$(git status --porcelain)" ] || die "worktree is dirty: $(git status --porcelain | head -3)"
TAG="${1:-}"
if [ -n "$TAG" ]; then
  [ "$(git rev-parse "$TAG" 2>/dev/null || echo x)" = "$(git rev-parse HEAD)" ] \
    || die "tag $TAG does not point at HEAD ($(git rev-parse --short HEAD))"
fi
COMMIT=$(git rev-parse HEAD)
VERSION=$(sed -n 's/.*providers.gradleProperty("versionName").orNull ?: "\([^"]*\)".*/\1/p' app/build.gradle.kts)
[ -n "$VERSION" ] || die "could not read default versionName from app/build.gradle.kts"
echo "commit $COMMIT  tag ${TAG:-none}  version $VERSION"

# --- 2. unit tests ----------------------------------------------------------
step "Unit tests (:app:testDebugUnitTest)"
if ! $GRADLE "${GRADLE_FLAGS[@]}" :app:testDebugUnitTest; then
  echo "tests failed — one retry of the failing class only"
  FAILING=$(python3 - <<'PY'
import glob, xml.etree.ElementTree as ET
bad = []
for f in glob.glob("app/build/test-results/testDebugUnitTest/TEST-*.xml"):
    r = ET.parse(f).getroot()
    if int(r.get("failures", "0")) or int(r.get("errors", "0")):
        bad.append(r.get("name"))
print(" ".join(sorted(bad)))
PY
)
  [ -n "$FAILING" ] || die "gradle failed without a recorded test failure"
  echo "retrying: $FAILING"
  $GRADLE "${GRADLE_FLAGS[@]}" :app:testDebugUnitTest \
    --tests "$FAILING" --rerun-tasks || die "failing class failed again after one retry"
  # full suite must then be re-run to completion so the report reflects every class
  $GRADLE "${GRADLE_FLAGS[@]}" :app:testDebugUnitTest || die "full suite failed after retry"
fi

python3 - <<'PY' > /tmp/gate-tests.txt
import glob, xml.etree.ElementTree as ET
tests = fails = errors = skipped = 0
classes = 0
for f in glob.glob("app/build/test-results/testDebugUnitTest/TEST-*.xml"):
    r = ET.parse(f).getroot()
    classes += 1
    tests += int(r.get("tests", "0"))
    fails += int(r.get("failures", "0"))
    errors += int(r.get("errors", "0"))
    skipped += int(r.get("skipped", "0"))
print(f"{tests} tests / {classes} classes, {fails} failures, {errors} errors, {skipped} skipped")
PY
grep -q ", 0 failures" /tmp/gate-tests.txt || die "test summary is not green: $(cat /tmp/gate-tests.txt)"
cat /tmp/gate-tests.txt

# --- 3. lint ----------------------------------------------------------------
step "Lint (:app:lintDebug)"
$GRADLE "${GRADLE_FLAGS[@]}" :app:lintDebug || die "lintDebug failed"
LINT=$(grep -oE "[0-9]+ errors?, [0-9]+ warnings?" app/build/reports/lint-results-debug.txt | tail -1)
[ -n "$LINT" ] || die "could not read lint summary"
echo "lint: $LINT"
[ "$(echo "$LINT" | grep -oE '^[0-9]+' | head -1)" = "0" ] || die "lint reported errors: $LINT"

# --- 4. release build -------------------------------------------------------
step "Release build (:app:assembleRelease)"
: "${KEYSTORE_PATH:?Set KEYSTORE_PATH}"; : "${STORE_PASSWORD:?Set STORE_PASSWORD}"
: "${KEY_PASSWORD:?Set KEY_PASSWORD}"; export KEY_ALIAS="${KEY_ALIAS:-upload}"
$GRADLE "${GRADLE_FLAGS[@]}" :app:assembleRelease || die "assembleRelease failed"

# --- 5. per-APK verification -------------------------------------------------
step "APK verification"
REPORT="release-gate-${VERSION}.txt"
{
  echo "release gate — LuxyNet Android $VERSION"
  echo "date: $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  echo "commit: $COMMIT"
  echo "tag: ${TAG:-none}"
  echo "tests: $(cat /tmp/gate-tests.txt)"
  echo "lint: $LINT"
  echo "cert SHA-256: $EXPECTED_CERT"
  echo
} > "$REPORT"

shopt -s nullglob
APKS=(app/build/outputs/apk/release/*.apk)
[ ${#APKS[@]} -gt 0 ] || die "no release APKs produced"
for apk in "${APKS[@]}"; do
  name=$(basename "$apk")
  badging=$("$BUILD_TOOLS/aapt2" dump badging "$apk") || die "aapt2 failed on $name"
  vc=$(echo "$badging" | grep -oE "versionCode='[0-9]+'" | head -1 | grep -oE '[0-9]+')
  vn=$(echo "$badging" | grep -oE "versionName='[^']+'" | head -1 | cut -d"'" -f2)
  [ "$vn" = "$VERSION" ] || die "$name: versionName '$vn' != build.gradle.kts '$VERSION'"
  expected_vc=$(echo "$VERSION" | awk -F. '{print $1*10000 + $2*100 + $3}')
  [ "$vc" = "$expected_vc" ] || die "$name: versionCode $vc != derived $expected_vc"
  cert=$("$BUILD_TOOLS/apksigner" verify --print-certs "$apk" 2>/dev/null \
    | grep "certificate SHA-256" | awk '{print tolower($NF)}') \
    || die "apksigner failed on $name"
  [ "$cert" = "$EXPECTED_CERT" ] || die "$name: cert $cert != expected $EXPECTED_CERT"
  size=$(stat -c %s "$apk")
  sha=$(sha256sum "$apk" | awk '{print $1}')
  echo "$name  size=$size  versionCode=$vc  versionName=$vn  sha256=$sha  cert=$cert" >> "$REPORT"
  echo "$name: versionCode=$vc versionName=$vn size=$size"
  echo "  sha256=$sha"
  echo "  cert ok"
done

echo
echo "GATE PASSED — report: $REPORT"
