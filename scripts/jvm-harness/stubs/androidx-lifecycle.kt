@file:Suppress("PackageDirectoryMismatch", "unused")

package androidx.lifecycle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Stand-in for `androidx.lifecycle.ViewModel`, for the offline harness only.
 *
 * `androidx.*` is published to Google Maven, which this environment cannot reach, so the real
 * class is not on any classpath available here. What the code under test actually uses from
 * `ViewModel` is [viewModelScope] and `onCleared`, and both are reproduced faithfully: the
 * scope is `SupervisorJob() + Dispatchers.Main.immediate`, exactly as the real
 * `viewModelScope` builds it, so a test that swaps the Main dispatcher sees the same
 * behaviour it would on device.
 */
public abstract class ViewModel {

    private val closeables = mutableListOf<AutoCloseable>()

    internal val scope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    protected open fun onCleared() {
        // Overridden by view models that release resources; nothing to do by default.
    }

    public fun addCloseable(closeable: AutoCloseable) {
        closeables += closeable
    }

    public fun clear() {
        scope.cancel()
        closeables.forEach(AutoCloseable::close)
        onCleared()
    }
}

public val ViewModel.viewModelScope: CoroutineScope get() = scope

/**
 * Stand-in for `androidx.lifecycle.SavedStateHandle`. Only the map-like surface is needed:
 * the one caller in this app reads a navigation route out of it through
 * [androidx.navigation.toRoute].
 */
public class SavedStateHandle(private val values: Map<String, Any?> = emptyMap()) {

    public constructor(vararg pairs: Pair<String, Any?>) : this(pairs.toMap())

    @Suppress("UNCHECKED_CAST")
    public operator fun <T> get(key: String): T? = values[key] as T?

    public fun contains(key: String): Boolean = values.containsKey(key)
}
