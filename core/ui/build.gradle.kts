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

    // `FlashToggle` names `Icons.Default.FlashOn` and `FlashOff`, neither of which is in
    // the small default icon set. Declared here because this module's own source uses it,
    // which is the rule every other line in this list follows; both features that draw the
    // toggle already carried it for the copies this module replaced.
    implementation(libs.androidx.material.icons.extended)

    // `api` for the three that appear in this module's own public signatures: `UdfViewModel`
    // extends `ViewModel`, `AdaptiveNavigationScaffold` takes an `ImmutableList`, and
    // `ObserveAsEvents` is written against `Lifecycle`.
    api(libs.androidx.lifecycle.runtime.ktx)
    api(libs.androidx.lifecycle.viewmodel.compose)
    api(libs.kotlinx.collections.immutable)

    // `SharedElementTransition`'s constructor takes a `SharedTransitionScope` and an
    // `AnimatedVisibilityScope`, and `:app` is what constructs it — from the two scopes its own
    // `SharedTransitionLayout` and nav graph provide — so both types have to be on a consumer's
    // compile classpath. `api` for the same reason the three below it are.
    api(libs.androidx.compose.animation)

    implementation(libs.androidx.core.ktx)
    // `PredictiveBackHandler` and `BackEventCompat`, for `rememberPredictiveBackDismiss`.
    // `implementation` rather than `api`: the state object it returns reports the swipe edge as
    // an `Int` and the progress as a `Float`, so no activity type reaches this module's public
    // signatures and a caller needs nothing on its classpath to use it.
    implementation(libs.androidx.activity.compose)
    // `AdaptiveNavigationScaffold` and `useListDetailLayout` are this module's, and both are
    // called from `:app`, so the adaptive types they expose have to travel with them.
    api(libs.androidx.material3.adaptive.navigation.suite)
    api(libs.androidx.adaptive)
}
