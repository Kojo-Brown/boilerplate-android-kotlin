plugins {
    id("boilerplate.android.library")
    id("boilerplate.hilt")
}

android {
    namespace = "com.kojo.boilerplate.core.auth"
}

/*
 * Sign-in, as an abstraction a feature can depend on plus the Credential Manager implementation
 * behind it.
 *
 * It is a `:core:` module rather than part of `:data` because `GoogleAuthRepository.signIn` takes
 * an `android.content.Context` — Credential Manager genuinely needs an Activity — and
 * `:core:domain` forbids the framework outright. `docs/solid.md` finding 2 is about exactly that
 * parameter; until it is inverted behind an interface this app owns, the abstraction cannot live
 * in the domain layer, and pretending otherwise would mean weakening the check that says so.
 */
dependencies {
    api(project(":core:common"))

    // `@Immutable` on `GoogleUser`. The Compose *compiler* is not applied to this module — only
    // the annotation is used — and that is why the annotation has to be explicit: a value class
    // crossing from a module Compose did not compile is unstable to Compose unless it says
    // otherwise. `StabilityContractTest` is what notices when one does not.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.runtime)

    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)

    testImplementation(project(":core:testing"))
}
