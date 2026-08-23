package com.kojo.boilerplate.feature.profile

import com.kojo.boilerplate.core.ui.udf.UiEvent

/**
 * Everything a profile screen can be told, shared by [ProfileViewModel] and
 * [ProfileDetailPaneViewModel].
 *
 * One type for both because the two screens offer the user exactly the same thing — the same
 * error page with the same retry button — and differ only in where their user id comes from.
 * That is the same argument `ProfileUiStateMapping` is shared on: a second copy would be a
 * duplicate that drifts, not a distinction.
 */
sealed interface ProfileUiEvent : UiEvent {

    /** The retry button on the error state. Resubscribes to the profile. */
    data object RetryClicked : ProfileUiEvent
}
