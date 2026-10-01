plugins {
    id("boilerplate.android.test")
}

android {
    namespace = "com.kojo.boilerplate.benchmark"

    /*
     * The module this one measures. `com.android.test` builds an APK that is installed *beside*
     * the one named here and drives it from outside, which is what makes a macrobenchmark
     * possible at all: startup is what happens between `am start` and the first frame, and no
     * code inside the process can observe its own cold start.
     */
    targetProjectPath = ":app"

    /*
     * Self-instrumenting: the test APK's `<instrumentation>` targets its own package rather than
     * the app's.
     *
     * Without it the instrumentation runs *inside* the app's process, which breaks a
     * macrobenchmark in two ways at once. The process would already be warm and loaded with the
     * test's own classes before the first measured launch, so `StartupMode.COLD` would be
     * measuring a process it had itself started; and killing the app between iterations — which
     * is how each iteration gets a cold process — would kill the runner with it.
     */
    experimentalProperties["android.experimental.self-instrumenting"] = true

    defaultConfig {
        /*
         * Macrobenchmark refuses to run on a device it cannot trust the numbers from, and an
         * emulator is top of that list: no thermal behaviour, a CPU shared with whatever else
         * the runner is doing, and a GPU that is software rasterisation. The errors it raises
         * are correct, and suppressing them is a deliberate trade this repository has to make —
         * the only hardware CI has is an emulator, and the alternative is a performance gate
         * that never runs.
         *
         * What survives the trade is a regression gate, not a performance figure. A number
         * measured here says nothing about a user's phone, and `config/benchmark/startup-budget.json`
         * is calibrated against this emulator and declares the device it was calibrated on, so a
         * run on different hardware fails rather than silently comparing to the wrong baseline.
         * docs/baseline-profiles.md has the reasoning and what it would take to gate on a real
         * device instead.
         *
         * `UNLOCKED` goes with it: the benchmark needs the screen on and unlocked, and the CI
         * emulator boots with the lock screen disabled via `-no-snapshot` plus the
         * `adb shell` calls in ci.yml rather than by a setting macrobenchmark can observe.
         *
         * `DEBUGGABLE` is deliberately *not* suppressed. The `benchmark` build type below is not
         * debuggable, and if that ever changes this is the error that has to be the one that
         * fires: a debuggable build has JIT behaviour of its own and would make every number
         * here meaningless rather than merely noisy.
         */
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR,UNLOCKED"
    }

    buildTypes {
        /*
         * The variant that does the measuring, matched by name to `:app`'s `benchmark` build type.
         *
         * A `com.android.test` module builds a variant per *its own* build type and matches each
         * to the target project's by name, so this block is what makes `:app`'s `benchmark`
         * variant reachable at all. It carries no `matchingFallbacks` on purpose: a fallback
         * would quietly measure `:app`'s `release` or `minified` build the day somebody removed
         * the `benchmark` build type from `app/build.gradle.kts`, and a startup benchmark against
         * the wrong variant is a number that looks fine and means nothing. Without one, that edit
         * fails the build and names the missing build type.
         *
         * The stock `debug` and `release` variants are left in place and deliberately not
         * filtered out with `beforeVariants`. `debug` is what carries this module through the
         * gates every other module goes through — `compileDebugKotlin`, `lintDebug`, `detekt` and
         * `resolveAllDependencies` are all unqualified in ci.yml, and `resolveAllDependencies`
         * asserts it saw `debugCompileClasspath` specifically — so filtering it out would take
         * this module out of every gate that can run without a device. Nothing assembles the
         * `release` variant, here or anywhere else in this repository.
         */
        create("benchmark") {
            // The *test* APK, which is not the thing being measured. Debuggable so a failing
            // benchmark can be attached to and stepped through; it shares no process with the
            // app under test, so this cannot affect a measurement. Debug-signed because that is
            // the one key every checkout has, and an unsigned test APK will not install.
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
        }

        /*
         * The variant `BaselineProfileGenerator` runs on, matched to `:app`'s
         * `nonMinifiedBenchmark` build type. `app/build.gradle.kts` has the reason that build type
         * exists: a profile recorded against a shrunk app is a list of R8's names, and the file that
         * gets checked in has to be a list of the source's.
         *
         * Identical to the block above in every respect, because this module's APK is not what
         * differs between the two runs — the app under test is.
         */
        create("nonMinifiedBenchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    /*
     * `implementation` and nothing else: this module has no API — no other module may depend on
     * it, which the rule map in the root build file enforces by absence.
     */

    // `MacrobenchmarkRule`, `BaselineProfileRule`, `StartupTimingMetric` and the compilation
    // modes. The whole module exists for these two rules.
    implementation(libs.androidx.benchmark.macro.junit4)

    // `AndroidJUnit4`, which both tests run with.
    implementation(libs.androidx.test.ext.junit)

    // `UiDevice`, `By` and `Until`. Both tests wait for the app's first screen to be on screen
    // rather than trusting `startActivityAndWait`'s first frame, because the first frame of this
    // app is an empty themed window: `MainActivity.onCreate` calls `setContent`, and the sign-in
    // screen's text arrives a composition later. A profile generated against the first frame
    // would miss everything Compose does to produce the second.
    implementation(libs.androidx.test.uiautomator)
}
