package com.kojo.boilerplate.core.network.api

import com.kojo.boilerplate.core.network.model.LoginRequest
import com.kojo.boilerplate.core.network.model.RefreshTokenRequest
import com.kojo.boilerplate.core.network.model.TokenResponse
import com.kojo.boilerplate.core.security.integrity.IntegrityInterceptor
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST

interface AuthApi {

    /**
     * Exchanges a password for a token pair — and the one endpoint in this app that is attested.
     *
     * ## Why this one
     *
     * Play rate limits integrity tokens per app and per device, and each one costs a round trip
     * to Play on the request path, so attestation is spent where it buys the most. Sign-in is
     * that place: it is the step a credential-stuffing script repeats, it is low-frequency for a
     * real person, and a verdict attached to it tells the backend that the attempt came from a
     * genuine device running a binary Play distributed — before a session exists rather than
     * after.
     *
     * [IntegrityInterceptor] swaps this marker for the token and the marker never reaches the
     * network. The request goes out unattested when the device cannot produce one, and the
     * server decides what that is worth; see the interceptor for why the client does not.
     *
     * `refreshToken` below is deliberately *not* attested. It runs on a schedule the user does
     * not control, often while the app is in the background, and attesting it would spend the
     * device's token budget on the request least able to wait for it — while proving nothing the
     * attested sign-in that issued the refresh token has not already proved.
     */
    @Headers(IntegrityInterceptor.REQUIRE_ATTESTATION_HEADER)
    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): TokenResponse

    @POST("auth/refresh")
    suspend fun refreshToken(@Body request: RefreshTokenRequest): TokenResponse

    @DELETE("auth/logout")
    suspend fun logout(@Header("Authorization") bearerToken: String)
}
