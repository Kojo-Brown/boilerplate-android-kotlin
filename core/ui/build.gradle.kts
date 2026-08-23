plugins {
    id("boilerplate.android.library.compose")
}

android {
    namespace = "com.kojo.boilerplate.core.ui"
}

/*
 * The design system: the theme, the shared widgets, the adaptive scaffold, and the
 * `UdfViewModel`/`UiState`/`ObserveAsEvents` vocabulary every screen is written in.
 *
 * No Hilt. Nothing here is injected — `UdfViewModel` is a base class, not a binding — and a
 * module that declares no `@Module` has no reason to run the processor.
 */
dependencies {
    api(project(":core:common"))

    // `api` for the three that appear in this module's own public signatures: `UdfViewModel`
    // extends `ViewModel`, `AdaptiveNavigationScaffold` takes an `ImmutableList`, and
    // `ObserveAsEvents` is written against `Lifecycle`.
    api(libs.androidx.lifecycle.runtime.ktx)
    api(libs.androidx.lifecycle.viewmodel.compose)
    api(libs.kotlinx.collections.immutable)

    implementation(libs.androidx.core.ktx)
    // `AdaptiveNavigationScaffold` and `useListDetailLayout` are this module's, and both are
    // called from `:app`, so the adaptive types they expose have to travel with them.
    api(libs.androidx.material3.adaptive.navigation.suite)
    api(libs.androidx.adaptive)
}
