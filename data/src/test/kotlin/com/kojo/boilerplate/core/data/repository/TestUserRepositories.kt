package com.kojo.boilerplate.core.data.repository

import com.kojo.boilerplate.core.database.dao.UserDao
import com.kojo.boilerplate.core.domain.sync.IdempotencyKeyGenerator
import com.kojo.boilerplate.core.domain.sync.conflict.ConflictResolver
import com.kojo.boilerplate.core.domain.sync.conflict.MergeConflictResolver
import com.kojo.boilerplate.core.network.api.UserApi
import kotlinx.coroutines.CoroutineDispatcher

/**
 * `UserRepositoryImpl` assembled the way the DI graph assembles it, for the suites that care
 * about what the repository does rather than about how it is wired.
 *
 * It exists because the constructor grew two collaborators when the push path landed — a
 * [ResolvingUserWriter] in place of the bare `ConflictResolver`, and an
 * [IdempotencyKeyGenerator] — and nine call sites across six files each said the same thing
 * about both. The parameters that matter to a given suite stay explicit at its own call site;
 * the ones that do not are defaults here.
 *
 * @param resolver the conflict policy under test. Defaulted to [MergeConflictResolver] because
 *   that is what `ConflictResolverModule` binds, so a suite that does not name a policy is
 *   asserting about the one the app ships.
 */
internal fun userRepositoryOver(
    dao: UserDao,
    api: UserApi,
    dispatcher: CoroutineDispatcher,
    resolver: ConflictResolver = MergeConflictResolver(),
    idempotencyKeys: IdempotencyKeyGenerator = SequentialIdempotencyKeyGenerator(),
): UserRepositoryImpl = UserRepositoryImpl(
    userDao = dao,
    userApi = api,
    writer = ResolvingUserWriter(dao, resolver),
    idempotencyKeys = idempotencyKeys,
    ioDispatcher = dispatcher,
)

/**
 * Idempotency keys a test can name: `key-1`, `key-2`, and so on in the order they were minted.
 *
 * The real generator is `UUID.randomUUID`, deliberately, and that is exactly what makes it
 * useless in an assertion — every key it produces is different and none of them is predictable,
 * so "the retry sent the same key the first attempt did" cannot be written against it. This is
 * why `IdempotencyKeyGenerator` is an interface at all.
 *
 * Not thread-safe, and not required to be: every test using it drives a single test dispatcher.
 */
internal class SequentialIdempotencyKeyGenerator : IdempotencyKeyGenerator {

    /** How many keys have been handed out, which is also the number in the newest one. */
    var minted: Int = 0
        private set

    override fun newKey(): String {
        minted++
        return "key-$minted"
    }
}
