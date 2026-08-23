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
 * ways: the `ForbiddenImport` detekt rule, which is scoped by a glob over the `core/domain`
 * path; `DomainLayerContractTest`, which reads the compiled constant pool; and this dependency
 * list. The glob is described rather than quoted because a block comment ends at the first star
 * followed by a slash, which is what a path glob is made of.
 */
dependencies {
    api(project(":core:common"))

    testImplementation(project(":core:testing"))
}
