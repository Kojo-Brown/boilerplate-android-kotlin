package com.kojo.boilerplate.core.auth

import android.content.Context
import kotlinx.coroutines.CompletableDeferred

class FakeGoogleAuthRepository(
    private val signInResult: Result<GoogleUser> = Result.success(fakeGoogleUser()),
    private val signOutResult: Result<Unit> = Result.success(Unit),
    /**
     * When supplied, [signIn] suspends on this until the test completes it.
     *
     * Without it the fake returns before the caller regains control, so a sign-in is
     * never observably in flight — and behaviour that only exists while one is, such as
     * the Loading guard in GoogleSignInViewModel.signIn, cannot be exercised at all.
     */
    private val signInGate: CompletableDeferred<Unit>? = null,
) : GoogleAuthRepository {

    var signInCallCount = 0
        private set
    var signOutCallCount = 0
        private set

    override suspend fun signIn(activityContext: Context): Result<GoogleUser> {
        // Counted before suspending, so a test can assert how many calls got this far
        // while the first one is still held.
        signInCallCount++
        signInGate?.await()
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
