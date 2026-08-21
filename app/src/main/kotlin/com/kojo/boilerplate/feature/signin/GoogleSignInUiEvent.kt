package com.kojo.boilerplate.feature.signin

import android.content.Context
import com.kojo.boilerplate.core.ui.udf.UiEvent

/**
 * Everything the sign-in screen can be told.
 *
 * [SignInClicked] carries a [Context] because Credential Manager needs the Activity it is to
 * draw its bottom sheet over, and only the composable can supply one. It is passed through and
 * not retained: the view model hands it straight to the repository for the duration of the
 * call and holds no reference of its own, which is what keeps this from being the Activity
 * leak that a `Context` on a view model usually is.
 */
sealed interface GoogleSignInUiEvent : UiEvent {

    /** The sign-in button. [activityContext] must be the Activity, not the application. */
    data class SignInClicked(val activityContext: Context) : GoogleSignInUiEvent

    /** The sign-out button on the signed-in state. */
    data object SignOutClicked : GoogleSignInUiEvent
}
