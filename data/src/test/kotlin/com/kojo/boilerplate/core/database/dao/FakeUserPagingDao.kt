package com.kojo.boilerplate.core.database.dao

import androidx.paging.PagingSource
import com.kojo.boilerplate.core.database.entity.UserEntity
import com.kojo.boilerplate.core.database.entity.UserPageKeyEntity

/**
 * An in-memory [UserPagingDao].
 *
 * It extends the DAO rather than implementing an interface, for the reason `FakeUserDao` does:
 * [UserPagingDao.commitPage] is inherited rather than reimplemented, so the read-resolve-write
 * these tests exercise is the same code Room runs and only the storage underneath it is fake.
 *
 * What is *not* faithful is the transaction. `@Transaction` means nothing here, so this cannot
 * demonstrate that a page lands atomically — that property belongs to Room and is asserted
 * against a real database under `androidTest`.
 *
 * [pagingSource] throws rather than returning a fake. A `RemoteMediator` never reads the
 * `PagingSource` — Paging owns it, and the mediator only ever writes — so a test that reached
 * for one here would be testing something the mediator does not do.
 */
class FakeUserPagingDao(
    initialUsers: List<UserEntity> = emptyList(),
    initialPageKey: UserPageKeyEntity? = null,
) : UserPagingDao() {

    private val users = initialUsers.toMutableList()
    private var key: UserPageKeyEntity? = initialPageKey

    /** Every user row currently held, in insertion order. */
    val storedUsers: List<UserEntity> get() = users.toList()

    /** The pagination cursor currently held, or `null` if no page has been committed. */
    val storedPageKey: UserPageKeyEntity? get() = key

    override fun pagingSource(): PagingSource<Int, UserEntity> =
        error("A RemoteMediator never reads the PagingSource; Paging owns it.")

    override suspend fun pageKey(): UserPageKeyEntity? = key

    override suspend fun findUser(id: String): UserEntity? = users.firstOrNull { it.id == id }

    override suspend fun upsertUser(entity: UserEntity) {
        val index = users.indexOfFirst { it.id == entity.id }
        if (index >= 0) users[index] = entity else users += entity
    }

    override suspend fun upsertPageKey(key: UserPageKeyEntity) {
        this.key = key
    }
}
