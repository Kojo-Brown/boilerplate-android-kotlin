package com.kojo.boilerplate.feature.signin

import android.content.Context
import com.kojo.boilerplate.core.auth.FakeGoogleAuthRepository
import com.kojo.boilerplate.core.auth.FakeGoogleAuthRepository.Companion.fakeGoogleUser
import com.kojo.boilerplate.core.auth.GoogleAuthRepository
import com.kojo.boilerplate.core.auth.GoogleUser
import com.kojo.boilerplate.core.coroutines.MainDispatcherExtension
import androidx.credentials.exceptions.GetCredentialCancellationException
import io.mockk.coEvery
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
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
    fun `signIn emits Error when repository returns failure`() = runTest {
        coEvery { googleAuthRepository.signIn(any()) } returns Result.failure(RuntimeException("Network error"))
        val viewModel = GoogleSignInViewModel(googleAuthRepository)

        viewModel.signIn(fakeContext)

        val state = viewModel.uiState.first { it !is GoogleSignInUiState.Loading }
        assertTrue(state is GoogleSignInUiState.Error)
        assertEquals("Network error", (state as GoogleSignInUiState.Error).message)
    }

    @Test
    fun `signIn reverts to Idle when user cancels credential selection`() = runTest {
        coEvery { googleAuthRepository.signIn(any()) } returns Result.failure(GetCredentialCancellationException())
        val viewModel = GoogleSignInViewModel(googleAuthRepository)

        viewModel.signIn(fakeContext)

        val state = viewModel.uiState.first { it !is GoogleSignInUiState.Loading }
        assertEquals(GoogleSignInUiState.Idle, state)
    }

    @Test
    fun `signIn is debounced while already loading`() = runTest {
        val fakeRepo = FakeGoogleAuthRepository(
            signInResult = Result.success(fakeGoogleUser()),
        )
        val viewModel = GoogleSignInViewModel(fakeRepo)

        viewModel.signIn(fakeContext)
        viewModel.signIn(fakeContext)
        viewModel.signIn(fakeContext)

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
    fun `clearError transitions Error back to Idle`() = runTest {
        coEvery { googleAuthRepository.signIn(any()) } returns Result.failure(RuntimeException("oops"))
        val viewModel = GoogleSignInViewModel(googleAuthRepository)

        viewModel.signIn(fakeContext)
        viewModel.uiState.first { it is GoogleSignInUiState.Error }

        viewModel.clearError()

        val state = viewModel.uiState.first { it is GoogleSignInUiState.Idle }
        assertEquals(GoogleSignInUiState.Idle, state)
    }

    @Test
    fun `error message falls back to default when throwable has no message`() = runTest {
        coEvery { googleAuthRepository.signIn(any()) } returns Result.failure(RuntimeException())
        val viewModel = GoogleSignInViewModel(googleAuthRepository)

        viewModel.signIn(fakeContext)

        val state = viewModel.uiState.first { it is GoogleSignInUiState.Error }
        assertEquals("Sign-in failed", (state as GoogleSignInUiState.Error).message)
    }
}
