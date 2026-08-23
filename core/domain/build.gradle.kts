plugins {
    id("boilerplate.android.library")
    id("boilerplate.hilt")
}

android {
    namespace = "com.kojo.boilerplate.core.domain"
}

/*
 * The Compose plugin is deliberately absent, and that absence is checked.
 *
 * While this was one module, the Compose compiler stamped `@StabilityInferred` onto every class
 * it compiled — use cases and domain models included — so "no androidx in the domain layer" was
 * not literally true at the bytecode level, and `DomainLayerContractTest` carried a named
 * exemption saying so. Splitting this out is what removed the annotation and let the exemption
 * go: the test now asserts *no* `android/` or `androidx/` reference at all, with nothing
 * forgiven. Applying `boilerplate.android.library.compose` here would put it straight back.
 *
 * There is no Android dependency below for the same reason, and the layer is held to it three
 * ways: the `ForbiddenImport` detekt rule scoped to `**​/core/domain/**`, `DomainLayerContractTest`
 * reading the compiled constant pool, and this dependency list.
 */
dependencies {
    api(project(":core:common"))

    testImplementation(project(":core:testing"))
}
