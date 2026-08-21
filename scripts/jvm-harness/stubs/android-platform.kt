@file:Suppress("PackageDirectoryMismatch", "unused", "UNUSED_PARAMETER")

package android.util

/**
 * Stand-in for `android.util.Log`, for the offline harness only.
 *
 * On device this is a `final class` of static native methods that throw
 * "not mocked" under a plain JVM. Here every level records the call and returns the byte
 * count the real API returns, so a test can assert on what was logged — which is what
 * `LogcatRepositoryTelemetry` needs and what Robolectric would otherwise be for.
 */
public object Log {

    public data class Entry(val level: String, val tag: String, val message: String)

    private val recorded = mutableListOf<Entry>()

    public val entries: List<Entry> get() = synchronized(recorded) { recorded.toList() }

    public fun clear(): Unit = synchronized(recorded) { recorded.clear() }

    public fun v(tag: String, msg: String): Int = record("V", tag, msg)

    public fun d(tag: String, msg: String): Int = record("D", tag, msg)

    public fun i(tag: String, msg: String): Int = record("I", tag, msg)

    public fun w(tag: String, msg: String): Int = record("W", tag, msg)

    public fun e(tag: String, msg: String): Int = record("E", tag, msg)

    public fun e(tag: String, msg: String, tr: Throwable?): Int = record("E", tag, msg)

    private fun record(level: String, tag: String, msg: String): Int {
        synchronized(recorded) { recorded += Entry(level, tag, msg) }
        return msg.length
    }
}
