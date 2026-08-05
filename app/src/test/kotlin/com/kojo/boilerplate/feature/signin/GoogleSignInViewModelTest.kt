package com.kojo.boilerplate.feature.signin

import android.content.Context
import com.kojo.boilerplate.core.auth.FakeGoogleAuthRepository
import com.kojo.boilerplate.core.auth.FakeGoogleAuthRepository.Companion.fakeGoogleUser
import com.kojo.boilerplate.core.auth.GoogleAuthRepository
import com.kojo.boilerplate.core.coroutines.MainDispatcherExtension
import androidx.credentials.exceptions.GetCredentialCancellationException
import io.mockk.coEvery
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MockKExtension::class)
class GoogleSignInViewModelTest {

    @JvmField
    @RegisterExtension
    val mainDispatcherExtension = MainDispatcherExtension()

    @MockK
    lateinit var googleAuthRepository: GoogleAuthRepository

    private val fakeContext: Context = mockk(relaxed = true)

    @Test
    fun `initial uiState is Idle`() {
        coEvery { googleAuthRepository.signIn(any()) } returns Result.success(fakeGoogleUser())
        val viewModel = GoogleSignInViewModel(googleAuthRepository)
        assertEquals(GoogleSignInUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun `signIn emits Success on successful authentication`() = runTest {
        val user = fakeGoogleUser()
        coEvery { googleAuthRepository.signIn(any()) } returns Result.success(user)
        val viewModel = GoogleSignInViewModel(googleAuthRepository)

        viewModel.signIn(fakeContext)

        val state = viewModel.uiState.first { it is GoogleSignInUiState.Success }
        assertEquals(user, (state as GoogleSignInUiState.Success).user)
    }

    @Test
    fun `successful sign-in raises a SignedIn event carrying the user`() = runTest {
        val user = fakeGoogleUser()
        coEvery { googleAuthRepository.signIn(any()) } returns Result.success(user)
        val viewModel = GoogleSignInViewModel(googleAuthRepository)

        viewModel.signIn(fakeContext)

        assertEquals(GoogleSignInEvent.SignedIn(user), viewModel.events.first())
    }

    @Test
    fun `signIn returns to Idle and raises SignInFailed when the repository fails`() = runTest {
        coEvery {
            googleAuthRepository.signIn(any())
        } returns Result.failure(RuntimeException("Network error"))
        val viewModel = GoogleSignInViewModel(googleAuthRepository)

        viewModel.signIn(fakeContext)

        assertEquals(GoogleSignInEvent.SignInFailed("Network error"), viewModel.events.first())
        // The failure is announced once and the screen goes back to offering the button. It
        // does not park in an error state that the next composition would announce again.
        assertEquals(GoogleSignInUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun `signIn reverts to Idle without an event when the user cancels`() = runTest {
        coEvery {
            googleAuthRepository.signIn(any())
        } returns Result.failure(GetCredentialCancellationException())
        val viewModel = GoogleSignInViewModel(googleAuthRepository)

        viewModel.signIn(fakeContext)

        assertEquals(GoogleSignInUiState.Idle, viewModel.uiState.value)
        // Dismissing the credential picker is not news to the user who dismissed it.
        assertNull(withTimeoutOrNull(EVENT_TIMEOUT_MS) { viewModel.events.first() })
    }

    @Test
    fun `signIn is debounced while already loading`() = runTest {
        // The gate is what makes this test mean anything. Previously the fake returned
        // before signIn() handed control back, so uiState was already Success by the
        // second call, the Loading guard never fired, and all three calls went through —
        // the assertion below failed against a call count of 3. Holding the first call
        // in flight is the only way the guard it is checking can be reached.
        val gate = CompletableDeferred<Unit>()
        val fakeRepo = FakeGoogleAuthRepository(
            signInResult = Result.success(fakeGoogleUser()),
            signInGate = gate,
        )
        val viewModel = GoogleSignInViewModel(fakeRepo)

        viewModel.signIn(fakeContext) // enters, parks on the gate, uiState stays Loading
        viewModel.signIn(fakeContext) // rejected by the Loading guard
        viewModel.signIn(fakeContext) // rejected by the Loading guard

        assertEquals(1, fakeRepo.signInCallCount)

        gate.complete(Unit)
        viewModel.uiState.first { it is GoogleSignInUiState.Success }
        assertEquals(1, fakeRepo.signInCallCount)
    }

    @Test
    fun `signOut resets uiState to Idle`() = runTest {
        val user = fakeGoogleUser()
        coEvery { googleAuthRepository.signIn(any()) } returns Result.success(user)
        coEvery { googleAuthRepository.signOut() } returns Result.success(Unit)
        val viewModel = GoogleSignInViewModel(googleAuthRepository)

        viewModel.signIn(fakeContext)
        viewModel.uiState.first { it is GoogleSignInUiState.Success }

        viewModel.signOut()

        val state = viewModel.uiState.first { it is GoogleSignInUiState.Idle }
        assertEquals(GoogleSignInUiState.Idle, state)
    }

    @Test
    fun `error message falls back to default when throwable has no message`() = runTest {
        coEvery { googleAuthRepository.signIn(any()) } returns Result.failure(RuntimeException())
        val viewModel = GoogleSignInViewModel(googleAuthRepository)

        viewModel.signIn(fakeContext)

        val event = viewModel.events.first()
        assertTrue(event is GoogleSignInEvent.SignInFailed)
        assertEquals("Sign-in failed", (event as GoogleSignInEvent.SignInFailed).message)
    }

    /**
     * The config-change case, and the reason the two streams exist side by side. A rotation
     * destroys the composition and builds a new one against the same ViewModel, so everything
     * the ViewModel exposes is collected again from scratch.
     */
    @Test
    fun `a consumed event is not replayed to the collector that replaces it`() = runTest {
        val user = fakeGoogleUser()
        coEvery { googleAuthRepository.signIn(any()) } returns Result.success(user)
        val viewModel = GoogleSignInViewModel(googleAuthRepository)

        viewModel.signIn(fakeContext)
        assertEquals(GoogleSignInEvent.SignedIn(user), viewModel.events.first())

        // The composition after the rotation. It must not navigate a second time.
        assertNull(withTimeoutOrNull(EVENT_TIMEOUT_MS) { viewModel.events.first() })
        // The state, by contrast, is replayed in full — which is what makes it state.
        assertEquals(GoogleSignInUiState.Success(user), viewModel.uiState.value)
    }

    /**
     * Sign-in finishing with this screen stopped is the ordinary case, not the edge one: the
     * credential picker is another Activity on top of it. A `MutableSharedFlow(replay = 0)`
     * would drop the event outright, since nothing is subscribed at the moment it is raised.
     */
    @Test
    fun `an event raised while nothing is collecting is delivered when collection resumes`() =
        runTest {
            val user = fakeGoogleUser()
            coEvery { googleAuthRepository.signIn(any()) } returns Result.success(user)
            val viewModel = GoogleSignInViewModel(googleAuthRepository)

            viewModel.signIn(fakeContext) // completes with no collector attached
            viewModel.uiState.first { it is GoogleSignInUiState.Success }

            assertEquals(GoogleSignInEvent.SignedIn(user), viewModel.events.first())
        }

    private companion object {
        /**
         * Long enough that a real emission would have arrived, short enough to keep the
         * absence assertions quick. It is virtual time under `runTest`, so it costs nothing.
         */
        const val EVENT_TIMEOUT_MS = 1_000L
    }
}
