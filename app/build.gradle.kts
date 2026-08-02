// Imported rather than written as java.time.Duration inline: inside a Kotlin DSL build
// script `java` resolves to the JavaPluginExtension accessor, which shadows the package
// and fails with "Unresolved reference: time".
import java.time.Duration

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.detekt)
}

android {
    namespace = "com.kojo.boilerplate"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kojo.boilerplate"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "BASE_URL", "\"https://api.example.com/v1/\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            all { test ->
                test.useJUnitPlatform()
                // A deadlocked test should fail this task, not sit until the CI job's own
                // timeout kills the run and leaves no test report behind — which is exactly
                // what DataStoreTokenProviderTest did before it was fixed. Gradle's task
                // timeout covers both engines here; a JUnit 5 default timeout would not,
                // since the suite still runs JUnit 4 classes through the vintage engine.
                // Ten minutes is far above the suite's real runtime and only ever trips on
                // something genuinely stuck.
                test.timeout.set(Duration.ofMinutes(10))
            }
        }
    }
}

// The Room Gradle Plugin registers `room` on the project, not on the `android`
// extension, so this block has to sit outside `android { }`.
room {
    schemaDirectory("$projectDir/schemas")
}

detekt {
    // `detekt` on its own analyses src/*/java and src/*/kotlin across every source set,
    // which is what CLAUDE.md's gate invokes. The per-variant `detektMain`/`detektTest`
    // tasks the Android plugin would add need type resolution and a full compile first;
    // this repo gates on the compile task directly instead, so plain `detekt` is the
    // right granularity and stays fast.
    source.setFrom(files("src/main/kotlin", "src/test/kotlin", "src/androidTest/kotlin"))
    parallel = true
    // The bundled default ruleset stays active; config/detekt/detekt.yml only carries
    // the deltas, so a detekt upgrade brings its new rules in rather than silently
    // inheriting a frozen snapshot.
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    basePath = rootDir.absolutePath
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    // Detekt forks its own JVM analysis and defaults to the Gradle daemon's target,
    // which is 21 here. Pin it to the module's target so the two never disagree.
    jvmTarget = JavaVersion.VERSION_17.toString()
    reports {
        html.required.set(true)
        sarif.required.set(true)
        xml.required.set(false)
        txt.required.set(false)
        md.required.set(false)
    }
}

// Phase 0 item 4. `scripts/verify-apk.sh` asserts that the assembled APK carries the
// package, versionCode, versionName, minSdk and targetSdk it is supposed to. Those
// expectations are written out of the build DSL here rather than copied into the script,
// so there is exactly one source of truth: a change to `defaultConfig` moves the
// expectation with it, and the check keeps verifying that AGP actually propagated the
// declared values into the artifact instead of comparing two hand-maintained copies.
//
// The values are read at configuration time and captured as task inputs, so the task
// stays configuration-cache compatible and re-runs when any of them changes.
val debugApkIdentityFile = layout.buildDirectory.file("apk-identity/debug.properties")

tasks.register("writeDebugApkIdentity") {
    description = "Writes the expected identity of the debug APK for scripts/verify-apk.sh."
    group = "verification"

    val debugBuildType = android.buildTypes.getByName("debug")
    // The debug variant's applicationId and versionName are the defaults plus whatever
    // suffix the build type declares. Neither is set today; reading them anyway means
    // adding one later does not silently turn this check into a false failure.
    val applicationId =
        requireNotNull(android.defaultConfig.applicationId) {
            "android.defaultConfig.applicationId is not set"
        } + (debugBuildType.applicationIdSuffix ?: "")
    val versionName =
        requireNotNull(android.defaultConfig.versionName) {
            "android.defaultConfig.versionName is not set"
        } + (debugBuildType.versionNameSuffix ?: "")
    val versionCode =
        requireNotNull(android.defaultConfig.versionCode) {
            "android.defaultConfig.versionCode is not set"
        }
    val minSdk =
        requireNotNull(android.defaultConfig.minSdk) { "android.defaultConfig.minSdk is not set" }
    val targetSdk =
        requireNotNull(android.defaultConfig.targetSdk) {
            "android.defaultConfig.targetSdk is not set"
        }

    inputs.property("applicationId", applicationId)
    inputs.property("versionName", versionName)
    inputs.property("versionCode", versionCode)
    inputs.property("minSdk", minSdk)
    inputs.property("targetSdk", targetSdk)
    outputs.file(debugApkIdentityFile)

    doLast {
        val destination = debugApkIdentityFile.get().asFile
        destination.parentFile.mkdirs()
        destination.writeText(
            """
            # Generated by :app:writeDebugApkIdentity — do not edit.
            applicationId=$applicationId
            versionCode=$versionCode
            versionName=$versionName
            minSdk=$minSdk
            targetSdk=$targetSdk
            """.trimIndent() + "\n",
        )
    }
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.material3.adaptive.navigation.suite)
    implementation(libs.androidx.adaptive)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Kotlin Serialization
    implementation(libs.kotlinx.serialization.json)

    // CameraX
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // MLKit
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.mlkit.text.recognition)

    // Google Credential Manager
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)

    // Retrofit + OkHttp
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)

    // Debug
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.vintage.engine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

/**
 * Proves that every version declared in `gradle/libs.versions.toml` actually exists on a
 * configured repository, by resolving the dependency *graph* of every classpath the app
 * builds against and reporting every module that could not be resolved.
 *
 * `./gradlew :app:dependencies` is not a substitute: it prints a `FAILED` marker next to an
 * unresolvable module and still exits 0, so it cannot gate CI.
 *
 * This walks metadata only and deliberately does not download artifacts. A version that does
 * not exist fails during metadata resolution, which is the question this task answers, and
 * skipping the artifact fetch keeps the job to a couple of minutes instead of pulling every
 * variant's full graph — MLKit and CameraX alone are hundreds of megabytes. The trade-off is
 * that a published-but-empty module (POM present, AAR missing) would slip through here; the
 * compile and assemble gates in Phase 0 items 2 and 4 are what cover that.
 */
tasks.register("resolveAllDependencies") {
    group = "verification"
    description = "Resolves every classpath's dependency graph so a non-existent version fails the build."

    val graphs = configurations
        .matching { it.isCanBeResolved && it.name.endsWith("Classpath") }
        .map { it.name to it.incoming.resolutionResult.rootComponent }

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
                            failures += "$name -> ${dependency.requested.displayName}: ${dependency.failure.message}"
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

        logger.lifecycle("Resolved ${graphs.size} configurations, $modules module nodes, 0 failures")
    }
}
