@file:Suppress("PackageDirectoryMismatch", "unused")

package dagger.hilt.android.lifecycle

import kotlin.reflect.KClass

/**
 * Stand-in for `@HiltViewModel`, for the offline harness only.
 *
 * `com.google.dagger:dagger` and `hilt-core` are ordinary jars on Maven Central and the
 * harness uses the real ones; `hilt-android` is an AAR on Google Maven and is not reachable
 * here, so the two annotations this app takes from it are declared instead. The signature
 * matches the real one, `assistedFactory` included, because `ProfileDetailPaneViewModel` uses
 * it and `SyncStrategyModuleContractTest` reads annotations off the compiled output.
 *
 * Only the declaration is reproduced. Hilt's code generation is not, which is why the harness
 * cannot stand in for the KSP half of the build — see `scripts/jvm-harness/README.md`.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
public annotation class HiltViewModel(val assistedFactory: KClass<*> = Unit::class)
