package com.kojo.boilerplate.core.auth

import android.content.Context

class FakeGoogleAuthRepository(
    private val signInResult: Result<GoogleUser> = Result.success(fakeGoogleUser()),
    private val signOutResult: Result<Unit> = Result.success(Unit),
) : GoogleAuthRepository {

    var signInCallCount = 0
        private set
    var signOutCallCount = 0
        private set

    override suspend fun signIn(activityContext: Context): Result<GoogleUser> {
        signInCallCount++
        return signInResult
    }

    override suspend fun signOut(): Result<Unit> {
        signOutCallCount++
        return signOutResult
    }

    companion object {
        fun fakeGoogleUser(
            id: String = "test-id",
            email: String = "test@example.com",
            displayName: String = "Test User",
            profilePictureUrl: String? = null,
            idToken: String = "fake-id-token",
        ): GoogleUser = GoogleUser(
            id = id,
            email = email,
            displayName = displayName,
            profilePictureUrl = profilePictureUrl,
            idToken = idToken,
        )
    }
}
