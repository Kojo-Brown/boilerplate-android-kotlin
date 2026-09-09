package com.kojo.boilerplate.buildlogic

import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import java.time.Duration
import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

/**
 * Values every module shares, in one place so that a bump is one edit rather than fifteen.
 */
object BoilerplateBuild {
    const val COMPILE_SDK = 35
    const val MIN_SDK = 26
    const val TARGET_SDK = 35
    const val NAMESPACE_PREFIX = "com.kojo.boilerplate"
    const val TEST_RUNNER = "androidx.test.runner.AndroidJUnitRunner"
    val JAVA_VERSION: JavaVersion = JavaVersion.VERSION_17
    val JVM_TARGET: JvmTarget = JvmTarget.JVM_17
}

/** The main build's version catalog, which `build-logic/settings.gradle.kts` shares with it. */
internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

/**
 * Pins the Kotlin target to match the Java one declared on the Android extension.
 *
 * Deliberately expressed as a task configuration rather than through the Kotlin extension: it
 * is the same one line for an application, a library and — should one ever appear — a plain
 * JVM module, and it does not depend on which Kotlin plugin flavour is applied.
 */
internal fun Project.configureKotlinJvmTarget() {
    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions.jvmTarget.set(BoilerplateBuild.JVM_TARGET)
    }
}

/**
 * The unit-test setup every module gets, moved here verbatim from the single-module
 * `app/build.gradle.kts`.
 *
 * Configured on Gradle's own `Test` task type rather than through `android.testOptions`, which
 * reaches the same tasks and keeps this function usable from every convention plugin.
 */
internal fun Project.configureUnitTests() {
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()

        // A deadlocked test should fail this task, not sit until the CI job's own timeout kills
        // the run and leaves no test report behind — which is exactly what
        // DataStoreTokenProviderTest did before it was fixed. Gradle's task timeout covers both
        // engines here; a JUnit 5 default timeout would not, since the suite still runs JUnit 4
        // classes through the vintage engine. Ten minutes is far above the suite's real runtime
        // and only ever trips on something genuinely stuck.
        timeout.set(Duration.ofMinutes(TEST_TIMEOUT_MINUTES))

        // Put the failure message in the console, not only in the HTML report. Gradle's default
        // prints `AssertionFailedError at SomeTest.kt:50` and keeps the expected/actual values
        // for the report — so a CI log tells you which assertion broke but not what it saw, and
        // reading further means downloading the gate-reports artifact. That is a round trip at
        // best and impossible from a network that cannot reach the artifact host at all.
        //
        // Scoped to failures: passing tests stay silent.
        testLogging {
            events("failed")
            exceptionFormat = TestExceptionFormat.FULL
            showStackTraces = true
            showCauses = true
        }
    }
}

/**
 * Applies detekt with the repository's single configuration file.
 *
 * `source` names the three source sets explicitly, as the single-module build did: the
 * per-variant `detektMain`/`detektTest` tasks the Android plugin would add need type resolution
 * and a full compile first, and this repo gates on the compile task directly instead. `basePath`
 * is the repository root in every module, which is what keeps path-scoped rules matching the
 * same way from wherever they are evaluated: the `ForbiddenImport` rule is scoped by a glob over
 * the `core/domain` path, and a per-module base path would make that glob mean something
 * different in each of fifteen places.
 *
 * That glob is deliberately not written out here. A KDoc block ends at the first `*` followed by
 * a slash, so a path glob quoted inside one closes the comment and turns the rest of the file
 * into a parse error — which is exactly how this comment failed CI the first time.
 */
internal fun Project.configureDetekt() {
    pluginManager.apply("io.gitlab.arturbosch.detekt")

    extensions.configure<DetektExtension> {
        source.setFrom(
            files("src/main/kotlin", "src/test/kotlin", "src/androidTest/kotlin"),
        )
        parallel = true
        // The bundled default ruleset stays active; config/detekt/detekt.yml only carries the
        // deltas, so a detekt upgrade brings its new rules in rather than silently inheriting a
        // frozen snapshot.
        buildUponDefaultConfig = true
        config.setFrom(files("${rootProject.rootDir}/config/detekt/detekt.yml"))
        basePath = rootProject.rootDir.absolutePath
    }

    tasks.withType<Detekt>().configureEach {
        // Detekt forks its own JVM analysis and defaults to the Gradle daemon's target, which
        // is 21 here. Pin it to the module's target so the two never disagree.
        jvmTarget = BoilerplateBuild.JAVA_VERSION.toString()
        reports {
            html.required.set(true)
            sarif.required.set(true)
            xml.required.set(false)
            txt.required.set(false)
            md.required.set(false)
        }
    }
}

/**
 * The test stack every module's unit tests are written against.
 *
 * Declared once here rather than per module because the choice is repository-wide: JUnit 5 for
 * new tests, the vintage engine so the JUnit 4 classes that predate it still run, `runTest` for
 * anything with a coroutine in it, and mockk for the handful of platform types a fake cannot
 * stand in for. A module that needed a different stack would be a signal worth noticing, not a
 * convenience worth pre-supporting.
 */
internal fun Project.sharedTestDependencies() {
    dependencies.apply {
        add("testImplementation", libs.findLibrary("junit").get())
        add("testImplementation", libs.findLibrary("junit-jupiter-api").get())
        add("testRuntimeOnly", libs.findLibrary("junit-jupiter-engine").get())
        add("testRuntimeOnly", libs.findLibrary("junit-vintage-engine").get())
        add("testImplementation", libs.findLibrary("kotlinx-coroutines-test").get())
        add("testImplementation", libs.findLibrary("mockk").get())
    }
}

/**
 * The Compose dependencies every UI module needs, all of them versioned by the BOM.
 *
 * Only the common floor is here — `ui`, `material3`, the tooling preview and the test rig.
 * Anything a single screen reaches for (the icon pack, the adaptive layouts, CameraX, ML Kit)
 * stays in that module's own build file, where the dependency says something about the module
 * instead of being inherited by twelve others.
 */
internal fun Project.configureCompose() {
    dependencies.apply {
        // `platform` is a member of `DependencyHandler` itself — Gradle's Java API, not a
        // Kotlin DSL extension — so there is nothing to import for it here.
        val bom = platform(libs.findLibrary("androidx-compose-bom").get())
        add("implementation", bom)
        add("androidTestImplementation", bom)

        add("implementation", libs.findLibrary("androidx-ui").get())
        add("implementation", libs.findLibrary("androidx-ui-graphics").get())
        add("implementation", libs.findLibrary("androidx-ui-tooling-preview").get())
        add("implementation", libs.findLibrary("androidx-material3").get())
        add("implementation", libs.findLibrary("androidx-lifecycle-runtime-compose").get())

        add("debugImplementation", libs.findLibrary("androidx-ui-tooling").get())
        add("debugImplementation", libs.findLibrary("androidx-ui-test-manifest").get())

        add("androidTestImplementation", libs.findLibrary("androidx-ui-test-junit4").get())
        add("androidTestImplementation", libs.findLibrary("kotlinx-coroutines-test").get())
    }

    configureComposeCompilerReports()
}

/**
 * Points the Compose compiler's stability metrics and reports at this module's build directory,
 * when the build is asked for them.
 *
 * ### What these files are
 *
 * The Compose compiler infers a stability for every class it sees and decides, per composable,
 * whether the function can be *skipped* when it is recomposed with the arguments it already has.
 * Both decisions are made silently and neither shows up in a build log. These two options make
 * the compiler write them down: `compose-reports` gets the human-readable `-classes.txt` and
 * `-composables.txt`, `compose-metrics` gets the per-module `-module.json` counts.
 *
 * `StabilityContractTest` already holds every type reachable from a `StateFlow` to the
 * `@Immutable`/`@Stable` contract, and it does that by reflection over compiled classes. What it
 * cannot see is the other half of the question: whether a composable actually *skips*. A screen
 * can be built entirely from annotated state and still recompose on every frame because one
 * parameter is an androidx type the compiler treats as unstable — a fact that exists only in
 * these reports. `scripts/verify-compose-metrics.py` is what reads them in CI.
 *
 * ### Why it is opt-in
 *
 * The destinations are `FilesSubpluginOption`s of the default (`INTERNAL`) kind, so the Kotlin
 * compile task does not declare them as outputs. Two things follow, and both are the reason the
 * property exists rather than the reports simply always being on:
 *
 * - Setting them changes the compiler arguments, which changes the compile task's cache key. A
 *   developer building locally would recompile every Compose module the first time they ran a
 *   command that had them on and again the first time they ran one that had them off.
 * - Because they are not declared outputs, a compile task restored from the build cache writes
 *   no reports at all. That is not a hole the way it would be for a value read from them — the
 *   verifier fails on a module that did not report — but it is why CI runs the compile step that
 *   generates them with `--no-build-cache`, and why the property is set for the whole workflow
 *   rather than on one step, so every Gradle invocation in a job agrees about the arguments.
 */
private fun Project.configureComposeCompilerReports() {
    val requested = providers.gradleProperty(COMPOSE_REPORTS_PROPERTY).orNull
    if (requested?.toBooleanStrictOrNull() != true) return

    extensions.configure<ComposeCompilerGradlePluginExtension> {
        reportsDestination.set(layout.buildDirectory.dir(COMPOSE_REPORTS_DIR))
        metricsDestination.set(layout.buildDirectory.dir(COMPOSE_METRICS_DIR))
    }
}

/**
 * Registers `resolveAllDependencies`, which proves that every version declared in
 * `gradle/libs.versions.toml` actually exists on a configured repository by resolving the
 * dependency *graph* of every classpath the module builds against, and reports every module that
 * could not be resolved.
 *
 * `./gradlew dependencies` is not a substitute: it prints a `FAILED` marker next to an
 * unresolvable module and still exits 0, so it cannot gate CI.
 *
 * This walks metadata only and deliberately does not download artifacts. A version that does not
 * exist fails during metadata resolution, which is the question this task answers, and skipping
 * the artifact fetch keeps the job to a couple of minutes instead of pulling every variant's full
 * graph — ML Kit and CameraX alone are hundreds of megabytes. The trade-off is that a
 * published-but-empty module (POM present, AAR missing) would slip through here; the compile and
 * assemble gates are what cover that.
 *
 * ### Why the classpaths are collected in `afterEvaluate`
 *
 * They have to be, twice over, and getting this wrong cost a CI round trip.
 *
 * A convention plugin runs while the module's build file is still being *read*. Reaching for
 * `configurations` there sees only the handful AGP has created so far — not one variant
 * classpath — so the check covered almost nothing while reporting success: every one of the
 * thirteen modules resolved the same "5 configurations, 39 module nodes", `:app` with its eleven
 * project dependencies included. A gate that passes without looking is worse than no gate.
 *
 * Worse, asking a configuration for its `resolutionResult` marks it and everything it extends
 * from as observed. Dependencies the build file declares *after* that point are not reliably
 * picked up — which is how `:core:domain` lost `api(project(":core:common"))` and failed to
 * compile against a module its own build file names.
 *
 * `afterEvaluate` is the earliest point where the script has finished and AGP's own
 * `afterEvaluate` — registered when it was applied, so it runs first — has created the variant
 * configurations. [assertSawVariantClasspaths] is what stops this silently regressing.
 */
internal fun Project.configureDependencyResolutionCheck() {
    val task = tasks.register("resolveAllDependencies") {
        group = "verification"
        description =
            "Resolves every classpath's dependency graph so a non-existent version fails."
    }

    afterEvaluate {
        val graphs = configurations
            .matching { it.isCanBeResolved && it.name.endsWith("Classpath") }
            .map { it.name to it.incoming.resolutionResult.rootComponent }

        task.configure {
            doLast {
                assertSawVariantClasspaths(graphs.map { it.first })

                val failures = mutableListOf<String>()
                var modules = 0

                graphs.forEach { (name, rootComponent) ->
                    val seen = mutableSetOf<ResolvedComponentResult>()

                    fun visit(component: ResolvedComponentResult) {
                        if (!seen.add(component)) return
                        component.dependencies.forEach { dependency ->
                            when (dependency) {
                                is ResolvedDependencyResult -> visit(dependency.selected)
                                is UnresolvedDependencyResult ->
                                    failures += "$name -> ${dependency.requested.displayName}: " +
                                        dependency.failure.message
                                else -> Unit
                            }
                        }
                    }

                    visit(rootComponent.get())
                    modules += seen.size
                    logger.lifecycle("Resolved $name (${seen.size} modules)")
                }

                if (failures.isNotEmpty()) {
                    throw GradleException(
                        "${failures.size} dependency/dependencies could not be resolved:\n" +
                            failures.joinToString("\n") { "  $it" },
                    )
                }

                logger.lifecycle(
                    "Resolved ${graphs.size} configurations, $modules module nodes, 0 failures",
                )
            }
        }
    }
}

/**
 * Fails when the collected classpaths do not include the ones a variant actually builds against.
 *
 * This is the check on the check. Collecting configurations a moment too early produces a task
 * that resolves a few plugin-internal classpaths, finds nothing wrong with them, and reports
 * success — which is exactly what it did before, in every module, for a whole CI run.
 */
private fun assertSawVariantClasspaths(names: List<String>) {
    val required = listOf("debugCompileClasspath", "debugRuntimeClasspath")
    val missing = required.filterNot { it in names }
    if (missing.isNotEmpty()) {
        throw GradleException(
            "resolveAllDependencies collected ${names.size} classpath(s) and none of them were " +
                "${missing.joinToString(", ")}. The configurations were read before AGP created " +
                "them, so this task is resolving almost nothing and passing. Collect them later.",
        )
    }
}

private const val TEST_TIMEOUT_MINUTES = 10L

/**
 * The Gradle property that turns the Compose compiler's stability reports on. CI sets it for the
 * whole workflow as `ORG_GRADLE_PROJECT_composeCompilerReports`; locally it is
 * `-PcomposeCompilerReports=true`.
 */
private const val COMPOSE_REPORTS_PROPERTY = "composeCompilerReports"

// Both directory names are also spelled in scripts/verify-compose-metrics.py, which reads what
// the compiler writes here. They are asserted there rather than shared: the verifier runs
// without Gradle on the classpath, and a name that only one of the two knows about surfaces as
// "no module reported", which is a failure and not a silent pass.
private const val COMPOSE_REPORTS_DIR = "compose-reports"
private const val COMPOSE_METRICS_DIR = "compose-metrics"
