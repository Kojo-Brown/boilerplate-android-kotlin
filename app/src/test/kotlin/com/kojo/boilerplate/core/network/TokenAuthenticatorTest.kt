package com.kojo.boilerplate.core.network

import com.kojo.boilerplate.core.event.AppEvent
import com.kojo.boilerplate.core.event.AppEventBus
import com.kojo.boilerplate.core.network.api.AuthApi
import com.kojo.boilerplate.core.network.model.LoginRequest
import com.kojo.boilerplate.core.network.model.RefreshTokenRequest
import com.kojo.boilerplate.core.network.model.TokenResponse
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The authenticator is the app's only publisher of [AppEvent.SessionExpired], and what makes it
 * the right one is that it is the only code that can tell the difference between the three ways
 * a 401 ends: refreshed, never authenticated, and *expired*. Only the third is an event, and
 * publishing on either of the others would sign the user out of a session they still have.
 */
class TokenAuthenticatorTest {

    private val tokenProvider = InMemoryTokenProvider()
    private val appEventBus = RecordingAppEventBus()

    @Test
    fun `publishes SessionExpired and clears the tokens when the refresh is rejected`() {
        tokenProvider.updateTokens("mock-access-token", "mock-refresh-token")
        val authenticator = authenticatorRefreshing { error("401 from the refresh endpoint") }

        val retry = authenticator.authenticate(route = null, response = unauthorized())

        assertNull(retry, "There is nothing to retry with; the session is gone.")
        assertNull(tokenProvider.getAccessToken())
        assertNull(tokenProvider.getRefreshToken())
        assertEquals(listOf(AppEvent.SessionExpired), appEventBus.published)
    }

    /**
     * A request that was never authenticated in the first place. There was no session, so
     * nothing expired — and a listener that reacted here would clear credential state and send
     * the user to the sign-in screen because one anonymous call happened to get a 401.
     */
    @Test
    fun `publishes nothing when there was no refresh token to begin with`() {
        val authenticator = authenticatorRefreshing { error("must not be called") }

        val retry = authenticator.authenticate(route = null, response = unauthorized())

        assertNull(retry)
        assertEquals(emptyList<AppEvent>(), appEventBus.published)
    }

    @Test
    fun `publishes nothing when the refresh succeeds`() {
        tokenProvider.updateTokens("mock-access-token", "mock-refresh-token")
        val authenticator = authenticatorRefreshing {
            TokenResponse(accessToken = "mock-fresh-access", refreshToken = "mock-fresh-refresh")
        }

        val retry = authenticator.authenticate(route = null, response = unauthorized())

        assertNotNull(retry)
        assertEquals("Bearer mock-fresh-access", retry?.header("Authorization"))
        assertEquals("mock-fresh-access", tokenProvider.getAccessToken())
        assertEquals(emptyList<AppEvent>(), appEventBus.published)
    }

    /**
     * The loop guard. A retried request that comes back 401 means the freshly minted token was
     * itself rejected — a server-side problem this class cannot refresh its way out of. It
     * gives up without a second refresh, and without an event: the tokens it holds are the ones
     * the server just issued, so discarding them is not the app's call to make here.
     */
    @Test
    fun `publishes nothing on a request that has already been retried after a refresh`() {
        tokenProvider.updateTokens("mock-access-token", "mock-refresh-token")
        val authenticator = authenticatorRefreshing { error("must not be called") }

        val retry = authenticator.authenticate(
            route = null,
            response = unauthorized(retriedAfterRefresh = true),
        )

        assertNull(retry)
        assertEquals(emptyList<AppEvent>(), appEventBus.published)
        assertEquals("mock-access-token", tokenProvider.getAccessToken())
    }

    private fun authenticatorRefreshing(refresh: () -> TokenResponse): TokenAuthenticator {
        val api = FakeAuthApi(refresh)
        return TokenAuthenticator(
            tokenProvider = tokenProvider,
            authApi = object : dagger.Lazy<AuthApi> {
                override fun get(): AuthApi = api
            },
            appEventBus = appEventBus,
        )
    }

    private fun unauthorized(retriedAfterRefresh: Boolean = false): Response {
        val request = Request.Builder()
            .url("https://api.example.invalid/v1/users")
            .apply { if (retriedAfterRefresh) header("X-Retry-After-Refresh", "true") }
            .build()
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(HTTP_UNAUTHORIZED)
            .message("Unauthorized")
            .build()
    }

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
    }
}

private class FakeAuthApi(private val refresh: () -> TokenResponse) : AuthApi {

    override suspend fun login(request: LoginRequest): TokenResponse =
        error("login is not part of the authenticator's path")

    override suspend fun refreshToken(request: RefreshTokenRequest): TokenResponse = refresh()

    override suspend fun logout(bearerToken: String): Unit =
        error("logout is not part of the authenticator's path")
}

private class RecordingAppEventBus : AppEventBus {

    val published = mutableListOf<AppEvent>()

    override val events: SharedFlow<AppEvent> = MutableSharedFlow<AppEvent>().asSharedFlow()

    override suspend fun publish(event: AppEvent) {
        published += event
    }

    override fun tryPublish(event: AppEvent): Boolean {
        published += event
        return true
    }
}
