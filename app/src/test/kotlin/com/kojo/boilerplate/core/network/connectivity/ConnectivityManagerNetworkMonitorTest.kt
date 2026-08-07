package com.kojo.boilerplate.core.network.connectivity

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The platform side is a mock, so what these tests actually pin is this class's half of the
 * contract: which callbacks produce which status, what happens to the registration when a
 * collector goes away, and the two behaviours a `NetworkCallback` does not give you for free
 * — an initial value, and surviving a network hand-off.
 *
 * Every callback invocation is followed by [runCurrent] because the flow is conflated: two
 * statuses pushed without letting the collector run would leave only the second, and a test
 * that skipped the pump would be asserting on the buffer rather than on the stream.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectivityManagerNetworkMonitorTest {

    private val connectivityManager: ConnectivityManager = mockk(relaxUnitFun = true)
    private val registeredCallback = slot<ConnectivityManager.NetworkCallback>()
    private val monitor = ConnectivityManagerNetworkMonitor(connectivityManager)

    private val wifi: Network = mockk()
    private val cellular: Network = mockk()

    @BeforeEach
    fun setUp() {
        every {
            connectivityManager.registerDefaultNetworkCallback(capture(registeredCallback))
        } returns Unit
        // Overridden per test; the default is "nothing is connected", which is also the state
        // the seeding path has to handle correctly.
        every { connectivityManager.activeNetwork } returns null
    }

    /**
     * @param hasInternet the network's own claim that it routes to the internet.
     * @param isValidated the platform's verdict after probing it.
     * @param isNotMetered the framework expresses metering as the absence of this capability.
     */
    private fun capabilities(
        hasInternet: Boolean = true,
        isValidated: Boolean = true,
        isNotMetered: Boolean = true,
    ): NetworkCapabilities = mockk {
        every { hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns hasInternet
        every { hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } returns isValidated
        every {
            hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        } returns isNotMetered
    }

    private fun connect(network: Network, capabilities: NetworkCapabilities) {
        every { connectivityManager.activeNetwork } returns network
        every { connectivityManager.getNetworkCapabilities(network) } returns capabilities
    }

    private fun TestScope.collectStatuses(into: MutableList<NetworkStatus>): Job {
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            monitor.networkStatus.toList(into)
        }
        runCurrent()
        return job
    }

    @Test
    fun `a subscriber is given the current status without waiting for a change`() = runTest {
        connect(wifi, capabilities(isValidated = true, isNotMetered = true))
        val statuses = mutableListOf<NetworkStatus>()

        collectStatuses(statuses)

        // The whole reason the flow seeds itself: a NetworkCallback reports transitions only,
        // so without this the list would be empty until connectivity happened to change.
        assertEquals(
            listOf(NetworkStatus.Online(isValidated = true, isMetered = false)),
            statuses,
        )
    }

    @Test
    fun `no active network seeds Offline`() = runTest {
        val statuses = mutableListOf<NetworkStatus>()

        collectStatuses(statuses)

        assertEquals(listOf<NetworkStatus>(NetworkStatus.Offline), statuses)
    }

    @Test
    fun `a network that does not claim internet access is Offline`() = runTest {
        connect(wifi, capabilities(hasInternet = false))
        val statuses = mutableListOf<NetworkStatus>()

        collectStatuses(statuses)

        assertEquals(listOf<NetworkStatus>(NetworkStatus.Offline), statuses)
    }

    @Test
    fun `an unvalidated metered network is reported as such rather than flattened`() = runTest {
        connect(wifi, capabilities(isValidated = false, isNotMetered = false))
        val statuses = mutableListOf<NetworkStatus>()

        collectStatuses(statuses)

        // A captive portal on a tethered phone: online enough to connect, not online enough
        // to be useful, and expensive. Collapsing this to `true` loses both facts.
        assertEquals(
            listOf(NetworkStatus.Online(isValidated = false, isMetered = true)),
            statuses,
        )
    }

    @Test
    fun `becoming available after starting offline reaches the collector`() = runTest {
        val statuses = mutableListOf<NetworkStatus>()
        collectStatuses(statuses)

        connect(wifi, capabilities())
        registeredCallback.captured.onAvailable(wifi)
        runCurrent()

        assertEquals(
            listOf(
                NetworkStatus.Offline,
                NetworkStatus.Online(isValidated = true, isMetered = false),
            ),
            statuses,
        )
    }

    @Test
    fun `a capabilities change is read from the capabilities the callback carries`() = runTest {
        connect(wifi, capabilities(isValidated = false))
        val statuses = mutableListOf<NetworkStatus>()
        collectStatuses(statuses)

        // Signing in to the captive portal: same network, now validated. The callback's own
        // argument is authoritative here, so this deliberately does not restub the manager.
        registeredCallback.captured.onCapabilitiesChanged(wifi, capabilities(isValidated = true))
        runCurrent()

        assertEquals(
            listOf(
                NetworkStatus.Online(isValidated = false, isMetered = false),
                NetworkStatus.Online(isValidated = true, isMetered = false),
            ),
            statuses,
        )
    }

    @Test
    fun `losing the default network goes Offline`() = runTest {
        connect(wifi, capabilities())
        val statuses = mutableListOf<NetworkStatus>()
        collectStatuses(statuses)
        registeredCallback.captured.onAvailable(wifi)
        runCurrent()

        registeredCallback.captured.onLost(wifi)
        runCurrent()

        assertEquals(NetworkStatus.Offline, statuses.last())
    }

    @Test
    fun `a wifi to cellular hand-off does not flap through Offline`() = runTest {
        connect(wifi, capabilities(isNotMetered = true))
        val statuses = mutableListOf<NetworkStatus>()
        collectStatuses(statuses)
        registeredCallback.captured.onAvailable(wifi)
        runCurrent()

        // The platform announces the replacement *before* it announces the loss. Treating
        // every onLost as "offline" would end the sequence disconnected — while connected.
        connect(cellular, capabilities(isNotMetered = false))
        registeredCallback.captured.onAvailable(cellular)
        runCurrent()
        registeredCallback.captured.onLost(wifi)
        runCurrent()

        assertEquals(
            NetworkStatus.Online(isValidated = true, isMetered = true),
            statuses.last(),
        )
    }

    @Test
    fun `a status equal to the last one is not re-emitted`() = runTest {
        connect(wifi, capabilities())
        val statuses = mutableListOf<NetworkStatus>()
        collectStatuses(statuses)

        // onCapabilitiesChanged also fires for changes this class does not model — signal
        // strength, link speed — and each one would otherwise recompose a screen for nothing.
        repeat(3) {
            registeredCallback.captured.onCapabilitiesChanged(wifi, capabilities())
            runCurrent()
        }

        assertEquals(1, statuses.size)
    }

    @Test
    fun `the callback is unregistered when the collector goes away`() = runTest {
        connect(wifi, capabilities())
        val job = collectStatuses(mutableListOf())

        verify(exactly = 0) {
            connectivityManager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>())
        }

        job.cancel()
        advanceUntilIdle()

        // The awaitClose assertion. Without it the system keeps a strong reference to the
        // callback and keeps waking the process on every network change, for the lifetime of
        // the process rather than of the screen.
        verify(exactly = 1) {
            connectivityManager.unregisterNetworkCallback(registeredCallback.captured)
        }
    }

    @Test
    fun `each collector owns its own registration`() = runTest {
        connect(wifi, capabilities())

        val first = collectStatuses(mutableListOf())
        val second = collectStatuses(mutableListOf())

        verify(exactly = 2) {
            connectivityManager.registerDefaultNetworkCallback(
                any<ConnectivityManager.NetworkCallback>(),
            )
        }

        first.cancel()
        second.cancel()
        advanceUntilIdle()

        verify(exactly = 2) {
            connectivityManager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>())
        }
    }
}
