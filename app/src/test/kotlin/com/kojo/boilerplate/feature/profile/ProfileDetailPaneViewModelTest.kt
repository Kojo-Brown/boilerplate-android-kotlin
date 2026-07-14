package com.kojo.boilerplate.feature.profile

import com.kojo.boilerplate.core.coroutines.MainDispatcherExtension
import com.kojo.boilerplate.core.data.model.User
import com.kojo.boilerplate.core.data.repository.UserRepository
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
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
        userRepository = userRepository,
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    @Test
    fun `initial uiState is Loading`() {
        assertEquals(ProfileUiState.Loading, createViewModel().uiState.value)
    }

    @Test
    fun `uiState emits Success when user exists`() = runTest {
        val viewModel = createViewModel()

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

        val viewModel = createViewModel(userId = "nonexistent-id")

        val state = viewModel.uiState.value
        assertTrue(state is ProfileUiState.Error)
        assertTrue((state as ProfileUiState.Error).message.contains("nonexistent-id"))
    }

    @Test
    fun `uiState emits Error when repository throws`() = runTest {
        every { userRepository.getUser(testUser.id) } returns flow { throw RuntimeException("database error") }

        val viewModel = createViewModel()

        val state = viewModel.uiState.value
        assertEquals("database error", (state as ProfileUiState.Error).message)
    }

    @Test
    fun `retry recovers from error state`() = runTest {
        every { userRepository.getUser(testUser.id) } returns flow { throw RuntimeException("transient error") }
        val viewModel = createViewModel()

        assertTrue(viewModel.uiState.value is ProfileUiState.Error)

        every { userRepository.getUser(testUser.id) } returns flowOf(testUser)
        viewModel.retry()

        val state = viewModel.uiState.value as ProfileUiState.Success
        assertEquals("Alice Johnson", state.profile.displayName)
    }

    @Test
    fun `uiState updates when user data changes in repository`() = runTest {
        val userFlow = MutableStateFlow<User?>(testUser)
        every { userRepository.getUser(testUser.id) } returns userFlow

        val viewModel = createViewModel()
        assertTrue(viewModel.uiState.value is ProfileUiState.Success)

        val updatedUser = testUser.copy(displayName = "Alice Updated")
        userFlow.value = updatedUser

        val state = viewModel.uiState.value as ProfileUiState.Success
        assertEquals("Alice Updated", state.profile.displayName)
    }
}
