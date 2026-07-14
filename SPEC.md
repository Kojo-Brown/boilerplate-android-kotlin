# Spec: boilerplate-android-kotlin

> Spec-driven. Mark `[x]` only after pushing.

## Phase 1 — Foundation
- [x] Kotlin 2.1 + Gradle 8 (KTS) + Android API 26+ min, API 35 target
- [x] Jetpack Compose + Material3 scaffold
- [x] Hilt dependency injection setup (Application + Activity)
- [x] Navigation Compose with typed routes (Kotlin Serialization)
- [x] `build.gradle.kts` with version catalog (`libs.versions.toml`)

## Phase 2 — Architecture
- [x] MVVM + UiState sealed class pattern per screen
- [x] Repository pattern: `UserRepository` interface + `UserRepositoryImpl`
- [x] Kotlin Coroutines + Flow for reactive data
- [x] Room 2.7 database + DAO + Entity pattern

## Phase 3 — Network
- [x] Retrofit 3 + OkHttp 5 with JWT interceptor + token refresh
- [x] Kotlin Serialization (`kotlinx.serialization`) for JSON
- [x] Result<T> wrapper for error handling in repositories

## Phase 4 — Auth & ML
- [x] DataStore Preferences for token persistence
- [x] Google Sign-In + Credential Manager API
- [x] MLKit barcode scanning screen example
- [x] CameraX integration with MLKit text recognition

## Phase 5 — UI Components
- [x] Reusable Compose components: `AppButton`, `AppTextField`, `LoadingIndicator`
- [x] Dark/light theme with `MaterialTheme` tokens
- [x] Adaptive layout (phone + tablet)

## Phase 6 — Testing & DevOps
- [x] JUnit 5 + MockK unit tests for ViewModels
- [x] Compose UI tests with `createComposeRule`
- [ ] GitHub Actions: lint → test → build APK
