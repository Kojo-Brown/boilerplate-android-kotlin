package com.kojo.boilerplate.core.domain.model

/**
 * How a refresh of the visible users ended: how many rows were re-fetched, and how many were
 * asked for and did not arrive.
 *
 * Both halves are counts rather than the users themselves, because the users are not the
 * result. A successful re-fetch is written to the database by the repository and the list
 * observing that database re-renders itself, so the rows need no return path — see
 * `UserRepository.syncUsers`. What the caller cannot learn from the list is the shortfall,
 * and that is what this carries.
 *
 * [refreshed] and [failed] together account for every *distinct* id the refresh attempted, so
 * `attempted == 0` means there was nothing on screen to refresh rather than that a fan-out
 * came back empty.
 */
data class RefreshOutcome(
    val refreshed: Int,
    val failed: Int,
) {

    /** How many distinct users the refresh attempted. */
    val attempted: Int get() = refreshed + failed

    init {
        require(refreshed >= 0) { "refreshed must not be negative, was $refreshed" }
        require(failed >= 0) { "failed must not be negative, was $failed" }
    }

    companion object {
        /** Nothing was visible, so nothing was requested. */
        val NOTHING_TO_REFRESH = RefreshOutcome(refreshed = 0, failed = 0)
    }
}
