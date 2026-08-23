plugins {
    id("boilerplate.android.library")
}

android {
    namespace = "com.kojo.boilerplate.core.testing"
}

/*
 * Test doubles, in a `main` source set on purpose.
 *
 * A fake in `src/test` is invisible to every other module — a test source set is not published
 * to consumers — and the alternatives are copying `FakeUserRepository` into five modules or
 * turning on `testFixtures` in each of them. This is the same shape Now in Android uses, and it
 * comes with one hazard: everything here is a real, shippable class. `checkModuleDependencies`
 * fails the build if any module depends on this one from anything but a test configuration, so
 * a fake cannot reach an APK by a slip of `implementation` for `testImplementation`.
 *
 * The dependencies below are `api` because a fake's whole job is to be an implementation of an
 * interface the consumer also names.
 */
dependencies {
    api(project(":core:auth"))
    api(project(":core:common"))
    api(project(":core:domain"))

    // The JUnit rule and extension are part of this module's surface, so their annotations have
    // to be on the consumer's compile classpath too.
    api(libs.junit)
    api(libs.junit.jupiter.api)
    api(libs.kotlinx.coroutines.test)
}
