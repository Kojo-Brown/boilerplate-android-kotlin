package com.kojo.boilerplate.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold startup, measured twice: once with nothing compiled, once with the committed baseline
 * profile and nothing else.
 *
 * ## What the two cases are for
 *
 * [withoutCompilation] is the floor — ART interpreting everything, which is what a user with no
 * profile installed gets. [withBaselineProfile] is what this app actually ships, because
 * `app/src/main/baseline-prof.txt` is packaged into the APK and ProfileInstaller hands it to ART on
 * first run.
 *
 * Measuring both is the only way the gate can tell two very different failures apart. If the app's
 * startup gets slower, both numbers move together and the cause is in the app. If only
 * [withBaselineProfile] moves, the profile has stopped covering the startup path — a renamed
 * package, a new screen in front of the old one, a dependency that moved its initialisation — and
 * the app is shipping a profile that describes code it no longer runs. The second failure is
 * invisible to any single measurement, and it is the one a checked-in profile is prone to.
 *
 * `scripts/verify-startup-budget.py` reads both out of the run's `benchmarkData.json` and holds
 * each to its own budget in `config/benchmark/startup-budget.json`.
 *
 * ## Require, and warmupIterations = 0
 *
 * [BaselineProfileMode.Require] fails the test when the profile is not in the APK or ART refused
 * it, instead of falling back to measuring an uncompiled app. That is the difference between this
 * gate and a gate that cannot fail: `UseIfAvailable` would report a perfectly good number for a
 * build whose profile had silently stopped being packaged, which is precisely the regression worth
 * catching.
 *
 * `warmupIterations = 0` is what keeps the number attributable. `CompilationMode.Partial`'s default
 * is to run the app a few times first and let ART's own JIT profile compile whatever it saw, on top
 * of the baseline profile. The result is faster and tells you nothing about the file in `src/main`,
 * because you can no longer separate what the profile compiled from what the warmup did.
 *
 * ## What these numbers are not
 *
 * Absolute figures from a CI emulator, with a software rasteriser and a CPU shared with the rest of
 * the runner. They are a regression signal against this repository's own history and nothing else —
 * see the suppression comment in `benchmark/build.gradle.kts` and docs/baseline-profiles.md.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun withoutCompilation() = measureStartup(CompilationMode.None())

    @Test
    fun withBaselineProfile() = measureStartup(
        CompilationMode.Partial(
            baselineProfileMode = BaselineProfileMode.Require,
            warmupIterations = 0,
        ),
    )

    private fun measureStartup(compilationMode: CompilationMode) = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        iterations = ITERATIONS,
        startupMode = StartupMode.COLD,
        compilationMode = compilationMode,
        // Outside the measured block on purpose: going to the launcher is setup, and timing it
        // would add the launcher's own frame to every iteration.
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        awaitFirstScreen()
    }

    private companion object {

        /**
         * Iterations per case. Macrobenchmark reports the median of these, which is what the budget
         * is written against.
         *
         * Ten rather than the handful a laptop would need: the measurement is a cold process start
         * on an emulator sharing a CPU with the rest of a CI runner, so the spread between
         * iterations is wide and a median over too few of them is a coin toss that would show up as
         * a flaky gate. Ten is also the point past which the two cases together stop fitting
         * comfortably inside this job's share of the workflow — each iteration reinstalls or
         * recompiles the app before launching it.
         */
        const val ITERATIONS = 10
    }
}
