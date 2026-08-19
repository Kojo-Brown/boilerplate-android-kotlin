package com.kojo.boilerplate.core.event

import com.kojo.boilerplate.core.auth.GoogleAuthRepository
import javax.inject.Inject

/**
 * Drops the Google credential state when the server-side session dies.
 *
 * [com.kojo.boilerplate.core.network.TokenAuthenticator] clears this app's own tokens on its
 * way to publishing [AppEvent.SessionExpired], but Credential Manager keeps its own record of
 * who was authorised, and nothing in the network layer knows it exists. Left alone, the next
 * sign-in can be answered from that record without showing the picker — so a user whose
 * session was revoked is re-authorised as the same account they were just ejected from, with
 * no way to choose a different one.
 *
 * This is an [AppEventListener] rather than a collector in some screen because it has to
 * happen whether or not anything is on screen: a session usually dies while the app is in the
 * background, and a reaction that only runs when the UI is started is a reaction that mostly
 * does not run.
 */
class SessionExpiryCredentialListener @Inject constructor(
    private val googleAuthRepository: GoogleAuthRepository,
) : AppEventListener {

    override suspend fun onEvent(event: AppEvent) {
        if (event !is AppEvent.SessionExpired) return

        // Surfaced rather than swallowed: a failure to clear the credential state leaves the
        // app in the state described above, which is worth a report. AppEventDispatcher
        // reports it and keeps every other listener subscribed.
        googleAuthRepository.signOut().getOrThrow()
    }
}
