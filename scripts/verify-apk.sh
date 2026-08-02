#!/usr/bin/env bash
#
# Verifies that an assembled debug APK is actually installable, rather than merely
# that `assembleDebug` exited 0.
#
# SPEC.md Phase 0 item 4. `assembleDebug` has been running in CI since PR #20, but a
# zero exit code is a weaker claim than an installable artifact: it says the build
# ran, not that what landed in app-debug.apk is a well-formed, signed, aligned package
# carrying the identity the build declared. Everything checked here is something the
# platform's package installer itself would reject the APK for.
#
# Usage: scripts/verify-apk.sh [apk] [identity-properties]
#
# `identity-properties` is written by `./gradlew :app:writeDebugApkIdentity` out of the
# build DSL, so the expected package/version/sdk values come from build.gradle.kts and
# cannot drift away from it.
#
# Requires an Android SDK with build-tools installed (apksigner, zipalign, aapt2).
# AGP installs the build-tools matching compileSdk as part of assembleDebug, so in CI
# this is satisfied by the build step that runs immediately before it.

set -euo pipefail

APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
IDENTITY="${2:-app/build/apk-identity/debug.properties}"

# The certificate Android's debug keystore has issued since the SDK shipped one. A debug
# APK signed with anything else means a signing config was pointed somewhere unexpected —
# worth failing on, because a real signing key must never be reachable from a debug build.
readonly DEBUG_CERT_DN="CN=Android Debug, O=Android, C=US"

failures=0

die() {
    printf 'verify-apk: %s\n' "$1" >&2
    exit 2
}

pass() {
    printf '  ok    %s\n' "$1"
}

# Records a failure and keeps going, so one run reports every problem with the APK
# instead of stopping at the first and hiding the rest behind another CI round trip.
fail() {
    printf '  FAIL  %s\n' "$1" >&2
    failures=$((failures + 1))
}

# Echoes the tool output a failed check was reading. The build-tools these three
# commands come from is whatever the machine has installed, and their output format does
# move between versions — build-tools 37 renamed a badging line this script parses. When
# a parse comes back empty the raw text is the only thing that says why, and printing it
# here turns that into a one-run diagnosis instead of a blind fix and another CI round.
dump() {
    printf '  --- %s ---\n' "$1" >&2
    printf '%s\n' "$2" | sed 's/^/  | /' >&2
}

check_equals() {
    local label="$1" expected="$2" actual="$3"
    if [ "$expected" = "$actual" ]; then
        pass "$label is '$actual'"
    else
        fail "$label is '$actual', expected '$expected'"
    fi
}

[ -f "$APK" ] || die "APK not found at '$APK' — run ./gradlew :app:assembleDebug first"
[ -f "$IDENTITY" ] ||
    die "identity file not found at '$IDENTITY' — run ./gradlew :app:writeDebugApkIdentity first"

# --- locate the SDK build-tools ------------------------------------------------------

SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -n "$SDK_ROOT" ] || die "neither ANDROID_HOME nor ANDROID_SDK_ROOT is set"
[ -d "$SDK_ROOT/build-tools" ] || die "no build-tools installed under '$SDK_ROOT'"

# Highest installed version: these three tools are backward compatible, and pinning a
# version here would break the moment the runner image or AGP moved to another one.
BUILD_TOOLS="$(find "$SDK_ROOT/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
[ -n "$BUILD_TOOLS" ] || die "no build-tools versions found under '$SDK_ROOT/build-tools'"

APKSIGNER="$BUILD_TOOLS/apksigner"
ZIPALIGN="$BUILD_TOOLS/zipalign"
AAPT2="$BUILD_TOOLS/aapt2"
for tool in "$APKSIGNER" "$ZIPALIGN" "$AAPT2"; do
    [ -x "$tool" ] || die "$tool is missing or not executable"
done
command -v unzip >/dev/null 2>&1 || die "unzip is required but not on PATH"

# --- expected identity ---------------------------------------------------------------

expected() {
    local value
    value="$(sed -n "s/^$1=//p" "$IDENTITY" | head -n 1)"
    [ -n "$value" ] || die "'$1' is missing from $IDENTITY"
    printf '%s' "$value"
}

EXPECTED_APPLICATION_ID="$(expected applicationId)"
EXPECTED_VERSION_CODE="$(expected versionCode)"
EXPECTED_VERSION_NAME="$(expected versionName)"
EXPECTED_MIN_SDK="$(expected minSdk)"
EXPECTED_TARGET_SDK="$(expected targetSdk)"

printf 'Verifying %s\n' "$APK"
printf '  build-tools: %s\n' "$(basename "$BUILD_TOOLS")"
printf '  size: %s bytes\n\n' "$(wc -c <"$APK" | tr -d ' ')"

# --- archive structure ---------------------------------------------------------------

printf 'Archive\n'
entries="$(unzip -Z1 "$APK" 2>/dev/null)" || fail "APK is not a readable zip archive"

if printf '%s\n' "$entries" | grep -qx 'AndroidManifest.xml'; then
    pass "AndroidManifest.xml present"
else
    fail "AndroidManifest.xml is missing from the archive"
fi

# An APK with no dex installs and then does nothing. Compose + Hilt + Room push this
# app past one dex file, so match the whole classesN.dex family rather than just the first.
dex_count="$(printf '%s\n' "$entries" | grep -cE '^classes[0-9]*\.dex$' || true)"
if [ "$dex_count" -gt 0 ]; then
    pass "$dex_count dex file(s) present"
else
    fail "no classes.dex in the archive"
fi

if printf '%s\n' "$entries" | grep -qx 'resources.arsc'; then
    pass "resources.arsc present"
else
    fail "resources.arsc is missing from the archive"
fi

# --- alignment -----------------------------------------------------------------------

printf '\nAlignment\n'
# Uncompressed entries have to sit on 4-byte boundaries or the runtime cannot mmap them
# and the installer refuses the package.
if "$ZIPALIGN" -c 4 "$APK" >/dev/null 2>&1; then
    pass "zipalign -c 4 (all uncompressed entries 4-byte aligned)"
else
    fail "APK is not 4-byte aligned — zipalign -c 4 rejected it"
fi

# --- signature -----------------------------------------------------------------------

printf '\nSignature\n'
# Verified across the app's own supported API range, which is the range the APK actually
# has to install on. apksigner picks the signature schemes each API level requires, so
# this catches a signature that is valid at one end of the range and not the other.
if signer_output="$("$APKSIGNER" verify --verbose --print-certs \
    --min-sdk-version "$EXPECTED_MIN_SDK" --max-sdk-version "$EXPECTED_TARGET_SDK" \
    "$APK" 2>&1)"; then
    pass "apksigner verify (API $EXPECTED_MIN_SDK–$EXPECTED_TARGET_SDK)"
else
    fail "apksigner verify failed for API $EXPECTED_MIN_SDK–$EXPECTED_TARGET_SDK"
    printf '%s\n' "$signer_output" >&2
fi

# minSdk is 26, so AGP signs with v2/v3 and leaves v1 (JAR signing) off; requiring a
# specific scheme by name would break on the next AGP that changes that default, so this
# only asserts that at least one of the modern block-based schemes verified.
if printf '%s\n' "$signer_output" | grep -qE '^Verified using v[234](\.[0-9]+)? scheme.*: true'; then
    pass "signed with an APK Signature Scheme v2 or newer"
else
    fail "no v2+ APK signature scheme verified"
    printf '%s\n' "$signer_output" | grep -E '^Verified using' >&2 || true
fi

# apksigner labels the certificate line differently across versions: build-tools 37 prints
# `V2 Signer: certificate DN:`, older ones `Signer #1 certificate DN:`, and a key carrying
# a rotation lineage `Signer (minSdkVersion=..., maxSdkVersion=...) certificate DN:`. Only
# `certificate DN: ` is common to all three, and no other line in the output carries that
# text. Every DN printed is checked rather than just the first, so an unexpected second
# signer cannot hide behind a correct one.
signer_dns="$(printf '%s\n' "$signer_output" | sed -n 's/.*certificate DN: //p')"

# The same certificate prints as `CN=Android Debug, O=Android, C=US` on some versions and
# `C=US, O=Android, CN=Android Debug` on others — RDN order is a formatting choice, not
# part of the identity — so compare the components as a set. This is enough for the fixed
# debug DN; a DN with an escaped comma inside a component would need real parsing.
normalize_dn() {
    printf '%s' "$1" | tr ',' '\n' |
        sed 's/^[[:space:]]*//; s/[[:space:]]*$//' | sort | paste -sd ',' -
}

if [ -z "$signer_dns" ]; then
    fail "apksigner printed no signer certificate DN"
    dump "apksigner verify --print-certs output" "$signer_output"
else
    expected_dn="$(normalize_dn "$DEBUG_CERT_DN")"
    unexpected_dn=""
    while IFS= read -r dn; do
        [ -n "$dn" ] || continue
        if [ "$(normalize_dn "$dn")" != "$expected_dn" ]; then
            unexpected_dn="$dn"
            break
        fi
    done <<EOF
$signer_dns
EOF
    if [ -n "$unexpected_dn" ]; then
        fail "signer DN is '$unexpected_dn', expected '$DEBUG_CERT_DN'"
    else
        pass "signed with the Android debug key"
    fi
fi

# --- identity ------------------------------------------------------------------------

printf '\nIdentity\n'
badging="$("$AAPT2" dump badging "$APK" 2>/dev/null)" ||
    die "aapt2 could not read the APK's manifest — the package is malformed"

package_line="$(printf '%s\n' "$badging" | grep -m 1 '^package: ')"
badging_field() {
    printf '%s\n' "$package_line" | sed -n "s/.*[[:space:]]$1='\([^']*\)'.*/\1/p" | head -n 1
}
sdk_field() {
    printf '%s\n' "$badging" | sed -n "s/^$1:'\([^']*\)'.*/\1/p" | head -n 1
}

# build-tools 37's aapt2 emits the minSdk line as `minSdkVersion:'26'`; earlier ones call
# it `sdkVersion:'26'`. Accept either rather than pinning a build-tools version here, so
# the check reports on the APK and not on which SDK the machine happens to have.
actual_min_sdk="$(sdk_field sdkVersion)"
[ -n "$actual_min_sdk" ] || actual_min_sdk="$(sdk_field minSdkVersion)"

check_equals "package" "$EXPECTED_APPLICATION_ID" "$(badging_field name)"
check_equals "versionCode" "$EXPECTED_VERSION_CODE" "$(badging_field versionCode)"
check_equals "versionName" "$EXPECTED_VERSION_NAME" "$(badging_field versionName)"
check_equals "minSdk" "$EXPECTED_MIN_SDK" "$actual_min_sdk"
check_equals "targetSdk" "$EXPECTED_TARGET_SDK" "$(sdk_field targetSdkVersion)"

# An empty field is a parse failure, not a wrong APK, and the two want different fixes.
# Show the lines the parse was looking at so the next run says which it was.
if [ -z "$(badging_field name)" ] || [ -z "$actual_min_sdk" ] ||
    [ -z "$(sdk_field targetSdkVersion)" ]; then
    dump "aapt2 badging (package and sdk lines)" \
        "$(printf '%s\n' "$badging" | grep -iE '^package:|sdkversion' || true)"
fi

# Without a LAUNCHER activity the APK installs but cannot be started from the launcher,
# which for this boilerplate is indistinguishable from a broken build.
launchable="$(printf '%s\n' "$badging" |
    sed -n "s/^launchable-activity: name='\([^']*\)'.*/\1/p" | head -n 1)"
if [ -n "$launchable" ]; then
    pass "launchable activity is '$launchable'"
else
    fail "no launchable activity — the APK would install with no way to start it"
fi

# A debug APK that is not debuggable means the debug build type was not applied.
if printf '%s\n' "$badging" | grep -qx 'application-debuggable'; then
    pass "application-debuggable"
else
    fail "APK is not marked debuggable, so it was not built as the debug variant"
fi

# --- result --------------------------------------------------------------------------

printf '\n'
if [ "$failures" -gt 0 ]; then
    printf 'verify-apk: %d check(s) failed\n' "$failures" >&2
    exit 1
fi
printf 'verify-apk: %s is installable\n' "$(basename "$APK")"
