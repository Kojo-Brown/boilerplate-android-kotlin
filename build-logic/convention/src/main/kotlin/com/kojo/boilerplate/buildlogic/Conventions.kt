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
import org.gradle.kotlin.dsl.platform
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

/**
 * Values every module shares, in one place so that a bump is one edit rather than thirteen.
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
 * is the repository root in every module, which is what keeps path-scoped rules — the
 * `ForbiddenImport` rule is scoped to `**/core/domain/**` — matching the same way from wherever
 * they are evaluated.
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
}

/**
 * Registers `resolveAllDependencies`, which proves that every version declared in
 * `gradle/libs.versions.toml` actually exists on a configured repository by resolving the
 * dependency *graph* of every classpath the module builds against, and reports every module
 * that could not be resolved.
 *
 * `./gradlew dependencies` is not a substitute: it prints a `FAILED` marker next to an
 * unresolvable module and still exits 0, so it cannot gate CI.
 *
 * This walks metadata only and deliberately does not download artifacts. A version that does not
 * exist fails during metadata resolution, which is the question this task answers, and skipping
 * the artifact fetch keeps the job to a couple of minutes instead of pulling every variant's
 * full graph — ML Kit and CameraX alone are hundreds of megabytes. The trade-off is that a
 * published-but-empty module (POM present, AAR missing) would slip through here; the compile and
 * assemble gates are what cover that.
 *
 * Registered per module rather than once on `:app` so that `./gradlew resolveAllDependencies`
 * covers every module's classpaths, including the ones `:app` does not compile against —
 * `:core:testing`'s, for one.
 */
internal fun Project.configureDependencyResolutionCheck() {
    val graphs = configurations
        .matching { it.isCanBeResolved && it.name.endsWith("Classpath") }
        .map { it.name to it.incoming.resolutionResult.rootComponent }

    tasks.register("resolveAllDependencies") {
        group = "verification"
        description =
            "Resolves every classpath's dependency graph so a non-existent version fails."

        doLast {
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

private const val TEST_TIMEOUT_MINUTES = 10L
