// `build-logic` is an included build rather than `buildSrc`, so editing a convention plugin
// invalidates only the plugin's own compilation instead of every task in the main build.
pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "BoilerplateAndroidKotlin"

// The module graph. What may depend on what is not expressed here — Gradle has no notion of a
// layer — so it is asserted by the `checkModuleDependencies` task in the root build file, which
// CI runs before anything else.
include(":app")

// The macrobenchmark module. A `com.android.test` project rather than a source set inside `:app`,
// because a macrobenchmark starts the app under test as a separate process and measures it from
// outside: the measuring code cannot be in the process being measured. It ships in no release
// artifact and nothing depends on it. See docs/baseline-profiles.md.
include(":benchmark")

include(":core:auth")
include(":core:common")
// The typed-preferences schema and nothing else: one `.proto` file, protoc, and the lite
// runtime. Its own build file says why it is not part of `:data`.
include(":core:datastore-proto")
include(":core:domain")
include(":core:navigation")
include(":core:paging")
include(":core:testing")
include(":core:ui")

include(":data")

include(":feature:home")
include(":feature:profile")
include(":feature:scanner")
include(":feature:signin")
include(":feature:textrecognition")
