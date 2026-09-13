package com.kojo.boilerplate.core.database.dao

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * What a typed query becomes before it reaches `LIKE`.
 *
 * This is the half of the search that can be tested without a database. Whether the query in
 * [UserPagingDao.pagingSource] then *uses* the pattern correctly — that its `ESCAPE` clause names
 * the same character escaped here, and that SQLite honours it — is a property of Room's generated
 * SQL running against a real SQLite, which is `androidTest` and needs an emulator this project's
 * CI does not yet run. The two halves have to agree on one character, so the escape is private to
 * the file declaring [likePattern], and the `ESCAPE` clause is written beside the only function
 * that builds a pattern for it.
 */
class SearchPatternTest {

    @Test
    fun `a plain query becomes a substring match`() {
        assertEquals("%alice%", likePattern("alice"))
    }

    @Test
    fun `a blank query matches everything`() {
        // Which is why "not searching" needs no second query and no nullable parameter: the
        // screen's default state is a pattern like any other.
        assertEquals("%%", likePattern(""))
    }

    @Test
    fun `a percent sign is matched literally rather than as a wildcard`() {
        // Unescaped, `%` would match everything — so typing one into the search field would
        // silently clear the filter instead of narrowing it.
        assertEquals("%50\\%%", likePattern("50%"))
    }

    @Test
    fun `an underscore is matched literally rather than as any single character`() {
        // Unescaped, `a_b` would also match `aXb`: a result that looks like a result.
        assertEquals("%a\\_b%", likePattern("a_b"))
    }

    @Test
    fun `the escape character escapes itself`() {
        // The case that is easy to forget. Left alone, a trailing backslash would escape the
        // closing `%` of the pattern and the query would stop being a substring match at all.
        assertEquals("%a\\\\b%", likePattern("a\\b"))
    }

    @Test
    fun `every special character in one query is escaped`() {
        assertEquals("%\\%\\_\\\\%", likePattern("%_\\"))
    }

    @Test
    fun `characters with no meaning to LIKE are left alone`() {
        // Notably the quote and the wildcards of other dialects: `LIKE` has exactly two, and
        // escaping more would turn a search for `*` into a search for `\*`.
        assertEquals("%o'brien *?%", likePattern("o'brien *?"))
    }
}
