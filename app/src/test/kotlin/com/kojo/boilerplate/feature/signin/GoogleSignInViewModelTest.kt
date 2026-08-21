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

    private fun GoogleSignInViewModel.signIn() =
        onEvent(GoogleSignInUiEvent.SignInClicked(fakeContext))

    @Test
    fun `the screen starts Idle`() {
        coEvery { googleAuthRepository.signIn(any()) } returns Result.success(fakeGoogleUser())
        val viewModel = GoogleSignInViewModel(googleAuthRepository)
        assertEquals(GoogleSignInUiState.Idle, viewModel.state.value)
    }

    @Test
    fun `a successful authentication ends in Success`() = runTest {
        val user = fakeGoogleUser()
        coEvery { googleAuthRepository.signIn(any()) } returns Result.success(user)
        val viewModel = GoogleSignInViewModel(googleAuthRepository)

        viewModel.signIn()

        val state = viewModel.state.first { it is GoogleSignInUiState.Success }
        assertEquals(user, (state as GoogleSignInUiState.Success).user)
    }

    @Test
    fun `a successful sign-in raises a SignedIn effect carrying the user`() = runTest {
        val user = fakeGoogleUser()
        coEvery { googleAuthRepository.signIn(any()) } returns Result.success(user)
        val viewModel = GoogleSignInViewModel(googleAuthRepository)

        viewModel.signIn()

        assertEquals(GoogleSignInUiEffect.SignedIn(user), viewModel.effects.first())
    }

    @Test
    fun `a failed sign-in returns to Idle and raises SignInFailed`() = runTest {
        coEvery {
            googleAuthRepository.signIn(any())
        } returns Result.failure(RuntimeException("Network error"))
        val viewModel = GoogleSignInViewModel(googleAuthRepository)

        viewModel.signIn()

        assertEquals(
            GoogleSignInUiEffect.SignInFailed("Network error"),
            viewModel.effects.first(),
        )
        // The failure is announced once and the screen goes back to offering the button. It
        // does not park in an error state that the next composition would announce again.
        assertEquals(GoogleSignInUiState.Idle, viewModel.state.value)
    }

    @Test
    fun `a cancelled sign-in reverts to Idle without an effect`() = runTest {
        coEvery {
            googleAuthRepository.signIn(any())
        } returns Result.failure(GetCredentialCancellationException())
        val viewModel = GoogleSignInViewModel(googleAuthRepository)

        viewModel.signIn()

        assertEquals(GoogleSignInUiState.Idle, viewModel.state.value)
        // Dismissing the credential picker is not news to the user who dismissed it.
        assertNull(withTimeoutOrNull(EFFECT_TIMEOUT_MS) { viewModel.effects.first() })
    }

    @Test
    fun `a second sign-in while one is in flight is dropped`() = runTest {
        // The gate is what makes this test mean anything. Previously the fake returned
        // before the handler handed control back, so the state was already Success by the
        // second event, the Loading guard never fired, and all three went through — the
        // assertion below failed against a call count of 3. Holding the first call in flight
        // is the only way the guard it is checking can be reached.
        val gate = CompletableDeferred<Unit>()
        val fakeRepo = FakeGoogleAuthRepository(
            signInResult = Result.success(fakeGoogleUser()),
            signInGate = gate,
        )
        val viewModel = GoogleSignInViewModel(fakeRepo)

        viewModel.signIn() // enters, parks on the gate, the state stays Loading
        viewModel.signIn() // rejected by the Loading guard
        viewModel.signIn() // rejected by the Loading guard

        assertEquals(1, fakeRepo.signInCallCount)

        gate.complete(Unit)
        viewModel.state.first { it is GoogleSignInUiState.Success }
        assertEquals(1, fakeRepo.signInCallCount)
    }

    @Test
    fun `signing out returns the screen to Idle`() = runTest {
        val user = fakeGoogleUser()
        coEvery { googleAuthRepository.signIn(any()) } returns Result.success(user)
        coEvery { googleAuthRepository.signOut() } returns Result.success(Unit)
        val viewModel = GoogleSignInViewModel(googleAuthRepository)

        viewModel.signIn()
        viewModel.state.first { it is GoogleSignInUiState.Success }

        viewModel.onEvent(GoogleSignInUiEvent.SignOutClicked)

        val state = viewModel.state.first { it is GoogleSignInUiState.Idle }
        assertEquals(GoogleSignInUiState.Idle, state)
    }

    @Test
    fun `the failure message falls back to a default when the throwable has none`() = runTest {
        coEvery { googleAuthRepository.signIn(any()) } returns Result.failure(RuntimeException())
        val viewModel = GoogleSignInViewModel(googleAuthRepository)

        viewModel.signIn()

        val effect = viewModel.effects.first()
        assertTrue(effect is GoogleSignInUiEffect.SignInFailed)
        assertEquals("Sign-in failed", (effect as GoogleSignInUiEffect.SignInFailed).message)
    }

    /**
     * The config-change case, and the reason the two streams exist side by side. A rotation
     * destroys the composition and builds a new one against the same ViewModel, so everything
     * the ViewModel exposes is collected again from scratch.
     */
    @Test
    fun `a consumed effect is not replayed to the collector that replaces it`() = runTest {
        val user = fakeGoogleUser()
        coEvery { googleAuthRepository.signIn(any()) } returns Result.success(user)
        val viewModel = GoogleSignInViewModel(googleAuthRepository)

        viewModel.signIn()
        assertEquals(GoogleSignInUiEffect.SignedIn(user), viewModel.effects.first())

        // The composition after the rotation. It must not navigate a second time.
        assertNull(withTimeoutOrNull(EFFECT_TIMEOUT_MS) { viewModel.effects.first() })
        // The state, by contrast, is replayed in full — which is what makes it state.
        assertEquals(GoogleSignInUiState.Success(user), viewModel.state.value)
    }

    /**
     * Sign-in finishing with this screen stopped is the ordinary case, not the edge one: the
     * credential picker is another Activity on top of it. A `MutableSharedFlow(replay = 0)`
     * would drop the effect outright, since nothing is subscribed at the moment it is raised.
     */
    @Test
    fun `an effect raised while nothing is collecting is delivered when collection resumes`() =
        runTest {
            val user = fakeGoogleUser()
            coEvery { googleAuthRepository.signIn(any()) } returns Result.success(user)
            val viewModel = GoogleSignInViewModel(googleAuthRepository)

            viewModel.signIn() // completes with no collector attached
            viewModel.state.first { it is GoogleSignInUiState.Success }

            assertEquals(GoogleSignInUiEffect.SignedIn(user), viewModel.effects.first())
        }

    private companion object {
        /**
         * Long enough that a real emission would have arrived, short enough to keep the
         * absence assertions quick. It is virtual time under `runTest`, so it costs nothing.
         */
        const val EFFECT_TIMEOUT_MS = 1_000L
    }
}
