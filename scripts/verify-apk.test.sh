#!/usr/bin/env bash
#
# Tests for scripts/verify-apk.sh.
#
# The happy path is exercised for real by every CI run, but the failure paths — the
# reason the script exists — are not: a green build proves nothing about whether a
# tampered, unaligned or wrongly signed APK would actually be caught. This drives the
# script against stub build-tools whose output is fixture-controlled, so each check can
# be shown to fail when it should and only when it should.
#
# It needs nothing but bash: no Android SDK, no APK, no network. That is deliberate —
# it means the script's logic stays verifiable in environments where the real toolchain
# cannot run at all.
#
# Usage: scripts/verify-apk.test.sh

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERIFY="$SCRIPT_DIR/verify-apk.sh"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

tests_run=0
tests_failed=0

# --- stub toolchain ------------------------------------------------------------------

# Each stub prints the fixture file for its tool and exits with the fixture's recorded
# status, so a scenario is expressed purely as data.
make_stub() {
    local path="$1" fixture="$2"
    cat >"$path" <<STUB
#!/usr/bin/env bash
cat "\$VERIFY_APK_FIXTURE/$fixture.out" 2>/dev/null
exit "\$(cat "\$VERIFY_APK_FIXTURE/$fixture.exit" 2>/dev/null || echo 0)"
STUB
    chmod +x "$path"
}

BIN="$WORK/bin"
SDK="$WORK/sdk/build-tools/35.0.0"
mkdir -p "$BIN" "$SDK"
make_stub "$SDK/apksigner" apksigner
make_stub "$SDK/zipalign" zipalign
make_stub "$SDK/aapt2" aapt2
make_stub "$BIN/unzip" unzip

# --- baseline fixture: a well-formed, debug-signed, aligned APK -----------------------

BASELINE="$WORK/baseline"
mkdir -p "$BASELINE"
: >"$BASELINE/apk"

cat >"$BASELINE/identity.properties" <<'EOF'
applicationId=com.kojo.boilerplate
versionCode=1
versionName=1.0.0
minSdk=26
targetSdk=35
EOF

cat >"$BASELINE/unzip.out" <<'EOF'
AndroidManifest.xml
classes.dex
classes2.dex
resources.arsc
META-INF/CERT.SF
EOF

cat >"$BASELINE/apksigner.out" <<'EOF'
Verifies
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
Verified using v4 scheme (APK Signature Scheme v4): false
Verified for SourceStamp: false
Number of signers: 1
Signer #1 certificate DN: CN=Android Debug, O=Android, C=US
Signer #1 certificate SHA-256 digest: 0000000000000000000000000000000000000000000000000000000000000000
EOF

cat >"$BASELINE/aapt2.out" <<'EOF'
package: name='com.kojo.boilerplate' versionCode='1' versionName='1.0.0' platformBuildVersionName='15' platformBuildVersionCode='35' compileSdkVersion='35' compileSdkVersionCodename='15'
sdkVersion:'26'
targetSdkVersion:'35'
uses-permission: name='android.permission.CAMERA'
application-label:'Boilerplate'
application: label='Boilerplate' icon='res/mipmap-anydpi-v26/ic_launcher.xml'
application-debuggable
feature-group: label=''
  uses-feature: name='android.hardware.camera' required='false'
launchable-activity: name='com.kojo.boilerplate.MainActivity'  label='' icon=''
EOF

: >"$BASELINE/zipalign.out"

# --- harness -------------------------------------------------------------------------

# Runs verify-apk.sh against a private copy of the baseline fixture, after applying the
# caller's mutation to it, and asserts the exit status and (optionally) that the output
# names the problem.
expect() {
    local name="$1" want_status="$2" want_output="$3" mutate="$4"
    tests_run=$((tests_run + 1))

    local fixture="$WORK/case"
    rm -rf "$fixture"
    cp -r "$BASELINE" "$fixture"
    ( cd "$fixture" && eval "$mutate" )

    local output status
    output="$(
        cd "$fixture" &&
            ANDROID_HOME="$WORK/sdk" ANDROID_SDK_ROOT="" \
                VERIFY_APK_FIXTURE="$fixture" PATH="$BIN:$PATH" \
                bash "$VERIFY" apk identity.properties 2>&1
    )"
    status=$?

    if [ "$status" -ne "$want_status" ]; then
        printf 'FAIL  %s: exit %d, expected %d\n%s\n\n' "$name" "$status" "$want_status" "$output"
        tests_failed=$((tests_failed + 1))
        return
    fi
    if [ -n "$want_output" ] && ! printf '%s\n' "$output" | grep -qF "$want_output"; then
        printf 'FAIL  %s: output did not mention %s\n%s\n\n' "$name" "$want_output" "$output"
        tests_failed=$((tests_failed + 1))
        return
    fi
    printf 'ok    %s\n' "$name"
}

# --- cases ---------------------------------------------------------------------------

expect 'a well-formed debug APK passes' 0 'is installable' 'true'

expect 'a missing APK is a usage error' 2 'APK not found' \
    'rm apk'
expect 'a missing identity file is a usage error' 2 'identity file not found' \
    'rm identity.properties'
expect 'an incomplete identity file is a usage error' 2 "'minSdk' is missing" \
    "sed -i '/^minSdk=/d' identity.properties"
expect 'an unreadable manifest is a usage error' 2 'malformed' \
    'echo 1 >aapt2.exit'

expect 'a wrong package is caught' 1 "expected 'com.kojo.boilerplate'" \
    "sed -i \"s/name='com.kojo.boilerplate'/name='com.example.other'/\" aapt2.out"
expect 'a wrong versionCode is caught' 1 "versionCode is '2'" \
    "sed -i \"s/versionCode='1'/versionCode='2'/\" aapt2.out"
expect 'a wrong versionName is caught' 1 "versionName is '9.9.9'" \
    "sed -i \"s/versionName='1.0.0'/versionName='9.9.9'/\" aapt2.out"
expect 'a wrong minSdk is caught' 1 "minSdk is '21'" \
    "sed -i \"s/sdkVersion:'26'/sdkVersion:'21'/\" aapt2.out"
expect 'a wrong targetSdk is caught' 1 "targetSdk is '34'" \
    "sed -i \"s/targetSdkVersion:'35'/targetSdkVersion:'34'/\" aapt2.out"

expect 'a failed signature check is caught' 1 'apksigner verify failed' \
    'echo 1 >apksigner.exit'
expect 'a v1-only signature is caught' 1 'no v2+ APK signature scheme verified' \
    "sed -i 's/(APK Signature Scheme v[23]): true/(APK Signature Scheme v2): false/' apksigner.out"
expect 'a non-debug signing key is caught' 1 "signer DN is 'CN=Release, O=Example, C=US'" \
    "sed -i 's/^Signer #1 certificate DN: .*/Signer #1 certificate DN: CN=Release, O=Example, C=US/' apksigner.out"
expect 'a missing signer certificate is caught' 1 'no signer certificate DN' \
    "sed -i '/^Signer #1 certificate DN: /d' apksigner.out"

expect 'an unaligned APK is caught' 1 'not 4-byte aligned' \
    'echo 1 >zipalign.exit'

expect 'a missing manifest entry is caught' 1 'AndroidManifest.xml is missing' \
    "sed -i '/^AndroidManifest.xml$/d' unzip.out"
expect 'an APK with no dex is caught' 1 'no classes.dex' \
    "sed -i '/^classes[0-9]*\.dex$/d' unzip.out"
expect 'a missing resources.arsc is caught' 1 'resources.arsc is missing' \
    "sed -i '/^resources.arsc$/d' unzip.out"

expect 'an APK with no launcher activity is caught' 1 'no launchable activity' \
    "sed -i '/^launchable-activity: /d' aapt2.out"
expect 'a non-debuggable APK is caught' 1 'not marked debuggable' \
    "sed -i '/^application-debuggable$/d' aapt2.out"

# Every check runs even after an earlier one fails, so one CI round trip reports the
# whole picture rather than the first problem only.
expect 'multiple failures are all reported' 1 '3 check(s) failed' \
    "sed -i \"s/versionCode='1'/versionCode='2'/\" aapt2.out
     sed -i '/^application-debuggable$/d' aapt2.out
     echo 1 >zipalign.exit"

# --- result --------------------------------------------------------------------------

printf '\n%d test(s), %d failure(s)\n' "$tests_run" "$tests_failed"
[ "$tests_failed" -eq 0 ]
