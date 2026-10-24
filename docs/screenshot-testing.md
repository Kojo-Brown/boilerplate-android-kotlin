# Screenshot tests with Paparazzi

What a golden image is worth holding, what it costs, and why in this repository the images can
only be recorded in CI.

## What the pixels are for

The design system had two kinds of test before this. `core/ui/src/androidTest` drives the real
components through the Compose test rule — the label is shown, the click arrives, a disabled
button does not fire — and `core/ui/src/test` covers the parts that are plain functions, like
`fitsOnOneRow`. Both are about **behaviour**, and both pass against a button rendered in the
wrong colour, at the wrong size, or with its label invisible against its own fill.

That gap is not hypothetical in a Material 3 app, because so much of the appearance is resolved
rather than written. `isError = true` on a text field moves the outline, the label, the
supporting text and the trailing icon slot at once, by reading the colour scheme. `Color.kt` is
sixty hex literals that nothing in the repository reads. A screenshot test is the only kind that
can fail when one of those is wrong, and the failure it produces is a picture rather than a
message.

It is also the only test that can hold a claim made *about* pixels. Three were already written
down in KDoc and asserted nowhere:

- `UserMonogram` is one drawing at two magnifications, because `sharedElement` animates one
  rectangle into another with nothing cross-fading, and a glyph that sits differently in the
  circle at 40dp than at 80dp is a transition that jumps at its midpoint.
- `LabelledValue` places its children with `placeRelative`, so a right-to-left reader gets the
  label on the right. `place` compiles, passes every other test, and is wrong.
- `FlashToggle` relies on the checked tint as a second cue, so that its state is not carried by
  the icon alone.

Each of those is now a golden image, and `LabelledValue`'s is the second half of a pair:
`MeasurePolicyTest` holds the *decision* on a plain JVM and says in its own KDoc that holding the
policy in place would mean composing. These images are that coverage.

## How it is set up

Paparazzi is applied to `:core:ui` and to no other module. The gate is a design-system gate: a
widget here is drawn from the palette and type scale this module owns, so a pixel diff is a
statement about shared code rather than about one screen.

Everything renders through two declarations in
[`Screenshots.kt`](../core/ui/src/test/kotlin/com/kojo/boilerplate/core/ui/screenshot/Screenshots.kt),
so that the goldens stay comparable with each other and a new test is three lines long:

```kotlin
class AppButtonScreenshotTest {
    @get:Rule val paparazzi = screenshotRule()

    @Test
    fun primaryEnabled() = paparazzi.snapshot {
        ScreenshotHost(ThemeMode.Light) {
            AppButton(text = "Continue", onClick = {})
        }
    }
}
```

Three choices in there are load-bearing.

**`RenderingMode.SHRINK`.** The image is the component, not a phone with a component in the top
corner. That is the difference between a diff a reviewer can read and a column of empty pixels,
and between a 4 kB golden and a 300 kB one — these files live in the repository for as long as the
component does. The cost is that a composable asking for all the space it can get is handed the
whole screen: `LoadingIndicator` is `fillMaxSize` by design, so its test wraps it in a sized `Box`.

**`supportsRtl = true`.** Paparazzi's default is `false`, which pins the render left-to-right
whatever the device configuration says — so the RTL test would have rendered LTR and passed
forever. With the flag set, asking for `LayoutDirection.RTL` is enough; Paparazzi substitutes an
RTL locale of its own, so the image stays a statement about placement rather than about a script
the renderer may have no font for.

**`dynamicColor = false`.** This is the one that would have quietly emptied the gate.
`BoilerplateTheme` takes its palette from the wallpaper on Android 12 and above and defaults to
doing so, and Paparazzi reports `Build.VERSION.SDK_INT` as **34** — the plugin reads
`android.defaultConfig.targetSdkVersion` and falls back to 34, which is what a library module
gets, and 34 is also the API its layoutlib implements. So the dynamic branch is live under every
snapshot. Left at its default these would be pictures of layoutlib's stock `system_accent*`
resources: stable, plausible, and no longer pictures of `Color.kt` at all. Every token in the
module could then be edited without a single golden moving.

`ColorSchemeScreenshotTest` is the test that follows from that. It draws each role as a band of
the container colour carrying its own `on` colour as the label, so one image holds both halves of
the pair and the contrast between them; the roles with no content colour follow as swatches.
Between them the two lists name all twenty-nine tokens the theme sets.

## The gate

```
./gradlew verifyPaparazziDebug
```

Golden images live in `core/ui/src/test/snapshots/images`, named
`<package>_<class>_<method>.png` — which is why the test methods have short names. The task
re-renders every snapshot and compares it against the committed image with a tolerance of 0.1%,
Paparazzi's own default, spelled out in `screenshotRule` so that an upgrade cannot change how
strict this repository is without a line of the diff moving.

In CI it is a step of the **compile · lint · detekt · test** job, after `testDebugUnitTest`.
The ordinary test step has already *rendered* everything — asked for neither a recording nor a
verification, Paparazzi writes an HTML report — so a composable that cannot be rendered at all
fails there, as a test failure, with a stack trace. The verify step is what compares. It re-runs
`:core:ui:testDebugUnitTest` rather than reusing it, because the mode is one of that task's
declared inputs.

A failure writes `delta-<name>.png` into `core/ui/build/paparazzi/failures`, a side-by-side of
expected, actual and the difference between them. Those are in the `gate-reports` artifact.

Two failures are worth telling apart:

| What the gate says | What it means |
|---|---|
| `File … does not exist` | A snapshot has no committed golden. Record it. |
| `… changed by N%` plus a delta image | The rendering moved. Look at the delta before anything else. |

The second one is the whole point and also the whole risk: the reflex is to re-record until the
build is green, and re-recording is exactly how a screenshot suite stops being a gate. Read the
delta first. If the change is intended, record and commit it in the same pull request as the
change that caused it, so a reviewer sees the picture move next to the code that moved it.

## Recording, and why only CI can do it

**No developer machine in this project can render a golden image.** `dl.google.com` answers 403
on CONNECT from the scheduled agent's network, and that single host is both the Android SDK and —
through the `maven.google.com` redirect — the repository `com.android.tools.layoutlib` is
published to. Without layoutlib there is no renderer. This is the same conclusion Phase 0 items 1
and 2 reached about every other Gradle gate here, and it is recorded in SPEC.md rather than
treated as a local problem.

Even with the network open it would still be the wrong place. Paparazzi ships a different native
renderer per operating system, and antialiasing and font rasterisation do not agree across them;
goldens recorded on a Mac fail on a Linux runner for reasons that have nothing to do with the
code. One machine has to own them, and the only machine every contributor shares is CI.

So the recording path runs there, on a red gate only:

1. Push the change. The diff gate fails.
2. The job then runs `recordPaparazziDebug` and uploads the result as the **`paparazzi-snapshots`**
   artifact. It cannot launder the failure — the gate has already failed the job, and nothing the
   gate reads is written by it.
3. Download the artifact, read `gate-reports` for the deltas, and commit the images you agree with
   into `core/ui/src/test/snapshots/images`.

Locally, for anyone whose network does reach Google Maven, `./gradlew recordPaparazziDebug` writes
the same tree — but commit only what CI records, or the gate will go red on the next run for the
renderer rather than for the code.

## Keeping the set honest

`verifyPaparazziDebug` walks the tests and asks each for its image, so a snapshot with no golden
fails loudly. It never walks the images, so nothing asks the opposite question: a renamed test
leaves its old PNG in the tree forever, verified by nothing and reviewed by nobody, and a deleted
component leaves all of them.
[`GoldenImageInventoryTest`](../core/ui/src/test/kotlin/com/kojo/boilerplate/core/ui/screenshot/GoldenImageInventoryTest.kt)
is the other direction: it reads the directory, parses each file name back into the test that
produced it, and fails on anything that no longer resolves to a test method on the classpath. It
needs no renderer, so it runs in the ordinary test gate too.

What makes a golden worth adding:

- **One claim per image.** A snapshot of a whole screen fails for twenty reasons and tells you
  none of them.
- **Deterministic by construction.** A fixed palette, no clock, no randomness, no network. An
  indeterminate `CircularProgressIndicator` is fine: Paparazzi renders a single frame with the
  platform clock held at zero, so the sweep is recorded at its starting position every time.
- **Small.** SHRINK plus a sized wrapper. A golden is reviewed in a diff and cloned by everyone.
- **A state a user reaches.** Loading, error, empty, disabled, dark, RTL, double font scale — the
  states that are hard to reach by hand and easy to break by accident.

## Known limits

- **One module.** `:core:ui` only. A feature screen has state, a view model and a navigation
  graph behind it, so a golden of one is a different exercise; Phase 12's remaining items
  (instrumented tests on an emulator matrix, Hilt test modules with fake bindings) are what that
  needs.
- **A new colour role needs a line in the sheet.** `ColorSchemeScreenshotTest` lists the roles
  explicitly, which is what makes it exhaustive today and what a role added to
  `BoilerplateTheme` has to be added to.
- **The renderer is API 34, not 35.** Paparazzi 1.3.5 resolves layoutlib 14.0.11 and the
  framework resources that belong to it. The components here use nothing newer, and the
  alternative — a 2.0.0 alpha — is pinned out for the reasons in `gradle/libs.versions.toml`.
- **Paparazzi and Compose are a version pair.** 1.3.5 reaches into Compose internals
  (`AndroidUiDispatcher`, `Recomposer`, `SnapshotKt`) by reflection, which is why it is held
  against the Compose BOM this repository pins. Moving Compose past 1.7.x means moving Paparazzi
  in the same change, and the failure mode of getting that wrong is an exception from inside the
  renderer rather than a compile error.
