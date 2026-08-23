package com.kojo.boilerplate.core.network.connectivity

import android.net.NetworkCapabilities
import com.kojo.boilerplate.core.common.network.NetworkStatus

/**
 * Reads a [NetworkStatus] out of the capabilities the platform reports for a network.
 *
 * It sits here rather than next to [NetworkStatus] because it is the half of that file that
 * knows about `android.net`: the type is the vocabulary every layer shares and lives in
 * `:core:common`, while translating the framework's bitmask into it is the data layer's job
 * and belongs beside the `ConnectivityManager` callback that calls it.
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
