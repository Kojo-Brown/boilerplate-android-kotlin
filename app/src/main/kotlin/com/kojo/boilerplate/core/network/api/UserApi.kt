package com.kojo.boilerplate.core.network.api

import com.kojo.boilerplate.core.network.model.UserDto
import retrofit2.http.GET
import retrofit2.http.Path

interface UserApi {

    @GET("users/me")
    suspend fun getCurrentUser(): UserDto

    @GET("users/{id}")
    suspend fun getUser(@Path("id") id: String): UserDto
}
