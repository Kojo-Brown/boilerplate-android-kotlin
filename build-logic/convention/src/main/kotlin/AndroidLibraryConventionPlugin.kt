import com.android.build.api.dsl.LibraryExtension
import com.kojo.boilerplate.buildlogic.BoilerplateBuild
import com.kojo.boilerplate.buildlogic.configureDependencyResolutionCheck
import com.kojo.boilerplate.buildlogic.configureDetekt
import com.kojo.boilerplate.buildlogic.configureKotlinJvmTarget
import com.kojo.boilerplate.buildlogic.configureUnitTests
import com.kojo.boilerplate.buildlogic.sharedTestDependencies
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Every library module in this repository. Compose is deliberately *not* part of it — see
 * `AndroidLibraryComposeConventionPlugin` for why that separation is the point of the split.
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {

    override fun apply(target: Project) = with(target) {
        with(pluginManager) {
            apply("com.android.library")
            apply("org.jetbrains.kotlin.android")
        }

        extensions.configure<LibraryExtension> {
            compileSdk = BoilerplateBuild.COMPILE_SDK
            defaultConfig {
                minSdk = BoilerplateBuild.MIN_SDK
                testInstrumentationRunner = BoilerplateBuild.TEST_RUNNER
                // A library has no `targetSdk`; the application module's value applies to the
                // whole APK. `testOptions.targetSdk` is where an instrumented test would set
                // its own, and this repo has no reason to differ from the app.
            }
            compileOptions {
                sourceCompatibility = BoilerplateBuild.JAVA_VERSION
                targetCompatibility = BoilerplateBuild.JAVA_VERSION
            }
        }

        configureKotlinJvmTarget()
        configureUnitTests()
        configureDetekt()
        configureDependencyResolutionCheck()
        sharedTestDependencies()
    }
}
