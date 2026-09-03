package com.kojo.boilerplate.core.domain.model

/**
 * How a push of this device's unsent edits ended: how many rows the server acknowledged, and
 * how many were attempted and did not land.
 *
 * The mirror image of [RefreshOutcome], and shaped like it for the same reason: the rows
 * themselves are not the result. Every acknowledged push is written back to the database by the
 * repository and the screens observing that database re-render themselves, so what the caller
 * cannot learn by looking at the list is the shortfall — and that is the entire return value.
 *
 * It is a separate type rather than a reuse of [RefreshOutcome] because the two count different
 * things and a caller that adds them up is making a mistake worth failing to compile. A refresh
 * that fetched nothing means the screen is showing stale rows; a push that sent nothing means
 * this device is holding edits the server has never seen. Named counts keep the two readable at
 * the call site — `PerformBackgroundSyncUseCase` reports on both in one run.
 *
 * [pushed] and [failed] together account for every distinct row that had something pending, so
 * `attempted == 0` means there was nothing to send rather than that every send failed.
 */
data class PushOutcome(
    val pushed: Int,
    val failed: Int,
) {

    /** How many rows with pending edits the push attempted. */
    val attempted: Int get() = pushed + failed

    init {
        require(pushed >= 0) { "pushed must not be negative, was $pushed" }
        require(failed >= 0) { "failed must not be negative, was $failed" }
    }

    companion object {
        /** No row was holding an unsent edit, so nothing was sent. */
        val NOTHING_TO_PUSH = PushOutcome(pushed = 0, failed = 0)
    }
}
