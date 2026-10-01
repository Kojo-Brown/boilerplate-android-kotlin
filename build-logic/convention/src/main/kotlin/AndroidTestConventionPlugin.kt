import com.android.build.api.dsl.TestExtension
import com.kojo.boilerplate.buildlogic.BoilerplateBuild
import com.kojo.boilerplate.buildlogic.configureDependencyResolutionCheck
import com.kojo.boilerplate.buildlogic.configureDetekt
import com.kojo.boilerplate.buildlogic.configureKotlinJvmTarget
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * The single test-only module, `:benchmark`.
 *
 * A `com.android.test` project is neither an application nor a library: it builds an APK that is
 * installed beside the module named by `targetProjectPath` and instruments it, and it produces
 * nothing a user installs. That makes two of the five things the other convention plugins do
 * inapplicable rather than merely unnecessary:
 *
 * - [com.kojo.boilerplate.buildlogic.configureUnitTests] is absent because a test module has no
 *   `test` source set to configure. Its code lives in `src/main` and runs on a device, which is
 *   the whole point — a macrobenchmark measures a real process starting on real hardware.
 * - [com.kojo.boilerplate.buildlogic.sharedTestDependencies] is absent for the same reason: there
 *   is no `testImplementation` configuration here for JUnit 5, mockk or `runTest` to go on. The
 *   on-device stack is declared in the module's own build file, because it is the only module in
 *   this repository that has one.
 *
 * What it does keep is every gate: the JVM target, detekt, and the dependency-resolution check.
 * That last one is why the module declares the stock `debug` build type and does not filter it
 * out — see the build file.
 */
class AndroidTestConventionPlugin : Plugin<Project> {

    override fun apply(target: Project) = with(target) {
        with(pluginManager) {
            apply("com.android.test")
            apply("org.jetbrains.kotlin.android")
        }

        extensions.configure<TestExtension> {
            compileSdk = BoilerplateBuild.COMPILE_SDK
            defaultConfig {
                minSdk = BoilerplateBuild.MIN_SDK
                targetSdk = BoilerplateBuild.TARGET_SDK
                testInstrumentationRunner = BoilerplateBuild.TEST_RUNNER
            }
            compileOptions {
                sourceCompatibility = BoilerplateBuild.JAVA_VERSION
                targetCompatibility = BoilerplateBuild.JAVA_VERSION
            }
        }

        configureKotlinJvmTarget()
        configureDetekt()
        configureDependencyResolutionCheck()
    }
}
