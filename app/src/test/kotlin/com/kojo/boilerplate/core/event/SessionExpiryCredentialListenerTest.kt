package com.kojo.boilerplate.core.event

import com.kojo.boilerplate.core.auth.FakeGoogleAuthRepository
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SessionExpiryCredentialListenerTest {

    @Test
    fun `clears the Google credential state when the session expires`() = runTest {
        val googleAuth = FakeGoogleAuthRepository()

        SessionExpiryCredentialListener(googleAuth).onEvent(AppEvent.SessionExpired)

        assertEquals(1, googleAuth.signOutCallCount)
    }

    /**
     * A failure is surfaced, not swallowed. It reaches [AppEventDispatcher], which reports it
     * and keeps every other listener subscribed — the arrangement that lets this listener be
     * three lines with no error handling of its own.
     */
    @Test
    fun `a failure to clear the credential state propagates to the dispatcher`() = runTest {
        val boom = IllegalStateException("credential provider unavailable")
        val googleAuth = FakeGoogleAuthRepository(signOutResult = Result.failure(boom))

        val thrown = assertThrows<IllegalStateException> {
            SessionExpiryCredentialListener(googleAuth).onEvent(AppEvent.SessionExpired)
        }

        assertSame(boom, thrown)
    }
}
