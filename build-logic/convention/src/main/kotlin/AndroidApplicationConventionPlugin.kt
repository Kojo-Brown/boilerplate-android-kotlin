import com.android.build.api.dsl.ApplicationExtension
import com.kojo.boilerplate.buildlogic.BoilerplateBuild
import com.kojo.boilerplate.buildlogic.configureDependencyResolutionCheck
import com.kojo.boilerplate.buildlogic.configureDetekt
import com.kojo.boilerplate.buildlogic.configureKotlinJvmTarget
import com.kojo.boilerplate.buildlogic.configureUnitTests
import com.kojo.boilerplate.buildlogic.sharedAndroidTestDependencies
import com.kojo.boilerplate.buildlogic.sharedTestDependencies
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** The single application module. */
class AndroidApplicationConventionPlugin : Plugin<Project> {

    override fun apply(target: Project) = with(target) {
        with(pluginManager) {
            apply("com.android.application")
            apply("org.jetbrains.kotlin.android")
        }

        extensions.configure<ApplicationExtension> {
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
            // See BoilerplateBuild.DUPLICATE_PACKAGING_RESOURCES: two jars on one APK
            // classpath carrying the same licence file is a build failure in AGP, not a
            // warning, and the androidTest APKs are where it lands.
            packaging {
                resources {
                    excludes += BoilerplateBuild.DUPLICATE_PACKAGING_RESOURCES
                }
            }
        }

        configureKotlinJvmTarget()
        configureUnitTests()
        configureDetekt()
        configureDependencyResolutionCheck()
        sharedTestDependencies()
        sharedAndroidTestDependencies()
    }
}
