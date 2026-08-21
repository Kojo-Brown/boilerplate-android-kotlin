package com.kojo.boilerplate.core.ui.udf

/**
 * Something the user did, on its way *into* a [UdfViewModel].
 *
 * One type per screen, sealed, with one member per interaction the view model needs to know
 * about — a tap, a keystroke, a permission result. It is the only way in: a screen never calls
 * a method on its view model, it sends an event to [UdfViewModel.onEvent].
 *
 * The point of the marker is not type safety, which a sealed hierarchy already gives. It is
 * that "what can this screen do?" has exactly one answer, in one file, that the compiler
 * checks against the `when` handling it — instead of being spread across however many public
 * methods the view model happens to expose. `docs/unidirectional-data-flow.md` has the whole
 * argument.
 *
 * An event names what happened, not what should happen next: `RetryClicked`, not `Reload`. The
 * decision of what a tap means belongs to the view model, and an event named for the reaction
 * has already made it in the composable.
 */
interface UiEvent
