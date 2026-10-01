package com.kojo.boilerplate.benchmark

/*
 * What both tests in this module need to know about the app they point at.
 *
 * In one file rather than duplicated into two companion objects because the two tests have to
 * agree: a profile generated against one wait condition and a benchmark measured against another
 * would report an improvement that nothing in the app had earned.
 */

/**
 * The application id of the `benchmark` variant of `:app`.
 *
 * It is `android.defaultConfig.applicationId` with no suffix, because no build type in
 * `app/build.gradle.kts` sets `applicationIdSuffix` — `benchmark` is `initWith(minified)`, which is
 * `initWith(release)`, and none of the three adds one. Written out rather than read from the build
 * because an instrumentation argument is the only way a test module could be told, and a wrong
 * value fails immediately and unmistakably: Macrobenchmark cannot find the package and says so.
 */
internal const val TARGET_PACKAGE = "com.kojo.boilerplate"

/**
 * Text on `:app`'s start destination, which is the Google sign-in screen — `AppNavHost` opens on
 * `AppDestination.SignIn`.
 *
 * Both tests wait for this rather than stopping at `startActivityAndWait`, which returns on the
 * first frame. The first frame of this app is an empty themed window: `MainActivity.onCreate`
 * calls `enableEdgeToEdge` and `setContent`, and the sign-in screen's own text cannot appear until
 * a composition later. Stopping at the first frame would generate a profile that covers the
 * activity and the theme and nothing Compose does to draw the screen — which is most of what a
 * baseline profile is for in a Compose app.
 *
 * UiAutomator can see it because Compose publishes its text into the accessibility node tree,
 * which is the same tree `By.text` queries. That is also why this is the button's label and not a
 * `testTag`: tags only reach UiAutomator when the app opts in with
 * `semantics { testTagsAsResourceId = true }`, and this app does not.
 */
internal const val FIRST_SCREEN_TEXT = "Sign in with Google"

/**
 * How long to wait for [FIRST_SCREEN_TEXT] after the activity's first frame.
 *
 * Generous on purpose: this runs on a CI emulator with software rasterisation, and the wait is a
 * correctness condition rather than part of the measurement — `StartupTimingMetric` reads the
 * platform's own `Activity.reportFullyDrawn`/first-frame trace points, not the wall clock around
 * this call. A timeout that fired because the emulator was slow would turn a measured regression
 * into a flake.
 */
internal const val FIRST_SCREEN_TIMEOUT_MS = 10_000L
