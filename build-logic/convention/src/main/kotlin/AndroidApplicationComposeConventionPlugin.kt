import com.android.build.api.dsl.ApplicationExtension
import com.kojo.boilerplate.buildlogic.configureCompose
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** The application module, which also hosts composables (`MainActivity`, `AppNavHost`). */
class AndroidApplicationComposeConventionPlugin : Plugin<Project> {

    override fun apply(target: Project) = with(target) {
        pluginManager.apply("boilerplate.android.application")
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

        extensions.configure<ApplicationExtension> {
            buildFeatures.compose = true
        }

        configureCompose()
    }
}
