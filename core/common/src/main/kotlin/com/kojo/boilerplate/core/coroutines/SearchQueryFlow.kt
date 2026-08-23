package com.kojo.boilerplate.core.coroutines

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Long enough to cover the gap between two keystrokes of ordinary typing, short enough that a
 * user who has stopped does not notice waiting. Material's guidance and most production search
 * fields land in the 250–400ms band.
 */
private val DEFAULT_SEARCH_DEBOUNCE = 300.milliseconds

/**
 * Turns a stream of raw text-field values into the stream of queries actually worth acting on.
 *
 * The text field itself must never be debounced — the character has to appear as it is typed —
 * so this belongs on the *derived* flow, between the field's state and whatever the query
 * drives. Binding the field to a debounced flow instead is the classic version of this bug: the
 * cursor stutters and typing feels broken.
 *
 * Three operators, each removing a different kind of redundant work:
 *
 * - `map { it.trim() }` collapses the whitespace a soft keyboard adds after a word, so
 *   `"alice"` and `"alice "` are one query rather than two.
 * - [debounce] drops every value superseded within [debounceTimeout]. Typing `alice` emits five
 *   values and one query. The timeout is computed per value so that an *empty* query — the user
 *   clearing the field, or the very first value a `MutableStateFlow("")` replays on
 *   subscription — passes straight through: "show me everything again" needs no rate limiting,
 *   and delaying the initial value would put a debounce-length flash of the loading state in
 *   front of every cold start.
 * - [distinctUntilChanged] absorbs what trimming just made equal. `"alice "` → `"alice"`
 *   arrives as a change to the upstream `StateFlow` but is not a change to the query, and
 *   without this it re-runs the search.
 *
 * ```kotlin
 * private val query = MutableStateFlow("")
 * val fieldValue: StateFlow<String> = query.asStateFlow()      // undebounced, drives the field
 * val results = query.asSearchQueries().flatMapLatest(::search) // debounced, drives the work
 * ```
 */
@OptIn(FlowPreview::class) // debounce is still @FlowPreview in kotlinx-coroutines 1.9.0.
fun Flow<String>.asSearchQueries(debounceTimeout: Duration = DEFAULT_SEARCH_DEBOUNCE): Flow<String> =
    map { raw -> raw.trim() }
        .debounce { query: String -> if (query.isEmpty()) Duration.ZERO else debounceTimeout }
        .distinctUntilChanged()
