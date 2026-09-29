# Play Store signing

The release build is signed with an **upload key** that exists only in the GitHub secret store.
Nothing in this repository can reach it, nothing writes it outside `build/`, and a release cannot
be built at all without it.

This document is the procedure — generating the key, putting it in the secret store, declaring it,
rotating it, and what to do when it is lost. Why the pieces are arranged this way is in the header
of `gradle/upload-signing.properties` and in the signing block at the top of `app/build.gradle.kts`.

## The two keys

Play App Signing splits the key in two, and almost every confusing signing error comes from
conflating them.

| | **Upload key** | **App signing key** |
|---|---|---|
| Signs | what you send to Play | what Play sends to devices |
| Held by | you | Google |
| If lost | Play resets it, over a support request | the app can never be updated again |
| This repository | `UPLOAD_KEYSTORE_BASE64` and the two passwords | not here, and must not be |

Google re-signs every artifact with the app signing key before it reaches a device, so the upload
key's only job is to prove that an upload came from you. That is what makes it safe to hold in a CI
secret store and replaceable when it is not: losing it costs a support request, not the app.

The consequence worth internalising: the certificate a user's device sees is **not** the one in
this repository's declaration. `gradle/upload-signing.properties` names the upload certificate.
Play Console shows both, on the same page, which is the usual source of the mismatch.

## Generating the key

```
keytool -genkeypair \
  -keystore upload-keystore.p12 -storetype PKCS12 \
  -alias upload -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=<your organisation>, O=<your organisation>, C=<your country>"
```

Three of those arguments are load-bearing, and the build rejects a key that gets them wrong:

- **`-validity 10000`.** Play will not accept an upload certificate that expires before
  2033-10-22. keytool's default is 90 days, which produces a key that works for one release and
  then does not.
- **`-keysize 2048`** and RSA. Play's floor; there is no reason to go lower and little to gain
  above it for a key whose whole lifetime is signing uploads.
- **PKCS #12.** It is keytool's own default since JDK 9. Note that the format has **no separate
  key password**: keytool sets the key password to the store password whatever `-keypass` says, so
  `UPLOAD_KEY_PASSWORD` and `UPLOAD_KEYSTORE_PASSWORD` hold the same value for a PKCS #12 keystore
  and different values for a JKS one. The build reads both regardless, and says which one failed.

Keep the keystore itself somewhere a password manager or a cloud KMS can hold it. The secret store
is where CI reads it from, not where it is archived: repository secrets cannot be read back out.

## Putting it in the secret store

Three repository secrets, under **Settings → Secrets and variables → Actions**:

| Secret | Value |
|---|---|
| `UPLOAD_KEYSTORE_BASE64` | `base64 < upload-keystore.p12` — wrapped lines are fine |
| `UPLOAD_KEYSTORE_PASSWORD` | the keystore's password |
| `UPLOAD_KEY_PASSWORD` | the key's password (the same one, for PKCS #12) |

Then declare which key those secrets are supposed to hold, in
`gradle/upload-signing.properties`:

```
keyAlias = upload
certificateSha256 = <the SHA-256 from the command below>
```

```
keytool -list -v -keystore upload-keystore.p12 | grep 'SHA256:'
```

The fingerprint is not a secret — it is in every artifact the key signs — and it is checked in on
purpose. It is the only thing that can tell a correctly signed build from a build correctly signed
with the wrong key, which is the one signing mistake Play cannot undo: an upload it does not
recognise is rejected, and the *first* upload of a new app enrols whatever key arrives with it.

## Releasing

Push a tag. `.github/workflows/release.yml` builds the bundle, checks that the certificate on it is
the declared one, and uploads the `.aab` with its mapping file.

```
git tag v1.0.0 && git push origin v1.0.0
```

A release build needs two other files filled in first — `gradle/certificate-pins.properties` and
`gradle/play-integrity.properties` — and fails in `:data:compileReleaseKotlin` while either is
empty. They are empty in this boilerplate because their values name a real server and a real Play
Console project. Each file's header says what goes in it.

Building one locally is the same three variables:

```
export UPLOAD_KEYSTORE_BASE64="$(base64 < upload-keystore.p12)"
export UPLOAD_KEYSTORE_PASSWORD=... UPLOAD_KEY_PASSWORD=...
./gradlew :app:bundleRelease
```

There is deliberately no other way to supply them. Not `gradle.properties`, not
`local.properties`, not a `keystore.properties` beside the build file, not a `-P` flag — a Gradle
property is visible in the process list of every user on the machine for as long as the build runs.
`UploadSigningContractTest` fails the build if a second route appears.

## What happens when it is not configured

`:app:checkUploadSigning` fails, and `assembleRelease` and `bundleRelease` both depend on it.

That task exists because of what AGP does otherwise: a release variant with no signing config does
not fail the build, it writes `app-release-unsigned.apk` and exits 0. The artifact cannot be
installed and cannot be uploaded, and nothing in the log says so.

The same task is what reports a keystore that does not open, an alias that is not in it, a password
that does not work, a certificate that is not the declared one, the Android debug certificate, and
a certificate that expires before Play's floor — each with a message naming the variable or the
file to fix.

## Rotating the upload key

Play has to be told, or the next upload is rejected: **Play Console → your app → Release → Setup →
App integrity → Upload key certificate → Request upload key reset**, attaching the new
certificate (`keytool -export -rsa -alias upload -keystore upload-keystore.p12 -file upload.pem`).
Google's turnaround is a couple of working days.

Then, in one change: replace the three secrets, and update `certificateSha256` in
`gradle/upload-signing.properties`. The order does not matter much — either half alone makes
`checkUploadSigning` fail with both fingerprints in the message — but both halves are needed, and
that is the point of checking the fingerprint in: a rotation that reached only the secret store is
otherwise a green build signed with a key Play will reject.

Nothing about a rotation reaches users. The app signing key is unchanged, so devices see the same
certificate they always did.

## If the upload key is lost or leaked

Ask Play for an upload key reset, as above. That is the whole recovery, and it is why the split
exists.

A leaked upload key is not a way to publish a fake update — Play still authenticates the account
uploading it — but it is worth resetting anyway: it is a key somebody else can produce artifacts
your pipeline would accept, and there is no cost to replacing it.

If a keystore ever reaches a commit, deleting the file is not the fix. It is in every clone and
every fork from that point on, and in the history after that. Generate a new key and reset the
upload key. `UploadSigningContractTest` fails on any keystore in the tree, and `.gitignore` covers
`*.jks`, `*.keystore`, `*.p12` and `*.pfx`, so this takes effort to do.

## How this is tested

`release` cannot be built in this repository, and the real upload key is not in it, so the signing
path could easily have been code no build ever ran. Two things stop that:

- **`scripts/verify-upload-signing.sh`**, run by the `upload signing` job on every pull request. It
  generates a throwaway key, declares it, and drives the real configuration: that
  `checkUploadSigning` is attached to `assembleRelease` and `bundleRelease` and not to
  `assembleDebug`, that the release variant really would be signed with the declared key, that
  `minified` keeps the debug key, that a mismatched fingerprint and a wrong password both fail, and
  that neither password appears anywhere Gradle printed. It needs no secret.
- **`scripts/verify-upload-signing.test.sh`**, which drives that script against stubs so its
  failure paths are exercised too — a rehearsal that stopped checking would otherwise report green
  about exactly the thing it stopped checking.

`UploadSigningContractTest` covers what neither of those can see: that no keystore is in the tree,
that the build has no second source for a secret, that no workflow interpolates one into a shell
script, and that the three variable names agree across all six files that name them.
