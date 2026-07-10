package com.kojo.boilerplate.core.data.repository

import com.kojo.boilerplate.core.common.safeCall
import com.kojo.boilerplate.core.data.model.User
import com.kojo.boilerplate.core.database.dao.UserDao
import com.kojo.boilerplate.core.database.entity.toDomain
import com.kojo.boilerplate.core.database.entity.toEntity
import com.kojo.boilerplate.core.network.api.UserApi
import com.kojo.boilerplate.core.network.model.toDomain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class UserRepositoryImpl @Inject constructor(
    private val userDao: UserDao,
    private val userApi: UserApi,
) : UserRepository {

    override fun getUsers(): Flow<List<User>> =
        userDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override fun getUser(id: String): Flow<User?> =
        userDao.observeById(id).map { it?.toDomain() }

    override suspend fun saveUser(user: User) {
        userDao.upsert(user.toEntity())
    }

    override suspend fun syncCurrentUser(): Result<User> = safeCall {
        val dto = userApi.getCurrentUser()
        val user = dto.toDomain()
        userDao.upsert(user.toEntity())
        user
    }

    override suspend fun syncUser(id: String): Result<User> = safeCall {
        val dto = userApi.getUser(id)
        val user = dto.toDomain()
        userDao.upsert(user.toEntity())
        user
    }
}
