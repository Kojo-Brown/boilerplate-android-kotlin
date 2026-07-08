package com.kojo.boilerplate.feature.home

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
class HomeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val testUsers = listOf(
        User(id = "1", displayName = "Alice Johnson", email = "alice@example.com"),
        User(id = "2", displayName = "Bob Smith", email = "bob@example.com"),
        User(id = "3", displayName = "Carol White", email = "carol@example.com"),
    )

    private lateinit var fakeRepository: FakeUserRepository
    private lateinit var viewModel: HomeViewModel

    @Before
    fun setUp() {
        fakeRepository = FakeUserRepository(testUsers)
        viewModel = HomeViewModel(
            userRepository = fakeRepository,
            ioDispatcher = UnconfinedTestDispatcher(),
        )
    }

    @Test
    fun `uiState initial value is Loading`() {
        val viewModel = HomeViewModel(
            userRepository = FakeUserRepository(),
            ioDispatcher = UnconfinedTestDispatcher(),
        )
        assertEquals(HomeUiState.Loading, viewModel.uiState.value)
    }

    @Test
    fun `uiState emits Success with all users when search query is empty`() = runTest {
        val state = viewModel.uiState.first { it is HomeUiState.Success }

        assertTrue(state is HomeUiState.Success)
        val success = state as HomeUiState.Success
        assertEquals(3, success.items.size)
        assertEquals("Alice Johnson", success.items[0].title)
        assertEquals("alice@example.com", success.items[0].description)
    }

    @Test
    fun `updateSearchQuery filters users by display name`() = runTest {
        viewModel.updateSearchQuery("alice")

        val state = viewModel.uiState.first { it is HomeUiState.Success }
        val success = state as HomeUiState.Success
        assertEquals(1, success.items.size)
        assertEquals("Alice Johnson", success.items[0].title)
    }

    @Test
    fun `updateSearchQuery filters users by email`() = runTest {
        viewModel.updateSearchQuery("bob@")

        val state = viewModel.uiState.first { it is HomeUiState.Success }
        val success = state as HomeUiState.Success
        assertEquals(1, success.items.size)
        assertEquals("Bob Smith", success.items[0].title)
    }

    @Test
    fun `updateSearchQuery is case insensitive`() = runTest {
        viewModel.updateSearchQuery("CAROL")

        val state = viewModel.uiState.first { it is HomeUiState.Success }
        val success = state as HomeUiState.Success
        assertEquals(1, success.items.size)
        assertEquals("Carol White", success.items[0].title)
    }

    @Test
    fun `updateSearchQuery returns empty list when no match`() = runTest {
        viewModel.updateSearchQuery("xyz-no-match")

        val state = viewModel.uiState.first { it is HomeUiState.Success }
        val success = state as HomeUiState.Success
        assertEquals(0, success.items.size)
    }

    @Test
    fun `clearing search query restores full list`() = runTest {
        viewModel.updateSearchQuery("alice")
        viewModel.updateSearchQuery("")

        val state = viewModel.uiState.first { it is HomeUiState.Success }
        assertEquals(3, (state as HomeUiState.Success).items.size)
    }

    @Test
    fun `uiState reflects repository updates reactively`() = runTest {
        viewModel.uiState.first { it is HomeUiState.Success }

        val newUser = User(id = "4", displayName = "Dave Brown", email = "dave@example.com")
        fakeRepository.saveUser(newUser)

        val updated = viewModel.uiState.first {
            it is HomeUiState.Success && (it as HomeUiState.Success).items.size == 4
        }
        assertEquals(4, (updated as HomeUiState.Success).items.size)
    }

    @Test
    fun `uiState emits Error when repository throws`() = runTest {
        fakeRepository.shouldThrowOnGetUsers = RuntimeException("network error")

        val errorViewModel = HomeViewModel(
            userRepository = fakeRepository,
            ioDispatcher = UnconfinedTestDispatcher(),
        )

        val state = errorViewModel.uiState.first { it is HomeUiState.Error }
        assertEquals("network error", (state as HomeUiState.Error).message)
    }

    @Test
    fun `retry triggers a new collection after error`() = runTest {
        fakeRepository.shouldThrowOnGetUsers = RuntimeException("transient error")

        val errorViewModel = HomeViewModel(
            userRepository = fakeRepository,
            ioDispatcher = UnconfinedTestDispatcher(),
        )
        errorViewModel.uiState.first { it is HomeUiState.Error }

        fakeRepository.shouldThrowOnGetUsers = null
        errorViewModel.retry()

        val state = errorViewModel.uiState.first { it is HomeUiState.Success }
        assertEquals(3, (state as HomeUiState.Success).items.size)
    }
}
