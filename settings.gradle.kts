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

include(":core:auth")
include(":core:common")
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
