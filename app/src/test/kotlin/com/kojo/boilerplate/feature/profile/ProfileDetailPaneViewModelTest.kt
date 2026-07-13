package com.kojo.boilerplate.feature.profile

import com.kojo.boilerplate.core.coroutines.MainDispatcherRule
import com.kojo.boilerplate.core.data.model.User
import com.kojo.boilerplate.core.data.repository.FakeUserRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileDetailPaneViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val testUser = User(
        id = "user-1",
        displayName = "Alice Johnson",
        email = "alice@example.com",
        avatarUrl = null,
    )

    private lateinit var fakeRepository: FakeUserRepository

    private fun createViewModel(userId: String = testUser.id) = ProfileDetailPaneViewModel(
        userId = userId,
        userRepository = fakeRepository,
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    @Before
    fun setUp() {
        fakeRepository = FakeUserRepository(listOf(testUser))
    }

    @Test
    fun `initial uiState is Loading`() {
        val viewModel = createViewModel()
        assertEquals(ProfileUiState.Loading, viewModel.uiState.value)
    }

    @Test
    fun `uiState emits Success when user exists`() = runTest {
        val viewModel = createViewModel()

        val state = viewModel.uiState.first { it is ProfileUiState.Success }

        assertTrue(state is ProfileUiState.Success)
        val success = state as ProfileUiState.Success
        assertEquals("user-1", success.profile.userId)
        assertEquals("Alice Johnson", success.profile.displayName)
        assertEquals("alice@example.com", success.profile.email)
    }

    @Test
    fun `uiState emits Error when user is not found`() = runTest {
        val viewModel = createViewModel(userId = "nonexistent-id")

        val state = viewModel.uiState.first { it is ProfileUiState.Error }

        assertTrue(state is ProfileUiState.Error)
        assertTrue((state as ProfileUiState.Error).message.contains("nonexistent-id"))
    }

    @Test
    fun `uiState emits Error when repository throws`() = runTest {
        fakeRepository.shouldThrowOnGetUser = RuntimeException("database error")
        val viewModel = createViewModel()

        val state = viewModel.uiState.first { it is ProfileUiState.Error }

        assertEquals("database error", (state as ProfileUiState.Error).message)
    }

    @Test
    fun `retry recovers from error state`() = runTest {
        fakeRepository.shouldThrowOnGetUser = RuntimeException("transient error")
        val viewModel = createViewModel()

        viewModel.uiState.first { it is ProfileUiState.Error }

        fakeRepository.shouldThrowOnGetUser = null
        viewModel.retry()

        val state = viewModel.uiState.first { it is ProfileUiState.Success }
        assertEquals("Alice Johnson", (state as ProfileUiState.Success).profile.displayName)
    }

    @Test
    fun `uiState updates when user data changes in repository`() = runTest {
        val viewModel = createViewModel()
        viewModel.uiState.first { it is ProfileUiState.Success }

        val updatedUser = testUser.copy(displayName = "Alice Updated")
        fakeRepository.saveUser(updatedUser)

        val state = viewModel.uiState.first {
            it is ProfileUiState.Success &&
                (it as ProfileUiState.Success).profile.displayName == "Alice Updated"
        }
        assertEquals("Alice Updated", (state as ProfileUiState.Success).profile.displayName)
    }
}
