# Shared elements and predictive back

Two features that look like one item and are really one question asked twice: **who owns the
gesture, and who owns the animation it drives.**

A shared-element transition is an animation played *by* a navigation. Predictive back is a
navigation *driven by* a gesture. Put them together and the result is the thing users actually
notice — a row that expands into a screen, and a swipe that unwinds it back into the row, at the
speed of the finger, reversible until released. Get the ownership wrong in either direction and
both halves still compile, still render, and simply do not animate.

That silence is the theme of this document. Every mistake below produces a working app.

## What is shared

Two elements, between the home list and a profile, keyed on the user id:

| Key | Source | Destination | Modifier |
|---|---|---|---|
| `SharedElementKey.UserAvatar` | `HomeItemCard` | `ProfileIdentity` | `sharedElement` |
| `SharedElementKey.UserName` | `HomeItemCard` | `ProfileIdentity` | `sharedBounds` |

The email on the row is deliberately not shared: the profile does not draw it in a comparable
place — it is a labelled card further down — so pinning the two together would drag the line
across the screen to land somewhere it does not belong. An element with no counterpart does not
animate, which is the right outcome and needs no code.

### `sharedElement` vs `sharedBounds`

This is the choice people get wrong, and the rule is not "which looks better".

`sharedElement` lifts **one** node into the transition's overlay and animates its rectangle from
where it is to where its counterpart is. Nothing cross-fades, because the modifier assumes there
is nothing to cross-fade between: it is the *same drawing*, at two positions and two sizes.

`sharedBounds` animates the container and cross-fades the contents inside it, so each side is
drawn at its own size with its own content.

So the question is not how similar the two sides look. It is whether they are literally one
drawing.

- **The monogram is.** `UserMonogram` lives in `:core:ui` and both sides call it — 40dp in the
  row, 80dp on the profile, with the glyph sized as a fraction of the circle so it scales with its
  container. One composable is what makes "the same drawing at two magnifications" true by
  construction. Two copies, one per feature, would satisfy `sharedElement`'s precondition on the
  day they were written and stop satisfying it the first time a colour changed on one side — and
  the symptom would be a transition that visibly jumps at its midpoint, on a device, months later.
  Moving the widget into the design system is therefore not tidying; it is what licenses the
  modifier.
- **The name is not.** It is the same string at `titleMedium` in the row and `headlineSmall` on
  the profile. `sharedElement` would scale one glyph run into the other's box, showing text at
  the wrong weight and letter spacing for the length of the animation. `sharedBounds` moves the
  box and lets each side render its own text.

`SharedElementContractTest` pins both pairs. It cannot check which modifier is right — that is a
claim about the drawing, not about the text — so the pin is what puts a reviewer in front of the
question, and this section is the answer for the two that exist.

## Why the key is a type

A shared element is matched to its counterpart by `equals` on its key, and
`rememberSharedContentState` takes `Any`. The two halves of every match in this app are written in
two modules that may not import each other — the row in `:feature:home`, the profile in
`:feature:profile` — so the key is the only thing the two sides agree on.

With a string, that agreement is unchecked. `"avatar-$id"` on one side and `"user-avatar-$id"` on
the other compiles, renders, and produces no transition: nothing throws, nothing logs at the
default level, and the screens work exactly as they did before anyone tried to animate them.

`SharedElementKey` is a sealed interface of data classes, which removes the spelling from the
problem entirely. The id is a constructor parameter rather than part of the name, because the key
has to be unique per *user* and not per screen — forty rows declaring one `UserAvatar` would be
forty shared elements under one key, and a duplicate key inside one `SharedTransitionScope` has no
defined winner.

`rememberSharedContentState`, `sharedElement` and `sharedBounds` are confined to
`SharedElementTransition.kt` and asserted to be. Reaching past the wrapper is how a raw string key
gets back in.

## Why the scopes are a parameter and not a `CompositionLocal`

`Modifier.sharedElement` needs two scopes: the `SharedTransitionScope` that owns the overlay, and
the `AnimatedVisibilityScope` that says which transition is running and in which direction. The
first comes from the `SharedTransitionLayout` wrapping the whole graph, the second from the
individual `composable<Route>` entry. Neither is available where the element is drawn, four
composables down inside a `LazyColumn` item, so both have to travel — packaged as one
`SharedElementTransition`, because they are never useful apart.

An ambient would remove the threading, and it would remove the decision with it. **Whether a
screen takes part in a shared-element transition is not a property of the screen.** `HomeScreen`
is the source half of one when the nav graph draws it full-width, and is not when
`HomeTwoPaneScreen` draws it beside a profile — because in that layout the row and the profile are
on screen *at the same time*, so both halves of every key would be visible at once, and there is
nothing arriving or leaving to animate anyway. With a `CompositionLocal`, whichever branch happened
to sit inside the provider would have inherited shared elements silently. As a parameter,
`AppNavHost` states it once per branch, in the one file that can see both.

The parameter is nullable, and `null` is a legitimate value rather than a missing argument:
`HomeTwoPaneScreen` passes it to `HomeScreen`, and `ProfileDetailPane` passes it to
`ProfileIdentity`. The screens themselves contain no `if` about an animation — the modifier
helpers absorb the absence.

There is no default on `HomeScreen` or `ProfileScreen`, which are what the nav graph calls: the
caller must decide. `HomeBody` and `ProfileContent` do default to `null`, because they are the
content composables that instrumented tests and previews call, where there is genuinely no
transition.

## Predictive back

`NavHost` (Navigation Compose 2.8+) seeks its own pop transition from the system back gesture. The
pop tracks the finger, reverses if the gesture is abandoned, and a shared element follows the same
seek for free — there is nothing to write.

**Except one line.** `android:enableOnBackInvokedCallback="true"` on the manifest's `application`
node is what switches the app from the legacy `KEYCODE_BACK` path to `OnBackInvokedDispatcher`,
and that dispatcher is the only thing that reports a gesture's *progress*. Without it:

- `NavHost` has no progress to seek with, so the pop plays as a plain animation after the fact;
- the shared elements have no seek to follow;
- `PredictiveBackHandler` receives a single completed event instead of a stream.

Every back still navigates. Nothing fails. That is why the attribute is asserted by
`SharedElementContractTest` — it is the highest-leverage line in the whole feature and the easiest
to lose to an unrelated manifest edit.

It is honoured from API 33 and on by default for apps targeting API 36+. This app is minSdk 26 /
targetSdk 35, so below 33 the platform ignores it and the legacy path is used; declaring it costs
nothing there.

### The one place this app intercepts back

`HomeTwoPaneScreen`. Both panes live inside a single `composable<Home>` entry, so selecting a user
does not push anything — the selection is state. `NavHost` therefore has nothing to pop, and back
with a profile on screen leaves Home altogether, past the screen the user was reading. Clearing
the selection first is what makes back mean what it means everywhere else.

`BackHandler` is the obvious tool and the wrong one. It consumes the gesture without taking part
in it: for as long as it is enabled the system plays no predictive-back animation, so the user
gets no preview of where back leads — on this screen or the one underneath — and the dismissal
happens in a single frame at the end. A back gesture that cannot be cancelled is the thing
predictive back was introduced to fix.

`rememberPredictiveBackDismiss` is the same interception with the progress handed back. The pane
shrinks under the finger and springs back if the gesture is abandoned. `BackHandler` is banned
repository-wide and asserted to be absent, now that the alternative exists.

Everything else stays with `NavHost`. Adding a handler to a *destination* would take the gesture
away from the seek and replace a reversible, finger-tracked transition with a hand-rolled
animation played after the fact.

### Commit and cancel, without catching anything

The progress `Flow` completing means the user released past the threshold; the flow being
**cancelled** means they released before it. Those are the only two outcomes.

The documented pattern catches `CancellationException` to tell them apart. This one does not: a
cancellation propagates out of the handler, as a cancellation should, and a `finally` winds the
pane back on the way past. `onDismissRequest` sits after the `try`, so it is unreachable on the
cancelled path. On the committed path the progress is deliberately *left* where the gesture ended
and reset after the caller has removed the content — winding it back first shows one frame of a
full-size pane on its way out.

### Why the transform is a `graphicsLayer` block

`PredictiveBackDismissState.progress` moves with the user's finger, so where it is *read* decides
what a gesture costs.

- Returned from a `@Composable` function, or passed to `Modifier.scale()`, it is read during
  composition. A `@Composable` returning a value is not restartable, so its state reads are
  recorded against its **caller** — here, an entire detail pane, its view model's state and a
  profile's worth of cards, recomposed every frame of the gesture to move a scale factor. This is
  the same shape as `useListDetailLayout()`; `docs/derived-state.md` has the argument.
- Read inside `Modifier.graphicsLayer { }`, the block form, it is read in the **draw phase**. A
  progress change invalidates drawing and nothing else. No recomposition, no relayout.

So the state is exposed as a holder and read in exactly one place. The pivot follows the swipe
edge from `BackEventCompat`, so the pane leans towards the finger the way the system's own preview
does rather than collapsing towards its middle.

## What is not done

- **Nothing was measured, or seen, on a device.** The agent's environment has no Android SDK —
  `dl.google.com` answers 403 on CONNECT — so no emulator, no Layout Inspector and no screen
  recording. Every claim here about how the animation *looks* is read off the API contract, not
  off a frame. The `sharedBounds` enter/exit transitions are consequently left at the library's
  defaults rather than chosen, and `DISMISS_MIN_SCALE` / `DISMISS_MIN_ALPHA` are matched to the
  system's documented preview rather than tuned against it.
- **No `boundsTransform`.** The default spring governs both elements. Choosing a curve is a
  judgement about how the motion feels, which is the previous point.
- **No `resizeMode` on the name.** `sharedBounds` defaults to `ScaleToBounds`; whether
  `RemeasureToBounds` reads better for text that changes type scale is a device question.
- **The transition is a source-checked contract, not a runtime one.** Nothing asserts that a
  transition actually ran. That needs an instrumented test with an animation clock, and
  `androidTest` still executes nowhere in this repository — Phase 12's emulator matrix is
  unchecked.
- **The monogram does not scale with the system font.** It is sized in `sp` off a `dp` container,
  deliberately, so the letter cannot overflow a fixed circle. That trade belongs to the
  accessibility pass, along with making the circle itself scalable.
- **`UserMonogram` announces its letter to TalkBack.** Both copies it replaced did too, so this is
  unchanged rather than introduced; collapsing it into the name beside it is the accessibility
  item's job, and doing it here would change the node trees the instrumented tests assert on.
- **The contract test reads source**, so an alias defeats it, and it attributes a declaration's
  body to the span before the next `fun` — coarse in the way `CustomLayoutContractTest`'s
  enclosing-declaration walk is coarse.
