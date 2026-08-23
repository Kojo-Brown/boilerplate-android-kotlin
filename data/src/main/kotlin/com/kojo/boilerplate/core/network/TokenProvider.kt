package com.kojo.boilerplate.core.network

interface TokenProvider {
    fun getAccessToken(): String?
    fun getRefreshToken(): String?
    fun updateTokens(accessToken: String, refreshToken: String)
    fun clearTokens()
}
