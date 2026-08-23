plugins {
    id("boilerplate.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.kojo.boilerplate.core.navigation"
}

/*
 * One file: the route contract. It is its own module rather than part of `:core:ui` because a
 * route is not a widget, and rather than part of `:app` because a feature has to be able to read
 * its own arguments — `ProfileViewModel` calls `savedStateHandle.toRoute<AppDestination.Profile>()`
 * — and a feature depending on `:app` would invert the whole graph.
 *
 * Knowing the *shape* of every route is not the same as knowing what draws them: nothing here
 * references a screen, and the composable that renders each route is wired up in `:app`.
 */
dependencies {
    // `api`, because the generated serializer is part of the public surface of every route:
    // `composable<AppDestination.Home>` and `toRoute<AppDestination.Profile>()` both resolve it
    // at the call site, in another module.
    api(libs.kotlinx.serialization.json)
}
