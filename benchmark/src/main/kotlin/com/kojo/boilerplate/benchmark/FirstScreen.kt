package com.kojo.boilerplate.benchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until

/*
 * How both tests decide the app has finished starting.
 *
 * In one place rather than written twice, because the two have to agree: a profile recorded against
 * one stopping point and a benchmark measured against another would report an improvement that
 * nothing in the app had earned.
 */

/**
 * Text on `:app`'s start destination, which is the Google sign-in screen — `AppNavHost` opens on
 * `AppDestination.SignIn`, `GoogleSignInViewModel` starts in `Idle`, and `Idle` renders
 * `SignInContent`, whose button carries this label. So it is on screen in the first composition
 * that draws anything, with no network call or preference read in front of it.
 *
 * UiAutomator can see it because Compose publishes its text into the accessibility node tree, which
 * is the same tree `By.text` queries. That is also why this is the button's label and not a
 * `testTag`: tags only reach UiAutomator when the app opts in with
 * `semantics { testTagsAsResourceId = true }`, and this app does not.
 */
private const val FIRST_SCREEN_TEXT = "Sign in with Google"

/**
 * How long to wait for [FIRST_SCREEN_TEXT] after the activity's first frame.
 *
 * Generous on purpose: this runs on a CI emulator with software rasterisation and two cores, and
 * the wait is a correctness condition rather than part of the measurement — `StartupTimingMetric`
 * reads the platform's own first-frame trace points, not the wall clock around this call. A timeout
 * that fired because the emulator was slow would turn a measured regression into a flake.
 */
private const val FIRST_SCREEN_TIMEOUT_MS = 10_000L

/**
 * Waits for the app's first screen to be on screen, and fails the test if it never is.
 *
 * `startActivityAndWait` returns on the first frame, and the first frame of this app is an empty
 * themed window: `MainActivity.onCreate` calls `enableEdgeToEdge` and `setContent`, and the sign-in
 * screen's own content cannot appear until a composition later. Stopping at the first frame would
 * record a profile covering the activity and the theme and almost nothing Compose does to draw the
 * screen — which is most of what a baseline profile is for in a Compose app.
 *
 * The `check` is the point of this function existing at all. `UiDevice.wait` *returns* whether the
 * condition was met and throws nothing when it was not, so an ignored result is the quietest failure
 * in this module: the generator would record a first-frame-only profile, the benchmark would measure
 * a launch that stopped early, and both would be internally consistent. The committed profile would
 * then be thin for a reason no gate names — `scripts/verify-startup-budget.py`'s per-package floors
 * might well still pass, because the first frame already loads a good deal of Compose. Asserting
 * here turns that into one failure naming the text that never appeared.
 */
internal fun MacrobenchmarkScope.awaitFirstScreen() {
    // `textContains` and not `text`: UiAutomator's exact matcher compares against the whole of the
    // accessibility node's text, and the node here is the Button — Compose merges a button's
    // descendants into one node, so what arrives is whatever that merge produced. It should be
    // exactly this string and matching it exactly would be more precise, but "more precise" buys
    // nothing when the alternative failure is a false negative on a gate whose round trip is an
    // emulator boot. Nothing else on this screen contains it.
    val appeared = device.wait(
        Until.hasObject(By.textContains(FIRST_SCREEN_TEXT)),
        FIRST_SCREEN_TIMEOUT_MS,
    )
    check(appeared) {
        "\"$FIRST_SCREEN_TEXT\" did not appear within ${FIRST_SCREEN_TIMEOUT_MS}ms of the first " +
            "frame. Either the app no longer opens on the sign-in screen — in which case this " +
            "constant and docs/baseline-profiles.md both need updating — or it failed to start. " +
            "Everything recorded or measured up to this point describes a launch that did not " +
            "finish, so it is not usable either way."
    }
}
