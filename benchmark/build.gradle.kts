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
         * The two variants that do the work, matched by name to `:app`'s `benchmark` and
         * `nonMinifiedBenchmark` build types.
         *
         * A `com.android.test` module builds a variant per *its own* build type and matches each to
         * the target project's by name, so these two blocks are what make `:app`'s benchmark
         * variants reachable at all.
         *
         * ## matchingFallbacks, and why omitting it was wrong
         *
         * The first version of this file left `matchingFallbacks` off both, on the reasoning that
         * `:app` has build types of exactly these names so no fallback could ever be needed — and
         * that a fallback would quietly measure the wrong variant if somebody deleted one of them.
         * CI falsified that in a way worth writing down, because the error names none of this:
         *
         *     Could not determine the dependencies of task
         *     ':benchmark:connectedNonMinifiedBenchmarkAndroidTest'
         *       ... EdgeState.calculateTargetConfigurations
         *
         * The missing step in the reasoning is that this module does not resolve only `:app`. A test
         * module compiles and runs against the tested app's whole graph, so the request carries
         * `BuildTypeAttr = nonMinifiedBenchmark` all the way down to `:core:common` and the other
         * fourteen library modules — and a library module has `debug` and `release` and nothing
         * else. `:app`'s own `matchingFallbacks` governs `:app`'s configurations, not this module's.
         * The macrobenchmark documentation says to put the property in both modules for exactly this
         * reason; the error it quotes is the same one, one project further along.
         *
         * `debug` and not `release`, so that these variants resolve the same library halves `:app`'s
         * own benchmark variants do — `app/build.gradle.kts` falls back to `debug` because `release`
         * cannot be built in this repository. Falling back differently here would compile the test
         * APK against one set of library classes and install it beside an app built from another.
         *
         * The concern that motivated leaving it out is real but misplaced: deleting `:app`'s
         * `benchmark` build type would now silently fall back to `debug` rather than fail. What
         * catches that is `scripts/verify-startup-budget.py`, which fails on a run whose
         * `warmupIterations` or iteration count does not match the budget, and
         * `BaselineProfileMode.Require`, which fails on an app with no profile compiled in — a
         * debuggable `debug` build would trip both.
         *
         * ## Why the stock variants stay
         *
         * `debug` and `release` are left in place and deliberately not filtered out with
         * `beforeVariants`. `debug` is what carries this module through the gates every other module
         * goes through — `compileDebugKotlin`, `lintDebug`, `detekt` and `resolveAllDependencies`
         * are all unqualified in ci.yml, and `resolveAllDependencies` asserts it saw
         * `debugCompileClasspath` specifically — so filtering it out would take this module out of
         * every gate that can run without a device. Nothing assembles the `release` variant, here
         * or anywhere else in this repository.
         */
        create("benchmark") {
            // The *test* APK, which is not the thing being measured. Debuggable so a failing
            // benchmark can be attached to and stepped through; it shares no process with the
            // app under test, so this cannot affect a measurement. Debug-signed because that is
            // the one key every checkout has, and an unsigned test APK will not install.
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("debug")
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
            matchingFallbacks += listOf("debug")
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

    // `UiDevice`, `By` and `Until`, for `awaitFirstScreen`. Both tests wait for the app's first
    // screen rather than trusting `startActivityAndWait`'s first frame, because the first frame of
    // this app is an empty themed window: `MainActivity.onCreate` calls `setContent`, and the
    // sign-in screen's content arrives a composition later. A profile recorded against the first
    // frame would miss most of what Compose does to produce the second — see FirstScreen.kt, which
    // also says why the wait's result is asserted rather than ignored.
    implementation(libs.androidx.test.uiautomator)
}
