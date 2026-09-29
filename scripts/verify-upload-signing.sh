#!/usr/bin/env bash
#
# Rehearses Play Store signing end to end, against a key generated here and thrown away.
#
# SPEC.md Phase 11 item 5. The signing path this checks cannot be exercised by building a release:
# `release` is deliberately unbuildable in this repository — `:data` fails `compileReleaseKotlin`
# while gradle/certificate-pins.properties and gradle/play-integrity.properties are empty — and the
# real upload key is in a secret store that a pull request from a fork cannot read and that this
# repository does not have at all. Left there, the whole of `app/build.gradle.kts`'s signing block
# would be code no build ever ran, which is the state app/proguard-rules.pro was in for months.
#
# So this generates a keystore on the spot, declares it through `-PuploadSigningIdentity`, and
# drives the real configuration with it. Nothing here compiles anything: `checkUploadSigning` and
# `signingReport` are configuration-time tasks, and `--dry-run` only prints a task graph. That is
# what makes it cheap enough to run on every pull request, and it is why the key material never
# has to be real.
#
# What it establishes, in order:
#
#   1. `checkUploadSigning` is attached to `assembleRelease` and to `bundleRelease`, and not to
#      `assembleDebug`. This is the assertion the whole gate rests on: AGP's answer to a release
#      variant with no signing config is `app-release-unsigned.apk` and exit 0, so a wiring that
#      stopped matching would take the build back to shipping unsigned artifacts silently.
#   2. With no key declared, `checkUploadSigning` fails and says so in terms of the file to edit.
#   3. With the throwaway key declared and its secrets in the environment, it passes, and the
#      release variant really is signed with that key while `debug` and `minified` keep the debug
#      one — `minified` being the variant that must never become the build that goes to Play.
#   4. A declaration that names a different certificate fails, with both fingerprints in the
#      message. This is the check the whole split exists for.
#   5. A wrong key password fails, naming the variable rather than throwing from inside apksigner.
#   6. Neither password appears anywhere in anything Gradle printed.
#
# Usage: scripts/verify-upload-signing.sh [gradlew]
#
# Requires a JDK (for keytool) and, through Gradle, an Android SDK. scripts/verify-upload-signing.test.sh
# drives this same script against stubs and needs neither.

set -uo pipefail

GRADLEW="${1:-./gradlew}"

# Under `build/`, which .gitignore covers: a keystore is the one kind of file that must not be
# capable of being committed by accident, even a throwaway one, because the habit is the risk.
WORK="app/build/upload-signing-rehearsal"
IDENTITY="$WORK/upload-signing.properties"
KEYSTORE="$WORK/throwaway.p12"
ALIAS="rehearsal-upload-key-not-a-real-key"

# Everything Gradle printed across every invocation, kept for the leak check at the end.
TRANSCRIPT="$WORK/transcript.log"

failures=0

die() {
    printf 'verify-upload-signing: %s\n' "$1" >&2
    exit 2
}

pass() {
    printf '  ok    %s\n' "$1"
}

# Records a failure and keeps going, so one run reports every problem instead of stopping at the
# first and hiding the rest behind another CI round trip.
#
# `$*` and not `$1`: several of these messages are long enough to want wrapping across two
# arguments, and a `$1` here silently prints the first half of them.
fail() {
    printf '  FAIL  %s\n' "$*" >&2
    failures=$((failures + 1))
}

dump() {
    printf '  --- %s ---\n' "$1" >&2
    printf '%s\n' "$2" | sed 's/^/  | /' >&2
}

command -v keytool >/dev/null 2>&1 || die "keytool is required but not on PATH"
[ -x "$GRADLEW" ] || die "'$GRADLEW' is missing or not executable"

rm -rf "$WORK"
mkdir -p "$WORK"
: >"$TRANSCRIPT"

# --- a key that is obviously not a real one ------------------------------------------------

# Random, so that the leak check below is a real check: a fixed password would also be absent from
# the log of a build that never read it. Base64 of 24 random bytes has no shell-special characters
# to quote and no chance of colliding with anything Gradle prints on its own.
PASSWORD="$(head -c 24 /dev/urandom | base64 | tr -d '\n=' )"
[ -n "$PASSWORD" ] || die "could not generate a password"

# PKCS #12, which is what keytool has produced by default since JDK 9, and one password for both
# halves because the format has no separate key password — keytool sets the key password to the
# store password and prints a warning if told otherwise. The distinct-passwords case is the one
# exercised as a *failure* in step 5.
#
# `-validity 10000` is what the build requires: Play will not take an upload certificate that
# expires before 2033-10-22, and keytool's default is 90 days.
if ! keytool -genkeypair -keystore "$KEYSTORE" -storetype PKCS12 -alias "$ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Upload Signing Rehearsal (not a real key), O=boilerplate-android-kotlin, C=GB" \
    -storepass "$PASSWORD" -keypass "$PASSWORD" >>"$TRANSCRIPT" 2>&1; then
    dump "keytool" "$(cat "$TRANSCRIPT")"
    die "keytool could not generate the throwaway keystore"
fi

fingerprint_of() {
    keytool -list -v -keystore "$1" -storepass "$PASSWORD" 2>/dev/null |
        sed -n 's/.*SHA256: \(.*\)/\1/p' | head -n 1 | tr -d ':[:space:]' | tr 'a-f' 'A-F'
}

FINGERPRINT="$(fingerprint_of "$KEYSTORE")"
[ -n "$FINGERPRINT" ] || die "could not read the throwaway certificate's SHA-256 from keytool"

write_identity() {
    cat >"$IDENTITY" <<EOF
# Generated by scripts/verify-upload-signing.sh. Not a real key; see that script.
keyAlias = $ALIAS
certificateSha256 = $1
EOF
}

# The three variables app/build.gradle.kts reads, and the only channel the key travels on. In CI
# they come from the secret store; here they come from a key that was generated four lines ago.
UPLOAD_KEYSTORE_BASE64="$(base64 <"$KEYSTORE" | tr -d '\n')"
UPLOAD_KEYSTORE_PASSWORD="$PASSWORD"
UPLOAD_KEY_PASSWORD="$PASSWORD"
export UPLOAD_KEYSTORE_BASE64 UPLOAD_KEYSTORE_PASSWORD UPLOAD_KEY_PASSWORD

printf 'Rehearsing Play upload signing\n'
printf '  keystore:    %s (PKCS #12, thrown away with the build directory)\n' "$KEYSTORE"
printf '  alias:       %s\n' "$ALIAS"
printf '  certificate: %s\n\n' "$FINGERPRINT"

# --- driving Gradle ---------------------------------------------------------------------------

# Every invocation goes through here so that all of them land in the transcript the leak check
# reads, and so that no caller can forget to capture one.
#
# `--console=plain` stops the progress bar rewriting lines the greps below would then not match.
# There is deliberately no `--quiet`: both things being read here — the alias and fingerprint
# `checkUploadSigning` logs, and every line `signingReport` prints — go out at the lifecycle level,
# which is exactly what `--quiet` drops. A run with it on reports nothing and passes nothing.
GRADLE_OUTPUT=""
GRADLE_STATUS=0
run_gradle() {
    GRADLE_OUTPUT="$("$GRADLEW" --console=plain "$@" 2>&1)"
    GRADLE_STATUS=$?
    {
        printf '\n$ %s %s\n' "$GRADLEW" "$*"
        printf '%s\n' "$GRADLE_OUTPUT"
    } >>"$TRANSCRIPT"
}

expect_output() {
    local label="$1" pattern="$2"
    if printf '%s\n' "$GRADLE_OUTPUT" | grep -qE "$pattern"; then
        pass "$label"
    else
        fail "$label"
        dump "gradle output" "$GRADLE_OUTPUT"
    fi
}

# --- 1. the wiring ------------------------------------------------------------------------------

printf 'Wiring\n'
for lifecycle in assembleRelease bundleRelease; do
    run_gradle ":app:$lifecycle" --dry-run
    if [ "$GRADLE_STATUS" -ne 0 ]; then
        fail "$lifecycle could not even be planned"
        dump "gradle output" "$GRADLE_OUTPUT"
    else
        expect_output "$lifecycle depends on checkUploadSigning" '^:app:checkUploadSigning'
    fi
done

run_gradle ":app:assembleDebug" --dry-run
if printf '%s\n' "$GRADLE_OUTPUT" | grep -qE '^:app:checkUploadSigning'; then
    fail "assembleDebug depends on checkUploadSigning — a debug build needs no upload key"
else
    pass "assembleDebug does not depend on checkUploadSigning"
fi

# --- 2. no key declared --------------------------------------------------------------------------

printf '\nWith no key declared\n'
: >"$WORK/empty.properties"
run_gradle ":app:checkUploadSigning" "-PuploadSigningIdentity=$WORK/empty.properties"
if [ "$GRADLE_STATUS" -eq 0 ]; then
    fail "checkUploadSigning passed with no upload key declared"
    dump "gradle output" "$GRADLE_OUTPUT"
else
    pass "checkUploadSigning fails"
    expect_output "and names the file to edit" 'declares no upload key'
    expect_output "and says what would otherwise happen" 'unsigned artifact'
fi

# --- 3. the key declared and present ---------------------------------------------------------

printf '\nWith the throwaway key\n'
write_identity "$FINGERPRINT"
run_gradle ":app:checkUploadSigning" ":app:signingReport" "-PuploadSigningIdentity=$IDENTITY"
if [ "$GRADLE_STATUS" -ne 0 ]; then
    fail "checkUploadSigning rejected a key that matches its own declaration"
    dump "gradle output" "$GRADLE_OUTPUT"
else
    pass "checkUploadSigning passes"
    expect_output "and reports the alias and the fingerprint" "Play upload signing: .*$FINGERPRINT"
fi

# `signingReport` prints one block per variant: `Variant:`, `Config:`, `Store:`, `Alias:`, the three
# digests and `Valid until:`. Reading the SHA-256 out of a named variant's block is the only way to
# see which key a variant would actually be signed with without building it.
variant_field() {
    printf '%s\n' "$GRADLE_OUTPUT" |
        awk -v variant="$1" -v field="$2" '
            $1 == "Variant:" { current = $2; next }
            current == variant && $1 == field ":" { $1 = ""; sub(/^ +/, ""); print; exit }
        '
}

# Every variant has to be in the report. A rule that reads a variant `signingReport` stopped
# printing finds an empty string, and an empty string compares equal to another empty string —
# which is a check that passes by having nothing to look at.
for variant in debug release minified; do
    if [ -z "$(variant_field "$variant" Config)" ]; then
        fail "signingReport printed no signing config for the $variant variant"
        dump "signingReport" "$GRADLE_OUTPUT"
    fi
done

release_config="$(variant_field release Config)"
release_store="$(variant_field release Store)"
release_certificate="$(variant_field release SHA-256 | tr -d ':[:space:]' | tr 'a-f' 'A-F')"

if [ "$release_config" = "release" ] && [ "$release_certificate" = "$FINGERPRINT" ]; then
    pass "the release variant is signed with the declared key"
else
    fail "the release variant reports config '$release_config' and certificate " \
        "'$release_certificate', expected 'release' and '$FINGERPRINT'"
    dump "signingReport" "$GRADLE_OUTPUT"
fi

# The invariant app/build.gradle.kts states in prose next to the `minified` build type: it borrows
# the debug key so that a shrunk APK is installable, and it must never become the build that goes
# to Play. `initWith(release)` copies the release build type wholesale, so the line that overrides
# the signing config back to `debug` is one deletion away from CI publishing a shrunk APK signed
# with the upload key.
#
# Checked by the config each variant names and the keystore behind it, rather than by comparing
# certificates with the debug variant's. That was the first shape of this check and it failed in CI
# for a reason worth recording: `signingReport` prints `Error: Missing keystore` and no digest at all
# for a debug-signed variant on a runner where the debug keystore has not been created yet — and
# this job deliberately builds nothing, so nothing creates it. Reading the config and the store needs
# no keystore to exist, and it is the more direct statement of the invariant anyway. Both halves are
# needed: `Config: debug` alone would still pass if `signingConfigs.debug` were repointed at the
# upload key, and a store comparison alone would pass if a second config shared the debug keystore.
for variant in debug minified; do
    config="$(variant_field "$variant" Config)"
    store="$(variant_field "$variant" Store)"
    if [ "$config" = "debug" ] && [ "$store" != "$release_store" ]; then
        pass "the $variant variant keeps the debug key"
    else
        fail "the $variant variant is signed by config '$config' out of '$store'; expected " \
            "'debug', and not the upload keystore at '$release_store'"
        dump "signingReport" "$GRADLE_OUTPUT"
    fi
done

# --- 4. a declaration naming a different key ---------------------------------------------------

printf '\nWith a declaration that names another key\n'
# One hex digit changed, which is the shape the real mistake has: a secret rotated to a different
# keystore, or a fingerprint copied from the wrong app in Play Console.
if [ "${FINGERPRINT:0:1}" = "A" ]; then
    write_identity "B${FINGERPRINT:1}"
else
    write_identity "A${FINGERPRINT:1}"
fi
run_gradle ":app:checkUploadSigning" "-PuploadSigningIdentity=$IDENTITY"
if [ "$GRADLE_STATUS" -eq 0 ]; then
    fail "checkUploadSigning accepted a keystore that is not the declared key"
    dump "gradle output" "$GRADLE_OUTPUT"
else
    pass "checkUploadSigning fails"
    expect_output "and prints the fingerprint it found" "$FINGERPRINT"
    expect_output "and says what signing with the wrong key costs" 'Play cannot undo'
fi

# --- 5. a wrong key password -------------------------------------------------------------------

printf '\nWith the wrong key password\n'
write_identity "$FINGERPRINT"
# Set and put back around the one invocation rather than prefixed to it: an assignment in front of
# a *function* call persists in the shell after the function returns, which would leave every check
# below running against a password that does not work.
UPLOAD_KEY_PASSWORD="not-$PASSWORD"
run_gradle ":app:checkUploadSigning" "-PuploadSigningIdentity=$IDENTITY"
UPLOAD_KEY_PASSWORD="$PASSWORD"
if [ "$GRADLE_STATUS" -eq 0 ]; then
    fail "checkUploadSigning accepted a key password that does not open the key"
    dump "gradle output" "$GRADLE_OUTPUT"
else
    pass "checkUploadSigning fails"
    expect_output "and names the variable rather than the exception" 'UPLOAD_KEY_PASSWORD'
fi

# --- 6. nothing leaked ---------------------------------------------------------------------

printf '\nSecrets\n'
# The reason this is worth a check of its own: a CI log is readable by anybody who can read the
# repository, it is kept for months, and Gradle prints a stack trace on failure — three of the five
# invocations above are failures on purpose. GitHub masks values it knows are secrets; it knows
# nothing about a password this script generated, so here the masking is not doing the work and the
# absence is the build's own property.
if grep -qF "$PASSWORD" "$TRANSCRIPT"; then
    fail "the keystore password appears in Gradle's output"
    printf '  (the transcript is not printed here, for the obvious reason)\n' >&2
else
    pass "no password appears in anything Gradle printed"
fi

if grep -qF "$UPLOAD_KEYSTORE_BASE64" "$TRANSCRIPT"; then
    fail "the encoded keystore appears in Gradle's output"
else
    pass "the keystore does not appear in anything Gradle printed"
fi

# --- result ----------------------------------------------------------------------------------

printf '\n'
if [ "$failures" -gt 0 ]; then
    printf 'verify-upload-signing: %d check(s) failed\n' "$failures" >&2
    exit 1
fi
printf 'verify-upload-signing: the release signing path is wired, gated and leak-free\n'
