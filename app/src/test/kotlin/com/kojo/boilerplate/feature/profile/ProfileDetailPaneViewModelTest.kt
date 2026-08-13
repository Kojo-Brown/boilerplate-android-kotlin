package com.kojo.boilerplate.feature.profile

import com.kojo.boilerplate.core.coroutines.MainDispatcherExtension
import com.kojo.boilerplate.core.data.model.User
import com.kojo.boilerplate.core.data.repository.UserRepository
import com.kojo.boilerplate.core.domain.usecase.ObserveUserProfileUseCase
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import java.io.IOException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MockKExtension::class)
class ProfileDetailPaneViewModelTest {

    @JvmField
    @RegisterExtension
    val mainDispatcherExtension = MainDispatcherExtension()

    @MockK
    lateinit var userRepository: UserRepository

    private val testUser = User(
        id = "user-1",
        displayName = "Alice Johnson",
        email = "alice@example.com",
        avatarUrl = null,
    )

    @BeforeEach
    fun setUp() {
        every { userRepository.getUser(testUser.id) } returns flowOf(testUser)
    }

    private fun createViewModel(userId: String = testUser.id) = ProfileDetailPaneViewModel(
        userId = userId,
        observeUserProfile = ObserveUserProfileUseCase(userRepository),
    )

    /**
     * uiState is built with stateIn(..., SharingStarted.WhileSubscribed), so its upstream
     * does not run until something collects it. Reading .value with no subscriber returns
     * the initial Loading value forever — which is why every assertion below used to fail
     * with ClassCastException or "expected Success". Collecting on backgroundScope keeps
     * the state hot for the test and runTest tears it down automatically.
     *
     * The collector is pinned to the test's own scheduler so there is a single clock. The
     * view model no longer takes a dispatcher of its own: its upstream runs in
     * `viewModelScope`, which the extension above has already pointed at that scheduler,
     * so the retry backoff stays on virtual time.
     */
    private fun TestScope.createSubscribedViewModel(
        userId: String = testUser.id,
    ): ProfileDetailPaneViewModel {
        val viewModel = ProfileDetailPaneViewModel(
            userId = userId,
            observeUserProfile = ObserveUserProfileUseCase(userRepository),
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { }
        }
        return viewModel
    }

    @Test
    fun `initial uiState is Loading`() {
        assertEquals(ProfileUiState.Loading, createViewModel().uiState.value)
    }

    @Test
    fun `uiState emits Success when user exists`() = runTest {
        val viewModel = createSubscribedViewModel()

        val state = viewModel.uiState.value

        assertTrue(state is ProfileUiState.Success)
        val success = state as ProfileUiState.Success
        assertEquals("user-1", success.profile.userId)
        assertEquals("Alice Johnson", success.profile.displayName)
        assertEquals("alice@example.com", success.profile.email)
    }

    @Test
    fun `uiState emits Error when user is not found`() = runTest {
        every { userRepository.getUser("nonexistent-id") } returns flowOf(null)

        val viewModel = createSubscribedViewModel(userId = "nonexistent-id")

        val state = viewModel.uiState.value
        assertTrue(state is ProfileUiState.Error)
        assertTrue((state as ProfileUiState.Error).message.contains("nonexistent-id"))
    }

    @Test
    fun `uiState emits Error once the automatic retries are exhausted`() = runTest {
        var subscriptions = 0
        every { userRepository.getUser(testUser.id) } returns flow {
            subscriptions++
            throw IOException("database error")
        }

        val viewModel = createSubscribedViewModel()

        // An IOException is transient, so retryWithBackoff holds the screen on Loading while
        // it tries again rather than showing an error the next attempt might clear.
        assertEquals(ProfileUiState.Loading, viewModel.uiState.value)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("database error", (state as ProfileUiState.Error).message)
        assertEquals(4, subscriptions) // the first attempt plus three retries
    }

    @Test
    fun `a failure that another attempt cannot fix is surfaced immediately`() = runTest {
        var subscriptions = 0
        every { userRepository.getUser(testUser.id) } returns flow {
            subscriptions++
            error("unparseable row")
        }

        val viewModel = createSubscribedViewModel()

        assertTrue(viewModel.uiState.value is ProfileUiState.Error)
        assertEquals(1, subscriptions)
        assertEquals(0L, currentTime)
    }

    @Test
    fun `retry recovers from error state`() = runTest {
        every { userRepository.getUser(testUser.id) } returns flow { throw IOException("transient error") }
        val viewModel = createSubscribedViewModel()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is ProfileUiState.Error)

        every { userRepository.getUser(testUser.id) } returns flowOf(testUser)
        viewModel.retry()
        advanceUntilIdle()

        val state = viewModel.uiState.value as ProfileUiState.Success
        assertEquals("Alice Johnson", state.profile.displayName)
    }

    @Test
    fun `uiState updates when user data changes in repository`() = runTest {
        val userFlow = MutableStateFlow<User?>(testUser)
        every { userRepository.getUser(testUser.id) } returns userFlow

        val viewModel = createSubscribedViewModel()
        assertTrue(viewModel.uiState.value is ProfileUiState.Success)

        val updatedUser = testUser.copy(displayName = "Alice Updated")
        userFlow.value = updatedUser

        val state = viewModel.uiState.value as ProfileUiState.Success
        assertEquals("Alice Updated", state.profile.displayName)
    }
}
