package com.kojo.boilerplate.feature.home

import com.kojo.boilerplate.core.ui.udf.UiEffect

/**
 * What the home screen does once.
 *
 * ### Why the refresh outcome moved here
 *
 * It used to be a third state, `RefreshState.Finished(refreshed, failed)`, rendered as a
 * banner with a Dismiss button and cleared by a `dismissRefreshResult()` call. Rule 2 of
 * `docs/state-and-events.md` is that state which has to be cleared by hand after it is read is
 * an event wearing state's clothes, and the manual clear is the part that does not survive a
 * configuration change: the banner was still up, still undismissed, after every rotation until
 * someone pressed the button — and pressing it during a rotation lost the press.
 *
 * The question the rule asks settles it. If the screen were destroyed and rebuilt right now,
 * should the report of what the *last* refresh failed to fetch appear again? No: the rows it
 * describes are on screen and unchanged, and the user has read the sentence. A snackbar
 * delivered once and gone is what that is.
 *
 * The successes need no effect of their own. They were written to the database by the
 * repository, and the list observes the database, so the rows update themselves — the only
 * thing left to report is the shortfall.
 */
sealed interface HomeUiEffect : UiEffect {

    /**
     * A refresh finished without covering everything it attempted. [refreshed] and [failed]
     * together account for every user the fan-out tried, so this is never raised with
     * `failed == 0` — a clean refresh is already visible in the rows, and "everything worked"
     * is a message the user has to dismiss to get their screen back.
     */
    data class RefreshIncomplete(
        val refreshed: Int,
        val failed: Int,
    ) : HomeUiEffect
}
