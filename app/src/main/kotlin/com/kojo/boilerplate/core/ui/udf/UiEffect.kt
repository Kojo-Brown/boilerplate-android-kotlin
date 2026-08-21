package com.kojo.boilerplate.core.ui.udf

/**
 * Something that must happen exactly once, on its way *out* of a [UdfViewModel]: navigate,
 * show a snackbar, hand something to the clipboard.
 *
 * The distinction from state is the one `docs/state-and-events.md` is built on — if the screen
 * were destroyed and rebuilt right now, should this happen again? A spinner should still be
 * spinning, so it is state. A snackbar that has already been shown should not be shown twice,
 * so it is an effect. State that has to be cleared by hand after it is read is an effect that
 * has been filed as state, and the manual clear is the part that does not survive a rotation.
 *
 * ### When a screen has none
 *
 * An effect exists when the *view model* decides that something should happen once. A tap that
 * only ever means "go to the next screen" is not one of those: routing it through the view
 * model adds a hop that can only forward, and the composable's own navigation lambda says the
 * same thing with less. Four of this app's six screens genuinely decide nothing of the kind,
 * and they say so in their own signature — `UdfViewModel<S, E, Nothing>`. `Nothing` has no
 * instances, so "this screen emits no effects" is a statement the compiler enforces rather
 * than a comment that goes stale, and [UdfViewModel.emitEffect] becomes uncallable.
 */
interface UiEffect
