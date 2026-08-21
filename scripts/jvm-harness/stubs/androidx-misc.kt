@file:Suppress("PackageDirectoryMismatch", "unused")

package androidx.navigation

import androidx.lifecycle.SavedStateHandle

/**
 * Stand-in for `androidx.navigation.toRoute`, for the offline harness only.
 *
 * The real implementation deserialises the route object out of the destination's arguments.
 * Nothing under `src/test` exercises that path — `ProfileViewModel` is the only caller and its
 * tests supply the id directly — so the harness needs this to type-check and link, not to
 * reproduce the deserialiser. Keyed by the type's own name so a harness test that wanted to
 * supply a route could.
 */
public inline fun <reified T : Any> SavedStateHandle.toRoute(): T =
    requireNotNull(get<T>(requireNotNull(T::class.qualifiedName))) {
        "No route of type ${T::class.qualifiedName} in this SavedStateHandle"
    }
