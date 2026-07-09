package com.kojo.boilerplate.core.network.api

import com.kojo.boilerplate.core.network.model.LoginRequest
import com.kojo.boilerplate.core.network.model.RefreshTokenRequest
import com.kojo.boilerplate.core.network.model.TokenResponse
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.Header
import retrofit2.http.POST

interface AuthApi {

    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): TokenResponse

    @POST("auth/refresh")
    suspend fun refreshToken(@Body request: RefreshTokenRequest): TokenResponse

    @DELETE("auth/logout")
    suspend fun logout(@Header("Authorization") bearerToken: String)
}
