# boilerplate-android-kotlin — Agent Instructions

## What this repo is
Production-grade Kotlin + Jetpack Compose Android boilerplate. Spec-driven and PR-driven: one `SPEC.md` item per run.

## Your job (scheduled agent, every 4h)
1. `git checkout main && git pull --ff-only origin main`
2. Read `SPEC.md`, take the **first** `- [ ]` item. Phase 0 items always win.
3. `git checkout -b <type>/<kebab-slug>` (`feat`/`fix`/`chore`/`ci`/`docs`)
4. Implement it completely — source, types, tests, docs.
5. Run every gate locally; **all must pass** before pushing:
   ```
   ./gradlew --version
   ./gradlew checkModuleDependencies
   ./gradlew compileDebugKotlin
   ./gradlew lintDebug detekt
   ./gradlew testDebugUnitTest
   ./gradlew verifyPaparazziDebug
   ./gradlew assembleDebug
   ```
   None of these take a project path. The first five exist in every module, so one invocation
   covers all fifteen; `verifyPaparazziDebug` exists only in `:core:ui`, which is the one module
   Paparazzi is applied to, and an unqualified invocation finds it there.
   `checkModuleDependencies` is first because it is seconds of work and fails on a layering
   violation every other gate would compile happily. `verifyPaparazziDebug` is the screenshot
   diff gate and blocks a merge like the rest — it re-runs `:core:ui`'s unit tests in verify
   mode and holds every golden image under `core/ui/src/test/snapshots` to the current
   rendering. It cannot run in the scheduled agent's environment at all: layoutlib comes from
   Google Maven, which answers 403 there. Read `docs/screenshot-testing.md` before touching a
   golden image, and never re-record one to turn a red build green without reading the delta
   first.
6. Commit, `git push -u origin <branch>`, then `gh pr create`.
7. `gh pr checks --watch` → **merge only if every check is green**:
   `gh pr merge --squash --delete-branch`
8. Pull main, mark the item `- [x]` in `SPEC.md`, update
   `../PROGRESS.md`, push as a `chore:` commit.

If a check fails, fix forward on the same branch. Never merge red. Never
weaken a test or lower a threshold to force green — if a gate is genuinely
wrong, change it deliberately and say why in the PR.

## Secrets
Never commit real credentials, tokens, keys, or `.env` files. Placeholders in
`.env.example` only; CI reads from the GitHub secret store. Test fixtures must
look obviously fake. Scan `git diff --cached` before every push.

## Modules
The app is fifteen Gradle modules — `:app`, `:data`, five `:feature:*` and eight `:core:*`.
`:benchmark` is a sixteenth Gradle project and not one of them: it applies `com.android.test`,
ships in nothing, and is the only project allowed to depend on `:app`. The unqualified gates above
cover it like any other; its device half runs only in CI. See `docs/baseline-profiles.md`.
What may depend on what is declared in the root `build.gradle.kts` and enforced by
`checkModuleDependencies`; `docs/modularisation.md` is the map. Three rules break builds:
- A `:feature:*` module may never depend on another `:feature:*`. Take a composable slot and
  let `:app` fill it.
- Nothing but `:app` may depend on `:data`.
- `:core:domain` has no Android dependency and no Compose plugin. That is what keeps
  `DomainLayerContractTest` free of exemptions.

Shared build configuration is in `build-logic/convention`. A module's own build file should be
its `namespace` and its dependencies; anything else probably belongs in a convention plugin.

## Conventions
- MVVM with a single `UiState` per screen; no state in composables
- Hilt for DI; constructor injection only
- Coroutines + Flow; inject dispatchers so tests can substitute
- Compose: hoist state, annotate models `@Immutable`/`@Stable`
- Tokens in EncryptedSharedPreferences or Keystore, never plaintext

See `../ROUTINE.md` for the full workflow.
