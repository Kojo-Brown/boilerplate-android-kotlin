package com.kojo.boilerplate.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Produces the baseline profile that `app/src/main/baseline-prof.txt` holds.
 *
 * ## What a baseline profile is, and why this is the only way to get one
 *
 * ART installs an app's dex without compiling it. Everything in a cold start is interpreted the
 * first time it runs, and only becomes machine code later, after the device's own background
 * profiling has watched the app for a while. A baseline profile is a list of the classes and
 * methods that matter on the paths a user hits immediately — startup, the first screen — shipped
 * with the APK so that ART compiles them ahead of first launch instead of learning them over the
 * first few days.
 *
 * The list cannot be written by hand in any honest way. It is the transitive set of everything the
 * platform, AndroidX, Compose, Hilt and this app's own code touch between `am start` and the first
 * screen, which is thousands of methods and is not knowable by reading the source. So it is
 * *recorded*: this rule launches the app the same way [StartupBenchmark] does, asks ART for the
 * profile it accumulated, and writes it out in the human-readable format AGP reads.
 *
 * ## Where the output goes
 *
 * `BaselineProfileRule` writes `…-baseline-prof.txt` into the run's additional test output, which
 * Gradle pulls back to `benchmark/build/outputs/`. It does not write into `app/src/main`, and this
 * repository deliberately does not use the `androidx.baselineprofile` Gradle plugin that would:
 * that plugin generates two extra build types per existing one, derived from `release` — and
 * `release` cannot be built here while the certificate pins and the Play Integrity project are
 * empty, for the reasons `app/build.gradle.kts` gives next to the `minified` build type. The
 * profile is therefore a reviewed, checked-in file, and `scripts/verify-startup-budget.py` is what
 * fails CI when the committed one no longer covers what this rule records.
 *
 * ## Why the body is the same three lines as the benchmark
 *
 * Whatever this does not exercise, ART does not record, and the app ships without it compiled. A
 * profile collected against a path that is not the measured path is the one failure mode here that
 * produces a green gate and a slower app, which is why [FIRST_SCREEN_TEXT] is shared between the
 * two files rather than written twice.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun startup() = baselineProfileRule.collect(packageName = TARGET_PACKAGE) {
        // From the launcher, not from whatever was on screen: `collect` installs and kills the app
        // between iterations, and a start from inside another task records a warm resume rather
        // than a cold launch.
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.text(FIRST_SCREEN_TEXT)), FIRST_SCREEN_TIMEOUT_MS)
    }
}
