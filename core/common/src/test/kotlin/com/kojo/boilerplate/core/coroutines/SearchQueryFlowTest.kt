package com.kojo.boilerplate.core.coroutines

import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchQueryFlowTest {

    private val debounce = 300.milliseconds

    @Test
    fun `rapid keystrokes collapse into the last query`() = runTest {
        val field = MutableStateFlow("")
        val queries = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            field.asSearchQueries(debounce).collect { queries += it }
        }
        runCurrent()

        "alice".forEachIndexed { index, _ -> field.value = "alice".take(index + 1) }
        advanceTimeBy(debounce + 1.milliseconds)

        // The empty initial value is not rate-limited; the five keystrokes are one query.
        assertEquals(listOf("", "alice"), queries)
    }

    @Test
    fun `a query held for longer than the timeout is emitted`() = runTest {
        val field = MutableStateFlow("")
        val queries = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            field.asSearchQueries(debounce).collect { queries += it }
        }
        runCurrent()

        field.value = "ali"
        advanceTimeBy(debounce + 1.milliseconds)
        field.value = "alice"
        advanceTimeBy(debounce + 1.milliseconds)

        assertEquals(listOf("", "ali", "alice"), queries)
    }

    @Test
    fun `the initial empty value is not delayed`() = runTest {
        val field = MutableStateFlow("")
        val queries = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            field.asSearchQueries(debounce).collect { queries += it }
        }

        runCurrent()

        // Nothing has been advanced: a debounced initial value would put a loading flash in
        // front of every cold start, so an empty query is passed through immediately.
        assertEquals(listOf(""), queries)
        assertEquals(0L, currentTime)
    }

    @Test
    fun `clearing the field takes effect immediately`() = runTest {
        val field = MutableStateFlow("")
        val queries = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            field.asSearchQueries(debounce).collect { queries += it }
        }
        runCurrent()

        field.value = "alice"
        advanceTimeBy(debounce + 1.milliseconds)
        field.value = ""
        runCurrent()

        assertEquals(listOf("", "alice", ""), queries)
    }

    @Test
    fun `surrounding whitespace does not make a new query`() = runTest {
        val queries = flowOf("alice", "alice ", " alice", "alice  ").asSearchQueries(debounce).toList()

        assertEquals(listOf("alice"), queries)
    }

    @Test
    fun `trimming is applied before the query is handed on`() = runTest {
        val queries = flowOf("  alice  ").asSearchQueries(debounce).toList()

        assertEquals(listOf("alice"), queries)
    }
}
