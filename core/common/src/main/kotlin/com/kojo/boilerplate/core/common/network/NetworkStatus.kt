package com.kojo.boilerplate.core.common.network

/**
 * What the device's default network can do right now, as far as the platform is willing to say.
 *
 * Deliberately not a `Boolean`. "Connected" is three separate questions and collapsing them
 * loses the two that change behaviour: a network can be joined but not carry traffic (a captive
 * portal that has not been signed into), and it can carry traffic but charge for it.
 */
sealed interface NetworkStatus {

    /** Whether traffic can be attempted at all. */
    val isOnline: Boolean

    /** No default network, or one that does not claim `NET_CAPABILITY_INTERNET`. */
    data object Offline : NetworkStatus {
        override val isOnline: Boolean get() = false
    }

    /**
     * A default network is up and claims internet access.
     *
     * @param isValidated the platform has actually reached the internet over this network
     *   (`NET_CAPABILITY_VALIDATED`). `false` is the captive-portal case: requests will connect
     *   and come back with the portal's login page rather than failing, so it is not something
     *   a retry can fix.
     * @param isMetered the user pays per byte on this network. Background prefetching and
     *   large uploads should wait; a request the user is watching should not.
     */
    data class Online(
        val isValidated: Boolean,
        val isMetered: Boolean,
    ) : NetworkStatus {
        override val isOnline: Boolean get() = true
    }
}
