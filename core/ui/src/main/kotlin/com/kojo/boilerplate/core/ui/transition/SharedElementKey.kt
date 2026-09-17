package com.kojo.boilerplate.core.ui.transition

import androidx.compose.runtime.Immutable

/**
 * Names the elements that are shared across a navigation, and is the reason there is a type here
 * at all rather than a string.
 *
 * ### The failure this closes
 *
 * A shared element is matched by `equals` on its key, and the two halves of a match are written in
 * two different modules by construction: the list row lives in `:feature:home`, the detail it
 * expands into lives in `:feature:profile`, and neither may import the other. So the key is the
 * only thing the two sides agree on, and with a string it is an agreement nothing checks —
 * `"avatar-$id"` on one side and `"user-avatar-$id"` on the other compile, render, and produce no
 * transition at all. Nothing throws, nothing logs at the default level, and the screens still
 * work: the element simply cross-fades like everything else, which is what the code looked like
 * before anyone tried to animate it. That is the whole of the bug, and it is invisible to every
 * gate this repository has except a human watching a device.
 *
 * A sealed type moves that agreement into the compiler. There is no spelling to get wrong, a
 * rename reaches both call sites, and a third screen wanting to join a transition has to add a
 * variant here — which is the moment to notice whether it is joining an existing pair or starting
 * a new one.
 *
 * ### Why the id is a constructor parameter and not part of the name
 *
 * The key has to be unique per *user*, not per screen: a list of forty rows all declaring
 * `UserAvatar` would be forty shared elements under one key, and a duplicate key inside one
 * `SharedTransitionScope` is undefined — the runtime picks one and the others are simply wrong.
 * Threading the id through the data class means the uniqueness comes from the value the row is
 * already keyed on ([com.kojo.boilerplate.core.ui.transition.SharedElementKey.UserAvatar.userId]
 * is the same string the `LazyColumn` uses as its item key), rather than from a second
 * hand-maintained convention.
 *
 * `data class` for `equals`/`hashCode`: those two are the entire contract a key has to satisfy,
 * and a hand-written key class that forgot them would match nothing — the same silent failure as
 * the misspelled string, reached a different way.
 */
@Immutable
sealed interface SharedElementKey {

    /**
     * The circular monogram: the same glyph, the same shape and the same container colour on the
     * row and on the profile, drawn at 40dp in the list and 80dp on the screen.
     *
     * Visually identical on both sides, which is what makes it the one element here that takes
     * `sharedElement` rather than `sharedBounds` — see `docs/shared-elements.md`.
     */
    data class UserAvatar(val userId: String) : SharedElementKey

    /**
     * The user's display name, `titleMedium` in the row and `headlineSmall` on the profile.
     *
     * The same string at two type scales, so the two sides are *not* visually identical and this
     * is a `sharedBounds` element: the bounds travel and the content cross-fades inside them.
     */
    data class UserName(val userId: String) : SharedElementKey
}
