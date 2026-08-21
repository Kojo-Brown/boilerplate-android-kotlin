package com.kojo.boilerplate.core.event

/**
 * Something that happened to the *application*, broadcast to every part of it that reacts.
 *
 * This is the narrow case `docs/state-and-events.md` rule 4 sets aside for `SharedFlow`, and
 * the bar for membership is deliberately high — an event bus is the easiest abstraction in an
 * app to turn into a place where anything can cause anything. Three questions, all of which
 * have to answer yes:
 *
 * 1. **Is it an event at all?** If it should still be true after the screen is destroyed and
 *    rebuilt, it is state, and state belongs in a `StateFlow` somebody observes. Connectivity
 *    is the near miss: "the network came back" sounds like news, but "is there a network" is a
 *    value with a current answer, and [com.kojo.boilerplate.core.network.connectivity.NetworkMonitor]
 *    already exposes it as one.
 * 2. **Do two or more independent things react?** One listener means the producer should call
 *    it. A bus with a single subscriber is an indirection that costs a reader the ability to
 *    find the caller.
 * 3. **Would a direct call couple layers that should not know each other?** The reactions to
 *    [SessionExpired] live in the auth layer and in navigation; the producer is an OkHttp
 *    `Authenticator`. Wiring those together directly would give the network layer a reference
 *    to the navigation graph.
 *
 * A per-screen one-shot — a snackbar, a "navigate on success" — fails question 2 and belongs
 * in the screen's own `Channel`, which is what
 * [com.kojo.boilerplate.feature.signin.GoogleSignInUiEffect] is. See `docs/event-bus.md`.
 */
sealed interface AppEvent {

    /**
     * The refresh token was rejected, so the app no longer has a usable session and the stored
     * tokens have been cleared.
     *
     * Raised by [com.kojo.boilerplate.core.network.TokenAuthenticator] on the one path where
     * that is knowable: a 401 arrived, a refresh token existed, and the refresh call for it
     * failed. A request that was simply never authenticated — no refresh token in the first
     * place — is not an expiry, because there was no session to lose.
     *
     * A `data object` and not a `data class`: it carries no payload because there is nothing
     * useful to say beyond the fact. Which request happened to be in flight when the session
     * died is an accident of timing, and a listener that branched on it would be reacting to
     * the wrong thing.
     */
    data object SessionExpired : AppEvent
}
