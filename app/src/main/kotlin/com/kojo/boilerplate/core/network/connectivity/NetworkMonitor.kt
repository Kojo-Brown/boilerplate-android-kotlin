package com.kojo.boilerplate.core.network.connectivity

import kotlinx.coroutines.flow.Flow

/**
 * The device's connectivity, as a stream.
 *
 * An interface rather than the [ConnectivityManagerNetworkMonitor] class directly, so a
 * ViewModel test can hand its subject a flow it controls instead of standing up a framework
 * service. `FakeNetworkMonitor` in the test source set is that double.
 */
interface NetworkMonitor {

    /**
     * Emits the current [NetworkStatus] on subscription and again whenever it changes.
     *
     * Cold: each collector registers its own platform callback and releases it when collection
     * ends. That is the right default for a per-screen consumer, and it is what makes the
     * lifetime of the registration exactly the lifetime of the collection. A caller that wants
     * one registration shared across several collectors should say so explicitly with
     * `shareIn`/`stateIn` — see `docs/callback-flow.md`.
     *
     * Consecutive duplicates are already filtered, and the stream is conflated: a collector
     * that falls behind sees the latest status, never a backlog of stale ones.
     */
    val networkStatus: Flow<NetworkStatus>
}
