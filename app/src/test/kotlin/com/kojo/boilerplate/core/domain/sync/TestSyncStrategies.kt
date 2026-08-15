package com.kojo.boilerplate.core.domain.sync

import com.kojo.boilerplate.core.data.repository.UserRepository
import javax.inject.Provider

/**
 * The strategy map as `SyncStrategyModule` binds it, over a repository the test controls.
 *
 * A hand-written copy of a Dagger multibinding is a copy that can drift, so it is worth being
 * explicit about why this one is allowed to exist: the unit test source set has no Hilt
 * runtime — there is no `hilt-android-testing` dependency and these tests run on a plain JVM
 * — so a component cannot be built here to ask it for the real map.
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
