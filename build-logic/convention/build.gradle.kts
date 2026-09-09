import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    `kotlin-dsl`
}

group = "com.kojo.boilerplate.buildlogic"

// 17 in both places, and both are required. The `kotlin-dsl` plugin fails the build when the
// Kotlin and Java targets disagree, and the AGP/KGP classes these plugins compile against are
// Java 17 class files.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

// `compileOnly`, not `implementation`: these plugins are already on the main build's script
// classpath — the root `build.gradle.kts` declares every one of them with `apply false` — and
// putting a second copy here would mean two AGP instances in one build. What is needed at this
// end is only their DSL types, at compile time.
//
// Only four are here, and the omissions are deliberate. The KSP and Hilt plugins are applied by
// id and never referenced by type, so nothing here needs to compile against them. Adding the
// Hilt one is not merely redundant, it breaks the build: `hilt-android-gradle-plugin` 2.57.2
// ships a Kotlin 2.x `.kotlin_module` that the compiler behind `kotlin-dsl` cannot read, and it
// fails this compilation with "Module was compiled with an incompatible version of Kotlin"
// before reaching any of this project's own source.
//
// The Compose one is here because `configureComposeCompilerReports` sets `metricsDestination`
// and `reportsDestination` on `ComposeCompilerGradlePluginExtension`, which is a type. Those two
// could equally be spelled as `-P plugin:androidx.compose.compiler.plugins.kotlin:...` free
// compiler arguments and then nothing would need to be added here — that spelling was rejected
// because it hard-codes the compiler plugin's internal id, which is exactly what the Gradle
// extension exists to stop a build script from depending on.
dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.compose.gradlePlugin)
    compileOnly(libs.detekt.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "boilerplate.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidApplicationCompose") {
            id = "boilerplate.android.application.compose"
            implementationClass = "AndroidApplicationComposeConventionPlugin"
        }
        register("androidLibrary") {
            id = "boilerplate.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidLibraryCompose") {
            id = "boilerplate.android.library.compose"
            implementationClass = "AndroidLibraryComposeConventionPlugin"
        }
        register("hilt") {
            id = "boilerplate.hilt"
            implementationClass = "HiltConventionPlugin"
        }
    }
}
