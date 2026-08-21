@file:Suppress("PackageDirectoryMismatch", "unused")

package android.content

/**
 * Stand-in for `android.content.Context`, for the offline harness only.
 *
 * Nothing in the JVM-compilable subset calls a method on it — it is passed through
 * `GoogleAuthRepository.signIn` to Credential Manager, which the harness does not compile —
 * so an opaque marker type is the whole of what is needed, and anything richer would be a
 * fake claiming to be a Context.
 */
public abstract class Context
