package com.kojo.boilerplate.feature.home

import androidx.paging.PagingData
import com.kojo.boilerplate.core.domain.model.User
import com.kojo.boilerplate.core.paging.PagedUserRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * A [PagedUserRepository] that records the searches it was asked for.
 *
 * ## Why the queries are the subject and the pages are not
 *
 * Because the queries are what this module decides. Which rows come back for a query is settled
 * by a `LIKE` in `UserPagingDao` and by SQLite, one module away and behind a real database;
 * *which query is asked for, and how often* is the whole of `HomeViewModel`'s contribution to
 * the search — the trimming, the debounce, and the `flatMapLatest` that cancels the `Pager` the
 * previous keystroke built.
 *
 * So this fake does the matching only so that a page has something plausible in it, and the
 * assertions are about [queries]. It is a substring match and not the DAO's `LIKE`: pretending
 * to reimplement the SQL would create a second definition of the search that could quietly
 * disagree with the real one, which is the failure mode a fake is most likely to introduce.
 *
 * A `PagingData` cannot be read back without `androidx.paging:paging-testing`, so nothing here
 * asserts the rows a page carries; `HomeViewModelTest` covers the mapping by calling
 * `toHomeItem` directly instead.
 */
class FakePagedUserRepository(private val all: List<User> = emptyList()) : PagedUserRepository {

    private val recorded = mutableListOf<String>()

    /** Every query this was asked for, in order, including the empty one a screen starts at. */
    val queries: List<String> get() = recorded.toList()

    override fun users(query: String): Flow<PagingData<User>> {
        recorded += query
        val matches = all.filter { user ->
            query.isBlank() ||
                user.displayName.contains(query, ignoreCase = true) ||
                user.email.contains(query, ignoreCase = true)
        }
        return flowOf(PagingData.from(matches))
    }
}
