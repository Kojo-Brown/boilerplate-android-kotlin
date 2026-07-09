package com.kojo.boilerplate.core.network

import com.kojo.boilerplate.core.network.api.AuthApi
import com.kojo.boilerplate.core.network.model.RefreshTokenRequest
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import javax.inject.Inject

/**
 * OkHttp Authenticator that handles 401 responses.
 * Uses a dedicated unauthenticated AuthApi to refresh the token without
 * triggering another 401 → infinite-loop cycle.
 *
 * Inject [AuthApi] lazily via [dagger.Lazy] so NetworkModule can provide
 * both the authenticator and the auth-only Retrofit client without a
 * circular dependency.
 */
class TokenAuthenticator @Inject constructor(
    private val tokenProvider: TokenProvider,
    private val authApi: dagger.Lazy<AuthApi>,
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        // Prevent retry loops: if we already attempted a refresh on this response chain, bail.
        if (response.request.header("X-Retry-After-Refresh") != null) return null

        val refreshToken = tokenProvider.getRefreshToken() ?: return null

        val newTokens = runBlocking {
            runCatching {
                authApi.get().refreshToken(RefreshTokenRequest(refreshToken))
            }.getOrNull()
        } ?: run {
            tokenProvider.clearTokens()
            return null
        }

        tokenProvider.updateTokens(newTokens.accessToken, newTokens.refreshToken)

        return response.request.newBuilder()
            .header("Authorization", "Bearer ${newTokens.accessToken}")
            .header("X-Retry-After-Refresh", "true")
            .build()
    }
}
