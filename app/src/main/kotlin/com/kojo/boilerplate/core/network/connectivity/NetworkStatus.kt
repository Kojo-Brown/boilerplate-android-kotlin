package com.kojo.boilerplate.core.network.connectivity

import android.net.NetworkCapabilities

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

    /**
     * No default network, or one that does not claim
     * [NetworkCapabilities.NET_CAPABILITY_INTERNET].
     */
    data object Offline : NetworkStatus {
        override val isOnline: Boolean get() = false
    }

    /**
     * A default network is up and claims internet access.
     *
     * @param isValidated the platform has actually reached the internet over this network
     *   ([NetworkCapabilities.NET_CAPABILITY_VALIDATED]). `false` is the captive-portal case:
     *   requests will connect and come back with the portal's login page rather than failing,
     *   so it is not something a retry can fix.
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

/**
 * Reads a [NetworkStatus] out of the capabilities the platform reports for a network.
 *
 * `NET_CAPABILITY_INTERNET` is the network's own claim about itself and is what decides
 * [NetworkStatus.Offline] here; `NET_CAPABILITY_VALIDATED` is the platform's verdict after
 * probing, and is reported rather than gated on so a caller can tell "no network" apart from
 * "a network that will answer with a login page".
 *
 * Metering is expressed by the platform as the *absence* of `NET_CAPABILITY_NOT_METERED`, so
 * the negation here is the framework's, not a double negative introduced by this code.
 */
internal fun NetworkCapabilities.toNetworkStatus(): NetworkStatus =
    if (!hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
        NetworkStatus.Offline
    } else {
        NetworkStatus.Online(
            isValidated = hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            isMetered = !hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
        )
    }
