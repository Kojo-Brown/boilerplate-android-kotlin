plugins {
    id("boilerplate.android.library.compose")
    id("boilerplate.hilt")
}

android {
    namespace = "com.kojo.boilerplate.feature.profile"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:domain"))
    // `ProfileViewModel` reads its own argument with `savedStateHandle.toRoute<Profile>()`, so
    // it needs the route contract — but not the navigation graph, which is `:app`'s.
    implementation(project(":core:navigation"))
    implementation(project(":core:ui"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    testImplementation(project(":core:testing"))
}
