package com.kojo.boilerplate.core.database.dao

import com.kojo.boilerplate.core.database.entity.UserEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * An in-memory [UserDao].
 *
 * It extends the DAO rather than implementing an interface, which means it inherits
 * [UserDao.upsertResolving] instead of reimplementing it — so the read-modify-write these
 * tests exercise is the same code Room runs, and only the storage underneath it is fake. What
 * is *not* faithful is the transaction: `@Transaction` means nothing here, so this fake cannot
 * demonstrate that concurrent resolutions of one id serialise. That property belongs to Room
 * and is asserted in `UserDaoTest`, on a real database, under `androidTest`.
 */
class FakeUserDao(initialEntities: List<UserEntity> = emptyList()) : UserDao() {

    private val _entities = MutableStateFlow(initialEntities)

    /** Every entity currently held, in insertion order. */
    val entities: List<UserEntity> get() = _entities.value

    override fun observeAll(): Flow<List<UserEntity>> =
        _entities.map { list -> list.sortedBy { it.displayName } }

    override fun observeById(id: String): Flow<UserEntity?> =
        _entities.map { list -> list.firstOrNull { it.id == id } }

    override suspend fun findById(id: String): UserEntity? =
        _entities.value.firstOrNull { it.id == id }

    override suspend fun upsert(entity: UserEntity) {
        _entities.update { current ->
            val index = current.indexOfFirst { it.id == entity.id }
            if (index >= 0) {
                current.toMutableList().also { it[index] = entity }
            } else {
                current + entity
            }
        }
    }

    override suspend fun delete(entity: UserEntity) {
        _entities.update { current -> current.filter { it.id != entity.id } }
    }
}
