import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension

/**
 * Hilt, for any module that declares a binding.
 *
 * The Gradle plugin and the KSP processor both have to run in the module that declares an
 * `@Module`, not only in `:app`: `@InstallIn` is discovered through the `@AggregatedDeps`
 * metadata the processor generates *next to the module it processes*, and the application's
 * component is assembled from whatever it finds on the runtime classpath. A library that
 * declares a binding without the processor compiles cleanly and contributes nothing, which is
 * the failure this convention exists to make impossible to have by omission.
 */
class HiltConventionPlugin : Plugin<Project> {

    // A block body, not an expression body: the last statement here is `dependencies.apply`,
    // which evaluates to a `DependencyHandler`, and `Plugin.apply` returns `Unit`.
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("com.google.devtools.ksp")
                apply("com.google.dagger.hilt.android")
            }

            val libs = extensions
                .getByType(VersionCatalogsExtension::class.java)
                .named("libs")

            dependencies.apply {
                add("implementation", libs.findLibrary("hilt-android").get())
                add("ksp", libs.findLibrary("hilt-android-compiler").get())
            }
        }
    }
}
