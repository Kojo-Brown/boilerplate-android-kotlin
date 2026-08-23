package com.kojo.boilerplate.core.network.connectivity

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.kojo.boilerplate.core.common.network.NetworkMonitor
import com.kojo.boilerplate.core.common.network.NetworkStatus
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * [NetworkMonitor] over [ConnectivityManager.NetworkCallback] — the reference example in this
 * repo of turning a register/unregister listener API into a `Flow`.
 *
 * ## Why `callbackFlow` and not `flow`
 *
 * A plain `flow { }` builder may only emit from the coroutine that is collecting it; emitting
 * from anywhere else fails at runtime with `IllegalStateException: Flow invariant is violated`.
 * The platform delivers network callbacks on its own thread, so every emission here crosses a
 * thread boundary and the plain builder is not an option. [callbackFlow] exists for exactly
 * this shape: it puts a channel between the callback and the collector, which makes
 * `trySend` safe to call from any thread and non-suspending — which matters, because
 * `onLost` is a `void` method that cannot wait for a slow collector.
 *
 * ## Why `awaitClose` is not optional
 *
 * The producer block runs once and returns; without a suspension at the end, `callbackFlow`
 * would close the channel the moment registration finished and the collector would see an
 * empty stream. [awaitClose] is that suspension, and it is a hard requirement —
 * `callbackFlow` throws `IllegalStateException` if the block returns without it.
 *
 * More importantly it is the only place the unregister can go. Cancelling a collector — a
 * ViewModel clearing, a `WhileSubscribed` window expiring, a screen leaving composition —
 * cancels this coroutine, and `awaitClose` runs its block on the way out. A registration
 * leaked here is not merely garbage: the system holds a strong reference to the callback and
 * keeps waking the process on every network change for as long as it does.
 *
 * ## Buffering
 *
 * `conflate()` fuses with the channel `callbackFlow` allocates, so the buffer holds exactly
 * one status. Without it, the default 64-element buffer would eventually fill and `trySend`
 * — which cannot suspend — would silently drop the *newest* status while serving stale ones.
 * Conflation inverts that: what gets dropped is the value already superseded, which for a
 * "what is the state now" stream is the only correct thing to drop.
 *
 * `distinctUntilChanged()` because `onCapabilitiesChanged` fires on changes this class does
 * not model — signal strength, link speed, transport hand-offs within the same status — and
 * every one of them would otherwise recompose a screen for no reason.
 */
class ConnectivityManagerNetworkMonitor @Inject constructor(
    private val connectivityManager: ConnectivityManager,
) : NetworkMonitor {

    override val networkStatus: Flow<NetworkStatus> = callbackFlow<NetworkStatus> {
        val callback = defaultNetworkCallback { status -> trySend(status) }

        connectivityManager.registerDefaultNetworkCallback(callback)

        // Seeded *after* registering, and the order is the point. A NetworkCallback reports
        // transitions only, so a collector that subscribes while already offline would
        // otherwise sit on nothing until connectivity changed — for a screen that opened in
        // a lift, possibly never. Reading the current state before registering would instead
        // leave a window in which a change is missed permanently. Reading it after closes
        // both: the snapshot is strictly newer than anything the callback could have
        // delivered in between, and the conflated buffer keeps the newer of the two.
        trySend(currentStatus())

        awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
    }
        .conflate()
        .distinctUntilChanged()

    /**
     * A callback for the *default* network — the one the system would route a new socket over
     * — rather than a `NetworkRequest` for every matching network. That is the question a UI
     * is asking, and it means this class never has to reconcile a set of networks.
     *
     * The tracked network exists for one case that is otherwise a bug. On a hand-off — wifi
     * dropping to cellular — the platform reports the *new* default with `onAvailable` before
     * it reports the old one with `onLost`. A callback that treats every `onLost` as "offline"
     * therefore ends up offline immediately after coming back online, and stays there until
     * something else changes. Only the loss of the network currently believed to be the
     * default counts, and `compareAndSet` makes that check and the clear a single step.
     *
     * Callbacks for one registration are delivered serially, so the atomic is for visibility
     * across the delivery thread and the collector rather than for contention.
     */
    private fun defaultNetworkCallback(
        emit: (NetworkStatus) -> Unit,
    ): ConnectivityManager.NetworkCallback {
        val defaultNetwork = AtomicReference<Network?>(null)

        return object : ConnectivityManager.NetworkCallback() {

            override fun onAvailable(network: Network) {
                defaultNetwork.set(network)
                emit(statusOf(network))
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                defaultNetwork.set(network)
                emit(networkCapabilities.toNetworkStatus())
            }

            override fun onLost(network: Network) {
                if (defaultNetwork.compareAndSet(network, null)) {
                    emit(NetworkStatus.Offline)
                }
            }
        }
    }

    private fun currentStatus(): NetworkStatus =
        connectivityManager.activeNetwork?.let(::statusOf) ?: NetworkStatus.Offline

    /**
     * Capabilities are read back from the manager rather than cached: between a network
     * becoming available and this call it may already have been validated, and the fresher
     * answer is the correct one. A `null` here means the network went away in that gap.
     */
    private fun statusOf(network: Network): NetworkStatus =
        connectivityManager.getNetworkCapabilities(network)?.toNetworkStatus()
            ?: NetworkStatus.Offline
}
