import com.android.build.api.dsl.LibraryExtension
import com.kojo.boilerplate.buildlogic.configureCompose
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * A library module that contains Compose UI.
 *
 * Separate from `boilerplate.android.library` on purpose, and the separation is load-bearing
 * rather than tidiness: the Compose compiler plugin stamps `@StabilityInferred` onto **every**
 * class in a module it is applied to, UI or not. While this app was one module that made "no
 * androidx in the domain layer" impossible to state truthfully at the bytecode level, and
 * `DomainLayerContractTest` had to carry a named exemption for it. Applying Compose only where
 * there are composables is what deleted that exemption — so a module joining this convention
 * because it is convenient, rather than because it draws something, quietly costs that
 * guarantee.
 */
class AndroidLibraryComposeConventionPlugin : Plugin<Project> {

    override fun apply(target: Project) = with(target) {
        pluginManager.apply("boilerplate.android.library")
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

        extensions.configure<LibraryExtension> {
            buildFeatures.compose = true
        }

        configureCompose()
    }
}
