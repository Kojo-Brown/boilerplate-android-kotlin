package com.kojo.boilerplate.core.database.dao

import com.kojo.boilerplate.core.database.entity.UserEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

class FakeUserDao(initialEntities: List<UserEntity> = emptyList()) : UserDao {

    private val _entities = MutableStateFlow(initialEntities)

    override fun observeAll(): Flow<List<UserEntity>> =
        _entities.map { list -> list.sortedBy { it.displayName } }

    override fun observeById(id: String): Flow<UserEntity?> =
        _entities.map { list -> list.firstOrNull { it.id == id } }

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
