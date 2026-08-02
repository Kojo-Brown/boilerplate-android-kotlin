# boilerplate-android-kotlin

> Kotlin 2.1 · Jetpack Compose · Hilt · Room · Retrofit · MLKit

Modern Android app starter following Google's recommended architecture.

## Stack

| Layer | Tech |
|-------|------|
| Language | Kotlin 2.1 |
| UI | Jetpack Compose + Material3 |
| DI | Hilt |
| Navigation | Navigation Compose (typed routes) |
| DB | Room 2.7 |
| Network | Retrofit 3 + OkHttp 5 |
| Async | Kotlin Coroutines + Flow |
| ML | MLKit (text, barcode) |
| Camera | CameraX |
| Testing | JUnit 5 + MockK + Compose UI Test |

## Quick Start

1. Open in Android Studio Meerkat+
2. Sync Gradle
3. Create `local.properties` with `API_URL=https://your-api.com`
4. Run on emulator (API 26+) or device

## CI

Two workflows run on every push to `main` and every pull request.

### `ci.yml` — gates → APK

| Job | Runs |
|-----|------|
| **compile · lint · detekt · test** | `compileDebugKotlin`, `lintDebug`, `detekt`, `testDebugUnitTest` |
| **Build + verify APK** (needs the gates) | `assembleDebug`, then `scripts/verify-apk.sh` |

All four gates run even when an earlier one fails, so a single run reports
every result rather than stopping at the first.

Artifacts: `gate-reports` (lint HTML, detekt, test reports and JUnit XML,
7-day retention) and `app-debug` (`app-debug.apk`, 14-day retention). The APK
is uploaded even when verification fails, so the artifact is available to
inspect without re-running CI.

### Verifying the APK

`assembleDebug` exiting 0 means the build ran; it does not mean the artifact is
installable. `scripts/verify-apk.sh` checks the properties Android's package
installer would otherwise reject the APK for:

- the archive is readable and carries `AndroidManifest.xml`, at least one
  `classes*.dex` and `resources.arsc`
- uncompressed entries are 4-byte aligned (`zipalign -c 4`)
- `apksigner verify` passes across the app's own API 26–35 range, under an APK
  Signature Scheme v2 or newer, with the Android debug certificate
- the package, `versionCode`, `versionName`, `minSdk` and `targetSdk` match what
  `app/build.gradle.kts` declares
- there is a launchable activity, and the APK is marked debuggable

The expected identity is not a second copy of those values: `./gradlew
:app:writeDebugApkIdentity` writes them out of the build DSL to
`app/build/apk-identity/debug.properties`, which the script reads. Changing
`defaultConfig` moves the expectation with it, and the check keeps proving that
AGP propagated the declared values into the artifact.

Run it locally with:

```bash
./gradlew :app:assembleDebug :app:writeDebugApkIdentity
./scripts/verify-apk.sh
```

`scripts/verify-apk.test.sh` drives the script against stub build-tools to prove
each check fails when it should. It needs only bash — no SDK, no APK, no network
— and runs in CI ahead of the build.

Not covered: the APK is never installed on an emulator, and alignment is checked
at 4 bytes rather than the 16 KB page alignment Android 15+ wants for native
libraries.

The debug build needs no secrets. A signed release build would add
`KEYSTORE_FILE`, `KEY_ALIAS`, `KEY_PASSWORD` and `STORE_PASSWORD` to the
repository secret store and extend the `build` job — none of those belong in
the repository.

### `dependency-resolution.yml` — resolution only

Resolves every version declared in `gradle/libs.versions.toml` without
compiling, so a version that does not exist fails fast and on its own.

## Spec Progress
See [SPEC.md](./SPEC.md).
