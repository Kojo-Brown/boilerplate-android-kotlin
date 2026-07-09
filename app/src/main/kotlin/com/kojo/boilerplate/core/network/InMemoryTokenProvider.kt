package com.kojo.boilerplate.core.network

import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory token store used until DataStore persistence is wired in.
 * Replaced by DataStoreTokenProvider in the DataStore spec item.
 */
@Singleton
class InMemoryTokenProvider @Inject constructor() : TokenProvider {

    private val accessToken = AtomicReference<String?>(null)
    private val refreshToken = AtomicReference<String?>(null)

    override fun getAccessToken(): String? = accessToken.get()

    override fun getRefreshToken(): String? = refreshToken.get()

    override fun updateTokens(accessToken: String, refreshToken: String) {
        this.accessToken.set(accessToken)
        this.refreshToken.set(refreshToken)
    }

    override fun clearTokens() {
        accessToken.set(null)
        refreshToken.set(null)
    }
}
