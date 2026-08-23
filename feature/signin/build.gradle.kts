plugins {
    id("boilerplate.android.library.compose")
    id("boilerplate.hilt")
}

android {
    namespace = "com.kojo.boilerplate.feature.signin"
}

dependencies {
    implementation(project(":core:auth"))
    implementation(project(":core:common"))
    implementation(project(":core:ui"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.material.icons.extended)
    // The screen distinguishes a cancelled sign-in from a failed one, which means naming
    // `GetCredentialCancellationException`. The repository behind it is `:core:auth`'s.
    implementation(libs.androidx.credentials)

    testImplementation(project(":core:testing"))
}
