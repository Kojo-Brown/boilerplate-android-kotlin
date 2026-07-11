package com.kojo.boilerplate.feature.signin

import android.content.Context
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.kojo.boilerplate.core.auth.FakeGoogleAuthRepository
import com.kojo.boilerplate.core.auth.FakeGoogleAuthRepository.Companion.fakeGoogleUser
import com.kojo.boilerplate.core.auth.GoogleUser
import com.kojo.boilerplate.core.coroutines.MainDispatcherRule
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GoogleSignInViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val fakeContext: Context = mockk(relaxed = true)

    @Test
    fun `initial uiState is Idle`() {
        val viewModel = buildViewModel()
        assertEquals(GoogleSignInUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun `signIn emits Success on successful authentication`() = runTest {
        val user = fakeGoogleUser()
        val viewModel = buildViewModel(signInResult = Result.success(user))

        viewModel.signIn(fakeContext)

        val state = viewModel.uiState.first { it is GoogleSignInUiState.Success }
        assertEquals(user, (state as GoogleSignInUiState.Success).user)
    }

    @Test
    fun `signIn emits Error when repository returns failure`() = runTest {
        val viewModel = buildViewModel(
            signInResult = Result.failure(RuntimeException("Network error")),
        )

        viewModel.signIn(fakeContext)

        val state = viewModel.uiState.first { it !is GoogleSignInUiState.Loading }
        assertTrue(state is GoogleSignInUiState.Error)
        assertEquals("Network error", (state as GoogleSignInUiState.Error).message)
    }

    @Test
    fun `signIn reverts to Idle when user cancels credential selection`() = runTest {
        val viewModel = buildViewModel(
            signInResult = Result.failure(GetCredentialCancellationException()),
        )

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
        val viewModel = buildViewModel(signInResult = Result.success(user))

        viewModel.signIn(fakeContext)
        viewModel.uiState.first { it is GoogleSignInUiState.Success }

        viewModel.signOut()

        val state = viewModel.uiState.first { it is GoogleSignInUiState.Idle }
        assertEquals(GoogleSignInUiState.Idle, state)
    }

    @Test
    fun `clearError transitions Error back to Idle`() = runTest {
        val viewModel = buildViewModel(
            signInResult = Result.failure(RuntimeException("oops")),
        )

        viewModel.signIn(fakeContext)
        viewModel.uiState.first { it is GoogleSignInUiState.Error }

        viewModel.clearError()

        val state = viewModel.uiState.first { it is GoogleSignInUiState.Idle }
        assertEquals(GoogleSignInUiState.Idle, state)
    }

    @Test
    fun `error message falls back to default when throwable has no message`() = runTest {
        val viewModel = buildViewModel(
            signInResult = Result.failure(RuntimeException()),
        )

        viewModel.signIn(fakeContext)

        val state = viewModel.uiState.first { it is GoogleSignInUiState.Error }
        assertEquals("Sign-in failed", (state as GoogleSignInUiState.Error).message)
    }

    private fun buildViewModel(
        signInResult: Result<GoogleUser> = Result.success(fakeGoogleUser()),
        signOutResult: Result<Unit> = Result.success(Unit),
    ): GoogleSignInViewModel = GoogleSignInViewModel(
        googleAuthRepository = FakeGoogleAuthRepository(
            signInResult = signInResult,
            signOutResult = signOutResult,
        ),
    )
}
