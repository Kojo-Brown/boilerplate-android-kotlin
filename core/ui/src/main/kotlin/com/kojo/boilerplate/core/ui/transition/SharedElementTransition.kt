package com.kojo.boilerplate.core.ui.transition

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The two scopes a shared element needs, carried as one value so a screen can be handed the
 * ability to take part in a transition without being handed the animation API.
 *
 * ### Why two scopes, and why they are packaged
 *
 * `Modifier.sharedElement` is a member of [SharedTransitionScope] — the scope that owns the
 * overlay the element is drawn into while it travels — and it takes an [AnimatedVisibilityScope]
 * as an argument, which is what tells it *which* transition it is participating in and in which
 * direction. The first comes from the `SharedTransitionLayout` wrapping the whole nav graph; the
 * second comes from the individual `composable<Route>` entry. Neither is available where the
 * element is drawn, four composables down inside a `LazyColumn` item, so both have to travel.
 *
 * Packaged rather than passed as a pair because they are never useful apart and because two
 * parameters spread across four nested private composables is four chances to forward one and
 * forget the other — which compiles, since each is used somewhere.
 *
 * ### Why it is a parameter rather than a `CompositionLocal`
 *
 * An ambient would remove the threading, and it would also remove the decision. Whether a screen
 * is part of a shared-element transition is not a property of the screen: `HomeScreen` is, when
 * the nav graph draws it full-width, and is not, when `HomeTwoPaneScreen` draws it beside a
 * profile — because in that layout the row and the detail are on screen *at the same time*, so
 * the two sides of every key would be visible at once and a duplicate key inside one
 * `SharedTransitionScope` has no defined winner. With an ambient, whichever branch happened to be
 * inside the provider would quietly get shared elements; as a parameter, `AppNavHost` states it
 * once per branch, in the one file that knows both. `MainActivity` makes the same argument about
 * the event bus, for the same reason.
 *
 * That is also why the modifiers below take a nullable transition instead of the screens taking
 * a nullable one and branching: a screen should not contain an `if` about an animation.
 *
 * ### Not `@Immutable`, and not `@Stable`
 *
 * Both scopes are interfaces the animation library implements, neither is annotated, and this
 * class can make no promise on their behalf — so it carries no annotation, and a composable
 * taking one is compared by identity under strong skipping. That costs nothing here: `AppNavHost`
 * remembers exactly one instance per navigation entry, so the identity is stable for as long as
 * the entry is. Annotating it `@Immutable` would be the unchecked promise `StabilityContractTest`
 * exists to stop people making.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
class SharedElementTransition(
    private val sharedTransitionScope: SharedTransitionScope,
    private val animatedVisibilityScope: AnimatedVisibilityScope,
) {

    @Composable
    internal fun sharedElementModifier(key: SharedElementKey): Modifier =
        with(sharedTransitionScope) {
            Modifier.sharedElement(
                rememberSharedContentState(key),
                animatedVisibilityScope,
            )
        }

    @Composable
    internal fun sharedBoundsModifier(key: SharedElementKey): Modifier =
        with(sharedTransitionScope) {
            Modifier.sharedBounds(
                rememberSharedContentState(key),
                animatedVisibilityScope,
            )
        }
}

/**
 * Marks this node as one half of [key], for content that is visually the same on both sides.
 *
 * The element is lifted into the transition's overlay and its position and size are animated
 * directly from where it is to where its counterpart is. Nothing cross-fades, because there is
 * nothing to cross-fade between — which is exactly the assumption that makes this the wrong
 * modifier for anything whose *content* differs between the two screens, however similar the two
 * look. [sharedBoundsTransition] is that case.
 *
 * @param transition the transition this screen is part of, or `null` when it is not part of one —
 *   in which case this modifier adds nothing. Absence is a legitimate state and not a missing
 *   argument: see the class KDoc above for the layout that has to be in it.
 */
@Composable
fun Modifier.sharedElementTransition(
    key: SharedElementKey,
    transition: SharedElementTransition?,
): Modifier = if (transition == null) this else this then transition.sharedElementModifier(key)

/**
 * Marks this node as one half of [key], for content whose *bounds* should travel while the
 * content itself changes.
 *
 * The same display name is `titleMedium` in a list row and `headlineSmall` on a profile. Animating
 * that with [sharedElementTransition] would scale one of the two glyph runs into the other's box
 * for the length of the transition, which is visible as text that is briefly the wrong weight and
 * the wrong spacing. `sharedBounds` animates the container and cross-fades what is inside it, so
 * each side is drawn at its own type scale and only the box moves.
 *
 * The enter and exit transitions are left at the library's defaults (a fade each way) rather than
 * chosen here. That is a deliberate non-decision: picking them is a judgement about how the
 * animation *looks*, which needs a device, and nothing in this environment can run one — see
 * `docs/shared-elements.md`.
 *
 * @param transition as in [sharedElementTransition].
 */
@Composable
fun Modifier.sharedBoundsTransition(
    key: SharedElementKey,
    transition: SharedElementTransition?,
): Modifier = if (transition == null) this else this then transition.sharedBoundsModifier(key)
