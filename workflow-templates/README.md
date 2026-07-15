# Workflow Templates

These files are ready-to-use GitHub Actions workflows. Copy them to `.github/workflows/` to activate.

## ci.yml — Lint → Test → Build APK

Runs three jobs in sequence on every push to `main` and every pull request:

| Job | Step | Command |
|-----|------|---------|
| **Lint** | Android lint | `./gradlew lint` |
| **Unit Tests** | JUnit 5 via Gradle | `./gradlew test` |
| **Build APK** | Debug build (requires lint + test green) | `./gradlew assembleDebug` |

### Artifacts uploaded

- `lint-report` — HTML lint results (7-day retention)
- `test-results` — JUnit XML results (7-day retention)
- `test-report` — HTML test report (7-day retention)
- `app-debug` — `app-debug.apk` (14-day retention)

### Setup

```sh
cp workflow-templates/ci.yml .github/workflows/ci.yml
```

No secrets required for the debug build. For a signed release build, add `KEYSTORE_FILE`, `KEY_ALIAS`, `KEY_PASSWORD`, and `STORE_PASSWORD` to your repository secrets and extend the `build` job accordingly.
