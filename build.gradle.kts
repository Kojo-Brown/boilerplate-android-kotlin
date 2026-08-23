import org.gradle.api.artifacts.ProjectDependency

// Every plugin the convention plugins in `build-logic` apply by id has to be on the build
// script classpath for them to find it, and `apply false` here is what puts it there. The
// version lives in the catalog either way, so this list and `build-logic/convention` cannot
// disagree about one.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.detekt) apply false
}

/**
 * What each module is allowed to depend on.
 *
 * Gradle has no notion of a layer: `include(":core:domain")` and `include(":feature:home")` are
 * the same kind of thing to it, and `implementation(project(":feature:profile"))` inside another
 * feature compiles perfectly well. The rules below are the architecture, and until something
 * checks them they are a convention that holds only while everyone remembers it — which, in a
 * repository that had `feature/home` importing `feature/profile` and `core/datastore` importing
 * `ui/theme` before this split, is not a safe assumption.
 *
 * The shape:
 *
 * - `:core:*` modules depend on other `:core:*` modules and nothing else. They cannot see a
 *   feature, they cannot see `:data`, and they cannot see `:app`.
 * - `:data` implements what `:core:domain` declares. Nothing depends on `:data` except `:app`,
 *   so an implementation detail — Room, Retrofit, DataStore, Credential Manager — cannot leak
 *   into a screen.
 * - `:feature:*` modules are siblings and may never depend on each other. A feature that needs
 *   another feature's UI takes it as a slot and lets `:app` supply it, which is how
 *   `HomeTwoPaneScreen` gets its detail pane.
 * - `:app` depends on everything and is depended on by nothing. It is where the graph is
 *   assembled and the only place that knows the full set of screens.
 *
 * A module missing from this map fails the check rather than defaulting to permissive: a new
 * module is a decision about where it sits, and this is where that decision is written down.
 */
val moduleDependencyRules: Map<String, Set<String>> = mapOf(
    ":core:common" to emptySet(),
    ":core:navigation" to emptySet(),
    // `:core:testing` appears in these two because their own unit tests use its fakes. It is a
    // back-edge — `:core:testing` depends on both — and Gradle is fine with it: a module's test
    // source set is compiled after every module's `main`, so nothing is circular. The
    // test-only rule below is what keeps the back-edge from reaching a shipped variant.
    ":core:auth" to setOf(":core:common", ":core:testing"),
    ":core:domain" to setOf(":core:common", ":core:testing"),
    ":core:ui" to setOf(":core:common"),
    ":core:testing" to setOf(":core:auth", ":core:common", ":core:domain"),
    ":data" to setOf(":core:auth", ":core:common", ":core:domain", ":core:testing"),
    ":feature:home" to setOf(
        ":core:common",
        ":core:domain",
        ":core:navigation",
        ":core:testing",
        ":core:ui",
    ),
    ":feature:profile" to setOf(
        ":core:common",
        ":core:domain",
        ":core:navigation",
        ":core:testing",
        ":core:ui",
    ),
    ":feature:scanner" to setOf(":core:common", ":core:testing", ":core:ui"),
    ":feature:signin" to setOf(":core:auth", ":core:common", ":core:testing", ":core:ui"),
    ":feature:textrecognition" to setOf(":core:common", ":core:testing", ":core:ui"),
    ":app" to setOf(
        ":core:auth",
        ":core:common",
        ":core:domain",
        ":core:navigation",
        ":core:ui",
        ":data",
        ":feature:home",
        ":feature:profile",
        ":feature:scanner",
        ":feature:signin",
        ":feature:textrecognition",
    ),
)

/**
 * `:core:testing` ships hand-written fakes in its *main* source set so that other modules can
 * use them from their tests. That makes it the one module whose classes must never reach a
 * shipped variant, so it is allowed only from a test configuration.
 */
val testOnlyModules = setOf(":core:testing")

// Snapshotted after every project has been evaluated, so the task body reads a plain data
// structure rather than reaching across the project tree while it runs.
//
// Two filters, both learned the hard way from a run that reported 85 violations and no real
// ones:
//
//  - Only *declarable* configurations are read — `implementation`, `api`, `testImplementation`
//    and their kin. A resolvable one such as `debugUnitTestCompileClasspath` carries whatever
//    AGP put there, including the tested variant expressed as a dependency on the module
//    itself, and none of that is a statement anybody wrote in a build file. Reading only what
//    was declared is also what keeps this task from observing a resolution result, which is a
//    mistake with consequences — see `configureDependencyResolutionCheck`.
//  - `include(":core:auth")` implicitly creates `:core` as a container project with no build
//    file and no plugins. It is scaffolding, not a module, and it has no place in a rule map.
val declaredModuleEdges = mutableMapOf<String, MutableSet<Pair<String, String>>>()

gradle.projectsEvaluated {
    subprojects
        .filter { it.file("build.gradle.kts").exists() }
        .forEach { module ->
            val edges = declaredModuleEdges.getOrPut(module.path) { mutableSetOf() }
            module.configurations
                .matching { !it.isCanBeResolved && !it.isCanBeConsumed }
                .forEach { configuration ->
                    configuration.dependencies
                        .filterIsInstance<ProjectDependency>()
                        .map { it.dependencyProject.path }
                        .filter { it != module.path }
                        .forEach { edges += configuration.name to it }
                }
        }
}

tasks.register("checkModuleDependencies") {
    group = "verification"
    description = "Fails if a module depends on one the architecture does not allow it to."

    doLast {
        val violations = mutableListOf<String>()

        val undeclared = declaredModuleEdges.keys - moduleDependencyRules.keys
        undeclared.sorted().forEach { module ->
            violations += "$module has no entry in moduleDependencyRules — decide what it may " +
                "depend on and add it to build.gradle.kts"
        }

        val missing = moduleDependencyRules.keys - declaredModuleEdges.keys
        missing.sorted().forEach { module ->
            violations += "$module has a rule but is not in settings.gradle.kts — remove the " +
                "rule or include the module"
        }

        declaredModuleEdges.forEach { (module, edges) ->
            val allowed = moduleDependencyRules[module] ?: return@forEach
            edges.sortedBy { "${it.second}/${it.first}" }.forEach { (configuration, dependency) ->
                if (dependency !in allowed) {
                    violations += "$module must not depend on $dependency " +
                        "(declared as $configuration). Allowed: " +
                        allowed.sorted().joinToString(", ").ifEmpty { "nothing" }
                }
                if (dependency in testOnlyModules && !configuration.isTestConfiguration()) {
                    violations += "$module depends on $dependency from $configuration, which " +
                        "ships it. $dependency is test-only: use testImplementation or " +
                        "androidTestImplementation"
                }
            }
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                "${violations.size} module dependency rule violation(s):\n" +
                    violations.joinToString("\n") { "  - $it" } +
                    "\n\nThe rules are in build.gradle.kts and are documented there. If a rule " +
                    "is genuinely wrong, change it deliberately — that is a change to the " +
                    "architecture, not to a lint setting.",
            )
        }

        logger.lifecycle(
            "Checked ${declaredModuleEdges.size} modules, " +
                "${declaredModuleEdges.values.sumOf { it.size }} project dependencies, " +
                "0 violations",
        )
    }
}

/**
 * A configuration whose contents never reach a shipped variant. Matched by prefix because AGP
 * creates one per variant — `testDebugImplementation`, `androidTestDebugRuntimeOnly` and so on —
 * and every one of them is test-scoped.
 */
fun String.isTestConfiguration(): Boolean =
    startsWith("test") || startsWith("androidTest")
