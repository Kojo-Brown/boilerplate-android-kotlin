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
    implementation(project(":core:ui"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.kotlinx.collections.immutable)

    testImplementation(project(":core:testing"))
}
