package com.kojo.boilerplate.feature.signin

import com.kojo.boilerplate.core.auth.GoogleUser

/**
 * Things that happen once on the sign-in screen, as opposed to [GoogleSignInUiState], which is
 * what the screen looks like at any moment.
 *
 * The test for which of the two a value belongs in: if the screen were destroyed and rebuilt
 * right now — a rotation, a theme change, a return from the background — should it happen
 * again? A spinner should still be on screen, so it is state. A snackbar that has already been
 * shown should not be shown a second time, so it is an event.
 */
sealed interface GoogleSignInEvent {

    /** Authentication succeeded; the caller navigates on. */
    data class SignedIn(val user: GoogleUser) : GoogleSignInEvent

    /**
     * Authentication failed for a reason worth telling the user about. Deliberately not
     * raised when the user dismisses the credential picker: they know they cancelled, and a
     * snackbar saying so is noise.
     */
    data class SignInFailed(val message: String) : GoogleSignInEvent
}
