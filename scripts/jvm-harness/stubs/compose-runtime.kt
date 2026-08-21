@file:Suppress("PackageDirectoryMismatch", "unused")

package androidx.compose.runtime

/**
 * Stand-ins for the two Compose stability annotations, for the offline harness only.
 *
 * `BINARY` retention matches the real declarations, which matters here: `StabilityContractTest`
 * finds these by searching the compiled class file's constant pool precisely because the JVM
 * drops binary-retention annotations before reflection can see them. A stub declared
 * `RUNTIME` would make that test pass for the wrong reason.
 */
@MustBeDocumented
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER)
public annotation class Immutable

@MustBeDocumented
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER)
public annotation class Stable
