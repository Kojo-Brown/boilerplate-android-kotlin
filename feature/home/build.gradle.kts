plugins {
    id("boilerplate.android.library.compose")
    id("boilerplate.hilt")
}

android {
    namespace = "com.kojo.boilerplate.feature.home"
}

/*
 * The home list, and the two-pane layout built on top of it.
 *
 * It does not depend on `:feature:profile`, even though the two-pane layout shows a profile:
 * `HomeTwoPaneScreen` takes the detail pane as a slot and `:app` supplies it. Features are
 * siblings, and one importing another is how a module graph collapses back into a single module
 * that happens to have directories — `checkModuleDependencies` in the root build file is what
 * stops it happening again.
 */
dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:domain"))
    // The paged user contract. `:core:paging` exposes `PagingData` and `User` as `api`, so
    // naming `Flow<PagingData<HomeItem>>` in `HomeUiState` needs nothing else on the classpath.
    implementation(project(":core:paging"))
    implementation(project(":core:ui"))

    implementation(libs.androidx.core.ktx)
    // The Compose half of Paging, and only this module needs it: `:data` builds the `Pager` and
    // `:core:paging` declares the contract, but `collectAsLazyPagingItems` belongs wherever the
    // list is actually composed.
    implementation(libs.androidx.paging.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.kotlinx.collections.immutable)

    testImplementation(project(":core:testing"))
}
