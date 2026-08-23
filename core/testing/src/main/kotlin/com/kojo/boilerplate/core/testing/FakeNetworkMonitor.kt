package com.kojo.boilerplate.core.testing

import com.kojo.boilerplate.core.common.network.NetworkMonitor
import com.kojo.boilerplate.core.common.network.NetworkStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A [NetworkMonitor] a test drives directly.
 *
 * Backed by a `MutableStateFlow` so it behaves the way the real monitor does in the two ways
 * a consumer can depend on: a subscriber gets the current status immediately rather than
 * waiting for a change, and consecutive duplicates are conflated away.
 */
class FakeNetworkMonitor(
    initialStatus: NetworkStatus = ONLINE,
) : NetworkMonitor {

    private val status = MutableStateFlow(initialStatus)

    override val networkStatus: Flow<NetworkStatus> = status.asStateFlow()

    fun emit(status: NetworkStatus) {
        this.status.value = status
    }

    companion object {
        /** Unmetered wifi that the platform has confirmed reaches the internet. */
        val ONLINE = NetworkStatus.Online(isValidated = true, isMetered = false)
    }
}
