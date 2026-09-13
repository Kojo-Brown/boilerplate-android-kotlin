package com.kojo.boilerplate.core.database.dao

/**
 * The escape character named by the `ESCAPE` clause of [UserPagingDao.pagingSource].
 *
 * A backslash because it is the conventional one and because no other character is safer: SQLite
 * has no default escape character at all, so whatever is chosen has to be escaped in the input
 * too, and [likePattern] does that for this one along with the wildcards.
 */
private const val LIKE_ESCAPE = '\\'

/** The two characters `LIKE` reads as wildcards, plus the escape character itself. */
private val LIKE_SPECIAL = setOf('%', '_', LIKE_ESCAPE)

/**
 * Turns what a user typed into a `LIKE` pattern that matches it as a substring and nothing else.
 *
 * ## Why this is not `"%$query%"`
 *
 * Because `%` and `_` are wildcards to `LIKE`, so the obvious interpolation hands the user a
 * query language they did not ask for and cannot see. Typing `_` would match any single
 * character — so a search for `a_b` silently returns `aXb` — and typing `%` would match
 * everything, turning the search field into a way to clear the filter by accident. Neither
 * produces an error; both produce a list that is wrong in a way that looks like a result.
 *
 * Escaping them with [LIKE_ESCAPE] and naming that character in the query's `ESCAPE` clause makes
 * every character the user typed mean itself. The escape character is escaped as well, which is
 * the case that is easy to forget and the one that would otherwise let a lone backslash swallow
 * the character after it.
 *
 * ## What a blank query produces
 *
 * `%%`, which matches every non-null value — so "no search" needs no second query, no nullable
 * parameter and no `:query = ''` branch in the SQL. Both columns it is compared against are
 * declared non-null, so there is no row this silently excludes.
 *
 * ## Case
 *
 * SQLite's `LIKE` is case-insensitive for ASCII and case-*sensitive* for everything else, which
 * is the one behavioural difference from the in-memory `contains(ignoreCase = true)` this
 * replaced. Making it uniform means `LIKE … COLLATE NOCASE` (which is ASCII-only too) or an ICU
 * build, so the honest position is that it is ASCII-insensitive and written down here rather
 * than quietly assumed. Fixing it properly is a collation decision for the whole `users` table,
 * not something for one query to take unilaterally.
 */
internal fun likePattern(query: String): String = buildString(query.length + 2) {
    append('%')
    query.forEach { character ->
        if (character in LIKE_SPECIAL) append(LIKE_ESCAPE)
        append(character)
    }
    append('%')
}
