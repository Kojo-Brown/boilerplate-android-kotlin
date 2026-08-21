package com.kojo.boilerplate.feature.signin

import com.kojo.boilerplate.core.auth.GoogleUser
import com.kojo.boilerplate.core.ui.udf.UiEffect

/**
 * Things that happen once on the sign-in screen, as opposed to [GoogleSignInUiState], which is
 * what the screen looks like at any moment.
 *
 * The test for which of the two a value belongs in: if the screen were destroyed and rebuilt
 * right now — a rotation, a theme change, a return from the background — should it happen
 * again? A spinner should still be on screen, so it is state. A snackbar that has already been
 * shown should not be shown a second time, so it is an effect.
 *
 * This is the one screen in the app where both members are things the *view model* decides
 * rather than things the composable could have done itself, which is what earns it a real
 * effect type instead of `Nothing`: whether a failure is worth telling the user about is a
 * judgement made below, on the exception.
 */
sealed interface GoogleSignInUiEffect : UiEffect {

    /** Authentication succeeded; the caller navigates on. */
    data class SignedIn(val user: GoogleUser) : GoogleSignInUiEffect

    /**
     * Authentication failed for a reason worth telling the user about. Deliberately not
     * raised when the user dismisses the credential picker: they know they cancelled, and a
     * snackbar saying so is noise.
     */
    data class SignInFailed(val message: String) : GoogleSignInUiEffect
}
