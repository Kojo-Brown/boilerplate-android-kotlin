plugins {
    id("boilerplate.android.library.compose")
    // Paparazzi, and in this module alone. The gate is a design-system gate: every composable
    // with a golden image is one of the widgets below, drawn from the palette and the type scale
    // this module owns, so this is the one place where a pixel diff is a statement about shared
    // code rather than about one screen. A feature module joining it is a decision to make when
    // a feature has UI worth pinning that is not already pinned here — not a convention plugin
    // waiting to be applied, which is why this is an `alias` here rather than a sixth
    // `boilerplate.*` plugin in build-logic. See docs/screenshot-testing.md.
    alias(libs.plugins.paparazzi)
}

android {
    namespace = "com.kojo.boilerplate.core.ui"
}

/*
 * The one piece of build configuration in this file that is neither a namespace nor a dependency,
 * which CLAUDE.md asks to be justified rather than inherited.
 *
 * Gradle forks unit tests into their own JVM with a 512 MB default heap — `org.gradle.jvmargs`
 * in gradle.properties sizes the *daemon*, not this. That is ample for the other fourteen
 * modules and not for this one: a Paparazzi test loads layoutlib, the whole API 34 framework
 * resource repository and the platform font set into the test JVM, then renders at this device's
 * 440 dpi, where a full-screen bitmap alone is around 10 MB. The failure mode is an
 * `OutOfMemoryError` from inside the renderer rather than a failed assertion, so it reads as a
 * broken gate rather than as a limit worth raising.
 *
 * It belongs here rather than in `configureUnitTests()` because the reason is Paparazzi and
 * Paparazzi is applied here. A runner has 16 GB and the daemon is capped at 4 GB, so this and
 * the Kotlin compile daemon fit alongside each other with room to spare.
 */
tasks.withType<Test>().configureEach {
    maxHeapSize = "2g"
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

    // For `MainDispatcherExtension`. `UdfViewModelEffectsTest` drives `emitEffect`, which posts
    // through `viewModelScope` — so the test has to install a `Dispatchers.Main` before the base
    // class can deliver anything at all.
    testImplementation(project(":core:testing"))
}
