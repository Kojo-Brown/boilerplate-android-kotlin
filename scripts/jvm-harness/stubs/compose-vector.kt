@file:Suppress("PackageDirectoryMismatch", "unused")

package androidx.compose.ui.graphics.vector

import androidx.compose.runtime.Immutable

/**
 * Stand-in for `androidx.compose.ui.graphics.vector.ImageVector`, for the offline harness only.
 *
 * An opaque marker with a name, which is all the JVM-compilable subset needs: `AdaptiveNavItem`
 * holds two of these and never looks inside one.
 *
 * It earns its place by what it unlocks rather than by what it does. `AdaptiveNavItem` is the
 * only thing `StabilityContractTest` imports that the harness could not otherwise resolve, and
 * without it that test — the one that audits every view model's state for Compose stability —
 * was silently skipped here and only ran in CI. It found a real regression there that this
 * harness should have caught first.
 *
 * `@Immutable` is not decoration: the real class carries it, and `StabilityContractTest` reads
 * it out of the class file. Without it here, `AdaptiveNavItem` would be reported as holding an
 * unstable property — a failure about the stub rather than about the app.
 */
@Immutable
public class ImageVector(public val name: String = "")
