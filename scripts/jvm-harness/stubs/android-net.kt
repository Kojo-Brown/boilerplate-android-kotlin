@file:Suppress("PackageDirectoryMismatch", "unused")

package android.net

/**
 * Stand-in for the slice of `android.net.NetworkCapabilities` this app reads, for the offline
 * harness only.
 *
 * The three capability constants keep the values the platform assigns them, so a stubbed
 * instance and a real one answer `hasCapability` identically for the same set. `open` rather
 * than `final` because the real class is `final` and the unit tests mock it — mockk needs the
 * inline mock maker for the real thing and only ordinary subclassing for this.
 */
public open class NetworkCapabilities(private val capabilities: Set<Int> = emptySet()) {

    public open fun hasCapability(capability: Int): Boolean = capability in capabilities

    public companion object {
        public const val NET_CAPABILITY_NOT_METERED: Int = 11
        public const val NET_CAPABILITY_INTERNET: Int = 12
        public const val NET_CAPABILITY_VALIDATED: Int = 16
    }
}
