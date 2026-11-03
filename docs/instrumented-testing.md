# Instrumented tests on an emulator matrix

What `src/androidTest` is for in this repository, which API levels it runs on and why, and the
handful of rules that decide whether a Compose assertion can pass at all.

## What was here before the matrix

Eight test classes across five modules, written over several months, and **none of them had ever
been compiled**. `testDebugUnitTest` does not see `src/androidTest`; nothing in CI assembled a
test APK; no device existed. The only gate that touched those files was detekt, which reads them
as source and never type-checks them.

The defects that had accumulated behind that are worth listing, because every one of them passed
review and none of them could have passed a run:

| What | Where | Why it could not pass |
|---|---|---|
| an import for `assertDoesNotExist` | `HomeScreenTest` | it is a *member* of `SemanticsNodeInteraction`, not a top-level extension, so the import does not resolve and the module does not compile |
| the monogram's initial asserted as readable text | `ProfileScreenTest` | `UserMonogram` is `clearAndSetSemantics {}` — the letter is not in the semantics tree at all |
| one node asserted for a string rendered twice | `GoogleSignInScreenTest` | `onNodeWithText` fails on two matches as loudly as on none |
| an icon's label queried with `onNodeWithText` | `HomeScreenTest`, ×3 | a `contentDescription` is never matched by a text query, so the assertion could not have found the icon either way — and two of the three were asserting *absence*, so they passed while testing nothing |

The first two rows are the ordinary cost of code that was never run. The last two are the
interesting ones, because they are the specific way an instrumented Compose test goes wrong: it
queries the semantics tree, the semantics tree is not the composable tree, and an assertion
pointed at a node that cannot exist is indistinguishable from a passing test.

## What turning it on broke

Two things, both in the build rather than in a test, and both invisible until an androidTest APK
was actually assembled.

**`compileDebugAndroidTestKotlin` failed on an import.** `HomeScreenTest` imported
`assertDoesNotExist`, which is a *member* of `SemanticsNodeInteraction` rather than a top-level
extension, so the import does not resolve. Six seconds, one error, before any emulator — which is
the argument for that step being a gate of its own.

**`:core:testing` could not package its androidTest APK**, and it has no `src/androidTest` at
all. AGP builds an androidTest APK for every library variant whether or not there is anything in
it, and `:core:testing` is the test-support module: its helpers *are* JUnit 5 extensions, so
JUnit 5 sits on its `api` configuration rather than its `testImplementation` one. Its androidTest
runtime classpath is therefore the whole JUnit 5 stack and nothing of its own — and
`junit-jupiter-api` and `junit-platform-commons` both carry `META-INF/LICENSE.md`, which AGP
treats as a conflict and fails on rather than resolving.

`BoilerplateBuild.DUPLICATE_PACKAGING_RESOURCES` is the fix: the four licence and notice files
that two jars on one APK classpath both ship, excluded in both convention plugins. AGP's own
defaults already drop the non-Markdown variants of two of them, so this keeps the existing
behaviour; attribution in this repository is the `supply-chain` job's `artifacts.json`, which
records all 221 dependencies rather than whichever files survived a merge.

Both failed in the `Build the test APKs` step, which runs *before* the emulator starts
deliberately: a build failure should read as a build failure rather than as an emulator step that
timed out, and three legs' worth of boot time is not spent discovering it.

## The matrix

`.github/workflows/ci.yml`, job `instrumented`. Three legs, `fail-fast: false`, each an
`x86_64` AOSP (`default`) emulator on a `pixel_6` profile:

- **API 26** — `BoilerplateBuild.MIN_SDK`. A `minSdk` is a promise; an untested one is a guess.
- **API 30** — one sample between the ends.
- **API 35** — `BoilerplateBuild.TARGET_SDK` and `COMPILE_SDK`.

Both ends run because Compose is the compatibility layer here, and what it cannot paper over is
platform behaviour that moved underneath it. One middle leg is the most the comparison is worth
paying for.

`default` rather than `google_apis`: nothing in these suites touches a Play service, the AOSP
image is the smaller download, and it is the target the `startup-benchmark` job already runs on
this runner. If a level stops publishing an AOSP `x86_64` image, `google_apis` is the substitute
for that leg and nothing else changes.

The image and its first boot are cached per leg (`actions/cache`, keyed on API level, target and
architecture), so only the first run on a key pays for them. The test run passes
`-no-snapshot-save`, so a wedged emulator cannot write itself back over the cached snapshot.

## Running it

In CI it is `scripts/run-instrumented-tests.sh`, which is one Gradle invocation:

```
./gradlew connectedDebugAndroidTest --stacktrace
```

No project path, like every gate in `CLAUDE.md`: the task exists in all fifteen modules, five of
which have `src/androidTest` today, and the other ten report `NO-SOURCE`. A module that takes up
instrumented testing later is picked up without an edit to the workflow.

`:benchmark` is the sixteenth project and is **not** in that set. It disables its own
`connectedDebugAndroidTest` in its build file — a macrobenchmark measures another process
starting, against the `benchmark` and `nonMinifiedBenchmark` variants on a pinned API level with
a budget, and running it against a debuggable `debug` build on three emulators would be wrong
three times over. `benchmark/build.gradle.kts` has the argument next to the line that does it.

**Locally** you need an Android SDK, an emulator and a Docker-free hour; `docs/baseline-profiles.md`
has the same caveat. The agent that maintains this repository has none of them — `dl.google.com`
answers 403 on CONNECT from its network, which is both the SDK download and, through the
`maven.google.com` redirect, Google's Maven repository — so for it CI is the only place any of
this executes. That is the same conclusion Phase 0 items 1 and 2 reached about every other gate
here, and future runs should read the PR checks rather than a local result.

The fast half *is* gated earlier and does not need a device:
`compileDebugAndroidTestKotlin` is a step of the `gates` job, so a test source set that does not
compile fails once, next to the compile gate, instead of three times behind three emulators.

## JUnit 4 on a device, JUnit 5 on the JVM

`src/test` runs on the JUnit Platform — `configureUnitTests()` calls `useJUnitPlatform()`.
`src/androidTest` is **JUnit 4**: `AndroidJUnitRunner` is a JUnit 4 runner, the JUnit 5 engine
has no Android runtime to start under it, and bridging the two needs a third-party Gradle plugin
and a second instrumentation. So an instrumented test is `org.junit.Test`, `org.junit.Assert.*`
and `@get:Rule`, and that is a property of where the code runs rather than a migration that
stalled.

`sharedAndroidTestDependencies()` in `build-logic` is what supplies that stack to every module:
JUnit 4, `androidx.test:core` for a real `Context`, `androidx.test.ext:junit`,
`androidx.test:runner` and `kotlinx-coroutines-test`.

`androidx.test:runner` is the one worth knowing about. `BoilerplateBuild.TEST_RUNNER` has named
`androidx.test.runner.AndroidJUnitRunner` since the module split, and until this item nothing put
that class on any classpath — AGP writes the name into the test APK's manifest without resolving
it, so every gate stayed green and the failure would have arrived as a `ClassNotFoundException`
out of `am instrument`. Declaring the runner is what makes `testInstrumentationRunner` a
statement the build can keep.

## Writing an assertion that can fail

Four rules, each of which a test in this repository got wrong before it had ever run.

**A query runs against the semantics tree, not the composable tree.** A composable that calls
`clearAndSetSemantics {}` is not there. `UserMonogram` does exactly that, deliberately — it is a
decoration beside a `Text` that already carries the name in full, so without it a screen reader
announces the initial and then the name. No query finds its letter, and a test that looks for it
is asserting against a design decision rather than against the code.

**`onNodeWithText` never matches a `contentDescription`, and vice versa.** An `Icon`'s label is a
content description. `RefreshAction` and `SearchBar` put every affordance they have in one, so
`onNodeWithContentDescription` is the only query that reaches them.

**A negative assertion under the wrong query cannot fail.**
`onNodeWithText("Clear search").assertDoesNotExist()` passes whether the icon is there or not,
because the query was never going to match it. Three tests here were that shape, two of them
named for the opposite claim. When writing `assertDoesNotExist`, check that the *positive* form
of the same query finds the thing in the state where it is supposed to be present — the pair is
what makes either half meaningful.

**`onNodeWithText` is also a uniqueness assertion.** It fails on two matches with "Expected
exactly '1' node but found '2'". That is a feature: `SignedInContent` renders
`displayName.ifEmpty { email }` above an unconditional email line, so a user with no display name
has the same string on screen twice, and the test for that case has to say so —
`onAllNodesWithText(…).assertCountEquals(2)`, paired with the one-node case above it. Reaching
for `onAllNodes…` to silence a failure you did not expect is how a count regression becomes
invisible.

**Half of the assertion API is a member and half is an extension, and the import line is where
that bites.** `assertIsDisplayed`, `assertIsEnabled`, `assertIsNotEnabled`, `assertHasClickAction`
and `assertCountEquals` are top-level extensions in `androidx.compose.ui.test` and need importing.
`assertExists` and `assertDoesNotExist` are **members of `SemanticsNodeInteraction`** and must
not be: an import for one does not resolve, which is a compile error on the import line rather
than at the call. That was the first thing `compileDebugAndroidTestKotlin` ever reported, and the
reason it is a gate of its own — `HomeScreenTest` had carried that import since it was written.

A fifth rule is a consequence of the first: **a Material button, card or toggle merges its
descendants into one semantics node.** The merged node carries the children's text and content
descriptions, which is why `onNodeWithText("Submit").assertIsNotEnabled()` works on a button. A
**test tag is not a property that merges**, so a tag on something inside a button is in neither
the merged node nor the merged tree. `AppButton`'s spinner is exactly that, and its assertion
needs `useUnmergedTree = true`.

## What is still not covered

- **Paging's load-state branches.** `HomeScreenTest` builds its `LazyPagingItems` from
  `PagingData.from`, which produces a fully-loaded generation, so the spinner, the append footer
  and the error content are never rendered. Driving them means constructing `LoadStates` by hand,
  which is what `androidx.paging:paging-testing` is for, and it is not on the classpath.
  `docs/paging.md` has the assertions that would be worth making.
- **Anything needing a Hilt graph.** Every suite here composes a component directly and passes
  its state in. Replacing the network and the database with fake bindings is the next SPEC item,
  and it needs a custom `AndroidJUnitRunner` and `HiltTestApplication` before a test can launch a
  screen that injects a view model.
- **`:app`.** It has no `src/androidTest`, so nothing yet navigates between two screens on a
  device. The shared-element transitions in `docs/shared-elements.md` are the obvious subject and
  need the Hilt rig above.
- **A real device.** `profile: pixel_6` is a screen size and a density, not a phone. Gestures,
  the predictive-back animation `rememberPredictiveBackDismiss` is written for, and anything
  touching a camera are all emulator fictions here.
