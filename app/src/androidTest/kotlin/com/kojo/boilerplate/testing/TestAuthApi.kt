package com.kojo.boilerplate.testing

import com.kojo.boilerplate.core.network.api.AuthApi
import com.kojo.boilerplate.core.network.model.LoginRequest
import com.kojo.boilerplate.core.network.model.RefreshTokenRequest
import com.kojo.boilerplate.core.network.model.TokenResponse
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The [AuthApi] the test graph binds in place of the Retrofit one.
 *
 * ## Why it is replaced even though nothing reachable needs it
 *
 * In the production graph the only consumer of [AuthApi] is `TokenAuthenticator`, which only
 * `NetworkModule.provideOkHttpClient` injects — and that provider is gone with the module. So
 * Dagger would never construct an [AuthApi] in a test even if the real binding were left in
 * place. It is replaced anyway, for two reasons that are about the graph rather than about this
 * class: the real provider builds a Retrofit instance over the *unauthenticated, pinned* client
 * — the one that carries a password on the way in — and leaving it bindable means the next test
 * that injects an `AuthApi` gets it without having changed anything. A test module that replaces
 * only what the current tests touch is one that stops being true the moment a test is added.
 *
 * ## The password is recorded nowhere
 *
 * [Call.Login] keeps the email and drops the password, and that is not an oversight. A failed
 * assertion prints the recorded calls, and an instrumented test report is uploaded as a CI
 * artifact — so a double that keeps a password in a list is a credential in a build log. The
 * tokens below are fixtures and are written to look like nothing else: a real JWT in a test
 * fixture is indistinguishable from a leaked one.
 */
@Singleton
class TestAuthApi @Inject constructor() : AuthApi {

    private val recorded = CopyOnWriteArrayList<Call>()

    /** Every call that reached this object, in the order the records were appended. */
    val calls: List<Call> get() = recorded.toList()

    sealed interface Call {

        /** The email only — see the class KDoc for why the password is not here. */
        data class Login(val email: String) : Call

        data class Refresh(val refreshToken: String) : Call

        data class Logout(val bearerToken: String) : Call
    }

    override suspend fun login(request: LoginRequest): TokenResponse {
        recorded += Call.Login(email = request.email)
        return TOKENS
    }

    override suspend fun refreshToken(request: RefreshTokenRequest): TokenResponse {
        recorded += Call.Refresh(refreshToken = request.refreshToken)
        return TOKENS
    }

    override suspend fun logout(bearerToken: String) {
        recorded += Call.Logout(bearerToken = bearerToken)
    }

    companion object {

        const val ACCESS_TOKEN: String = "mock-access-token"

        const val REFRESH_TOKEN: String = "mock-refresh-token"

        /** The pair every endpoint here answers with. Obviously fake, deliberately. */
        val TOKENS: TokenResponse = TokenResponse(
            accessToken = ACCESS_TOKEN,
            refreshToken = REFRESH_TOKEN,
        )
    }
}
