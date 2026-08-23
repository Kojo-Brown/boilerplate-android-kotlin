package com.kojo.boilerplate.core.common.network

import kotlinx.coroutines.flow.Flow

/**
 * The device's connectivity, as a stream.
 *
 * An interface rather than the platform-backed implementation directly, so a ViewModel test
 * can hand its subject a flow it controls instead of standing up a framework service.
 * `FakeNetworkMonitor` in `:core:testing` is that double, and `:data` is where the
 * `ConnectivityManager` implementation lives — this module has no Android in it.
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
