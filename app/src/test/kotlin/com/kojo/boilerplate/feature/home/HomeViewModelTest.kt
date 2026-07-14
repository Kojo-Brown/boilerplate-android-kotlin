package com.kojo.boilerplate.feature.home

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
class HomeViewModelTest {

    @JvmField
    @RegisterExtension
    val mainDispatcherExtension = MainDispatcherExtension()

    @MockK
    lateinit var userRepository: UserRepository

    private val testUsers = listOf(
        User(id = "1", displayName = "Alice Johnson", email = "alice@example.com"),
        User(id = "2", displayName = "Bob Smith", email = "bob@example.com"),
        User(id = "3", displayName = "Carol White", email = "carol@example.com"),
    )

    @BeforeEach
    fun setUp() {
        every { userRepository.getUsers() } returns flowOf(testUsers)
    }

    private fun buildViewModel() = HomeViewModel(
        userRepository = userRepository,
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    @Test
    fun `uiState initial value is Loading`() {
        assertEquals(HomeUiState.Loading, buildViewModel().uiState.value)
    }

    @Test
    fun `uiState emits Success with all users when search query is empty`() = runTest {
        val viewModel = buildViewModel()
        val state = viewModel.uiState.value

        assertTrue(state is HomeUiState.Success)
        val success = state as HomeUiState.Success
        assertEquals(3, success.items.size)
        assertEquals("Alice Johnson", success.items[0].title)
        assertEquals("alice@example.com", success.items[0].description)
    }

    @Test
    fun `updateSearchQuery filters users by display name`() = runTest {
        val viewModel = buildViewModel()

        viewModel.updateSearchQuery("alice")

        val success = viewModel.uiState.value as HomeUiState.Success
        assertEquals(1, success.items.size)
        assertEquals("Alice Johnson", success.items[0].title)
    }

    @Test
    fun `updateSearchQuery filters users by email`() = runTest {
        val viewModel = buildViewModel()

        viewModel.updateSearchQuery("bob@")

        val success = viewModel.uiState.value as HomeUiState.Success
        assertEquals(1, success.items.size)
        assertEquals("Bob Smith", success.items[0].title)
    }

    @Test
    fun `updateSearchQuery is case insensitive`() = runTest {
        val viewModel = buildViewModel()

        viewModel.updateSearchQuery("CAROL")

        val success = viewModel.uiState.value as HomeUiState.Success
        assertEquals(1, success.items.size)
        assertEquals("Carol White", success.items[0].title)
    }

    @Test
    fun `updateSearchQuery returns empty list when no match`() = runTest {
        val viewModel = buildViewModel()

        viewModel.updateSearchQuery("xyz-no-match")

        val success = viewModel.uiState.value as HomeUiState.Success
        assertEquals(0, success.items.size)
    }

    @Test
    fun `clearing search query restores full list`() = runTest {
        val viewModel = buildViewModel()

        viewModel.updateSearchQuery("alice")
        viewModel.updateSearchQuery("")

        assertEquals(3, (viewModel.uiState.value as HomeUiState.Success).items.size)
    }

    @Test
    fun `uiState reflects repository updates reactively`() = runTest {
        val usersFlow = MutableStateFlow(testUsers)
        every { userRepository.getUsers() } returns usersFlow
        val viewModel = buildViewModel()

        assertTrue(viewModel.uiState.value is HomeUiState.Success)

        val newUser = User(id = "4", displayName = "Dave Brown", email = "dave@example.com")
        usersFlow.value = testUsers + newUser

        val updated = viewModel.uiState.value as HomeUiState.Success
        assertEquals(4, updated.items.size)
    }

    @Test
    fun `uiState emits Error when repository throws`() = runTest {
        every { userRepository.getUsers() } returns flow { throw RuntimeException("network error") }

        val viewModel = buildViewModel()

        val state = viewModel.uiState.value
        assertTrue(state is HomeUiState.Error)
        assertEquals("network error", (state as HomeUiState.Error).message)
    }

    @Test
    fun `retry triggers new collection after error`() = runTest {
        every { userRepository.getUsers() } returns flow { throw RuntimeException("transient error") }
        val viewModel = buildViewModel()

        assertTrue(viewModel.uiState.value is HomeUiState.Error)

        every { userRepository.getUsers() } returns flowOf(testUsers)
        viewModel.retry()

        assertEquals(3, (viewModel.uiState.value as HomeUiState.Success).items.size)
    }
}
