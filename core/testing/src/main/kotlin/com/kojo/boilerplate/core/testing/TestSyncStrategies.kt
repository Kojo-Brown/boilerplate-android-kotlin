package com.kojo.boilerplate.core.testing

import com.kojo.boilerplate.core.domain.repository.UserRepository
import com.kojo.boilerplate.core.domain.sync.CurrentUserSyncStrategy
import com.kojo.boilerplate.core.domain.sync.SyncMode
import com.kojo.boilerplate.core.domain.sync.SyncStrategy
import com.kojo.boilerplate.core.domain.sync.SyncStrategyFactory
import com.kojo.boilerplate.core.domain.sync.VisibleUsersSyncStrategy
import javax.inject.Provider

/**
 * The strategy map as `SyncStrategyModule` binds it, over a repository the test controls.
 *
 * A hand-written copy of a Dagger multibinding is a copy that can drift, so it is worth being
 * explicit about why this one is allowed to exist: the unit test source sets have no Hilt
 * runtime — there is no `hilt-android-testing` dependency and these tests run on a plain JVM
 * — so a component cannot be built here to ask it for the real map.
 *
 * It lives in `:core:testing` rather than beside the module it mirrors because two modules
 * need it — `:core:domain`'s own tests and `:feature:home`'s — and a `src/test` source set is
 * invisible from anywhere else.
 *
 * What stops the drift is `SyncStrategyModuleContractTest`, which reads the module's own
 * annotations and asserts that the bindings are exactly these classes under exactly these
 * keys. A strategy added to the module and not to this file fails there, with a message
 * pointing at this file.
 */
fun syncStrategyFactoryOver(repository: UserRepository): SyncStrategyFactory =
    SyncStrategyFactory(
        mapOf(
            SyncMode.VISIBLE_USERS to Provider<SyncStrategy> { VisibleUsersSyncStrategy(repository) },
            SyncMode.CURRENT_USER to Provider<SyncStrategy> { CurrentUserSyncStrategy(repository) },
        ),
    )
