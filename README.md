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
| **Build APK** (needs the gates) | `assembleDebug` |

All four gates run even when an earlier one fails, so a single run reports
every result rather than stopping at the first.

Artifacts: `gate-reports` (lint HTML, detekt, test reports and JUnit XML,
7-day retention) and `app-debug` (`app-debug.apk`, 14-day retention).

The debug build needs no secrets. A signed release build would add
`KEYSTORE_FILE`, `KEY_ALIAS`, `KEY_PASSWORD` and `STORE_PASSWORD` to the
repository secret store and extend the `build` job — none of those belong in
the repository.

### `dependency-resolution.yml` — resolution only

Resolves every version declared in `gradle/libs.versions.toml` without
compiling, so a version that does not exist fails fast and on its own.

## Spec Progress
See [SPEC.md](./SPEC.md).
