plugins {
    id("boilerplate.android.library")
}

android {
    namespace = "com.kojo.boilerplate.core.paging"
}

/*
 * The paged half of the user contract: one interface, and the `PagingConfig` behind it.
 *
 * It is a module of its own for a reason that is about `:core:domain` rather than about paging.
 * A paged read is `Flow<PagingData<User>>` and there is no honest way to express it without
 * `PagingData` — hiding it behind a hand-rolled `Page<T>` would mean reimplementing invalidation,
 * placeholders and load state, which is the whole of what Paging 3 is. `PagingData` is an
 * `androidx` type, and `:core:domain` is held to carrying no `android`/`androidx` reference at
 * all: the `ForbiddenImport` detekt rule catches the import, `DomainLayerContractTest` catches
 * the reference even without one, and the module's own dependency list leaves nothing to import.
 * So the contract sits one module out, where `:data` can implement it and a feature can consume
 * it, and the domain layer stays a plain-JVM layer.
 *
 * The Hilt convention is deliberately absent. Nothing here is injected or binds anything — the
 * binding lives in `:data`, next to the implementation — and applying it would put Dagger on the
 * compile classpath of a module whose entire content is one interface.
 */
dependencies {
    // `api`, not `implementation`, for both: `PagedUserRepository.users()` returns
    // `Flow<PagingData<User>>`, so `PagingData` and `User` are this module's public surface and
    // every consumer needs them on its own compile classpath to name the type at all.
    api(project(":core:domain"))
    api(libs.androidx.paging.common)
}
