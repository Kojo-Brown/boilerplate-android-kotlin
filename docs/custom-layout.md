# Custom layouts and `SubcomposeLayout`

Two components in `:core:ui` are written as layouts rather than assembled out of `Row` and
`Column`, and they are deliberately one of each kind. The difference between them is the whole
subject:

| | `LabelledValue` | `ExpandableText` |
|---|---|---|
| Built on | `Layout(contents = …)` | `SubcomposeLayout` |
| The measurement decides | **where** content goes | **whether** content exists |
| Children composed | with the caller | inside the measure pass |
| Answers a parent's intrinsic query | yes, from its children's | only by a speculative measure |

The rule the table is a long way of stating:

> Use a `Layout`. Reach for `SubcomposeLayout` only when what to compose is not knowable until
> something has been measured — and expect to pay for it.

## The three phases, and why this is a phase problem

A Compose frame is composition, then layout, then drawing. Composition decides what nodes exist;
layout decides how big they are and where. The arrow runs one way, which is what makes the
framework predictable, and it is also why "show this control only if the text does not fit" is
awkward: the condition is a layout fact and the consequence is a composition one.

There are three ways out and they are not equivalent.

**Measure and place differently.** If every node exists under every outcome, nothing has to be
composed late — the measurement only chooses coordinates. This is a plain `Layout`, and it is the
answer far more often than it is used.

**Write the measurement into state and recompose.** `Text(onTextLayout = { overflows = it.hasVisualOverflow })`
with an `if (overflows)` below it. This is the version everyone writes first. It works, in the
sense that the second frame is correct — the first is not, because the state write happens during
the layout of the frame that has already composed. On a screen composed once, one wrong frame is
invisible. Inside a `LazyColumn` item it is not: the item is re-composed and re-measured every time
it scrolls back into view, so the control pops in, every time, for the life of the screen. When the
flag also changes what gets measured — a control takes vertical space — the same shape is how an
invalidation loop starts.

**Compose from inside the measure pass.** That is what `SubcomposeLayout` is: it can call
`subcompose(slotId) { … }` in its own measure lambda and measure what comes back. The answer to
"is there a control?" and the control itself belong to the same frame.

## `LabelledValue` — a `Layout`

A caption and the value it captions, side by side when they fit and stacked when they do not.

It is measure-dependent for a reason that is specific to this app: the same card is drawn by
`ProfileScreen` across a phone and by `ProfileDetailPane` inside the detail half of a list-detail
layout. A size class cannot make this call — the component is handed what is left of the window
after a navigation rail, a pane split and two lots of padding, for a value that might be twelve
characters or sixty. Only the measure pass knows.

What it is *not* is a reason to subcompose. Both children exist either way; the row and the stack
place the same two nodes at different coordinates.

Three details worth keeping:

- **Two content lambdas, not one.** `Layout(contents = listOf(label, value))` hands the measure
  policy one list of measurables per lambda, which is how it tells them apart without composing
  anything itself. Before that overload existed, wanting exactly this was the most common honest
  reason to reach for `SubcomposeLayout`; a lot of code written against the old API is still
  carrying that cost.
- **The fit is decided from an intrinsic.** A `Measurable` may be measured once per layout pass, so
  "measure it and see whether it fits" is not available. `maxIntrinsicWidth` is — at the price of
  its own pass over the child's subtree. Two `Text`s are cheap. A lazy list is not merely expensive
  but *impossible*: `LazyColumn` cannot answer an intrinsic query at all, because answering would
  mean composing every item, which is the one thing it exists not to do. A custom layout that asks
  its children for intrinsics therefore constrains what may be put inside it.
- **The arithmetic is in `Long`.** `Constraints.Infinity` **is** `Int.MAX_VALUE`, and a `Text`'s
  maximum intrinsic width is its width with no wrapping at all. Add three numbers of that size as
  `Int`s and the sum wraps negative; a negative sum compares `<=` anything; the "it fits" branch
  then measures a child against a negative width, which is an exception out of the `Constraints`
  constructor. `fitsOnOneRow` is a `Long` comparison and `MeasurePolicyTest` has the case.

## `ExpandableText` — a `SubcomposeLayout`

A block of text clipped to a few lines, with a control to open it, and the control appears only
when there is something behind the clip.

Whether that is true is not a property of the string. A hundred characters is four lines on a phone
and two in a dialog, one line at `bodySmall` and three at `bodyLarge`, and more again once the
reader turns the system font size up. Nothing but a text layout at this instance's actual width and
style knows — which is the measure pass, which is after composition has already decided what
exists. That is the circle, and subcomposition is what breaks it.

### The four slots

| Slot | Measured | Placed |
|---|---|---|
| `Collapsed` | always | unless expanded |
| `OverflowProbe` | always | **never** |
| `Expanded` | only when shown | yes |
| `Toggle` | only when there is something to toggle | yes |

`OverflowProbe` is the interesting one. It is the same text at one line more than the collapsed
limit, and comparing its height against the collapsed one answers "is there more?" — one extra line
found somewhere to go, so something was waiting for it. Measuring the *whole* text instead is the
reading that comes first and it is linear in the string; this is bounded at the collapsed limit plus
one line however long the text is.

It is measured and never placed, and that is what makes it invisible rather than hidden. An unplaced
node is not drawn, and it is not walked by semantics either, so it does not reach TalkBack and does
not appear in a UI test's node tree as a second copy of the text.

### Slot ids

A slot id is how a `SubcomposeLayout` finds the subcomposition to reuse, compared with `equals`.
Two ways to get it wrong, neither of which is an error:

- **An id built at the call** — a lambda, an anonymous object, a `Pair`. It never equals the
  previous pass's, so the slot's composition is discarded and rebuilt on every measure.
- **Two slots sharing an id** — the second composes *over* the first, and the first's measurables
  are left describing a node that is gone.

An enum entry is immune to both by construction. `CustomLayoutContractTest` requires one, and
requires the ids within a layout to be distinct.

### The state is the reader's request, not an instruction

`expanded` is hoisted — it lives on `TextScanState.TextDetected`, because a `remember` inside a
lazy slot is discarded when the slot scrolls away, which would silently re-collapse a result behind
the reader. Being screen state, it outlives the string it was set for: expand a page of recognised
text, point the camera at a door sign, and the next scan replaces `fullText` with four words.

So the flag is honoured only while the text actually overflows. `planExpandableText` derives both
the body and the control from the measurement, and the caller's flag only chooses between the two
bodies when there are two to choose from. The version that simply obeys it renders an expanded body
identical to the collapsed one under a control that collapses to what is already on screen.

## Testing a measure policy without a device

Neither component has a unit test of its layout, because there is no way to run one here:
a `MeasurePolicy` executes in the layout phase, so exercising it means composing, which means
`androidTest`, which runs nowhere in this repository yet (see `docs/room-migrations.md` for what
that cost the migration suite, and Phase 12 for the emulator matrix that would fix it).

What *is* tested is every decision the policies make, because the decisions were written as
functions over pixels in `MeasurePolicy.kt` rather than as lines inside a measure lambda. What stays
in the lambda is the part that genuinely needs a `Measurable`: asking for an intrinsic, measuring,
placing. Two of the cases in `MeasurePolicyTest` are ones that would otherwise be found on a device,
months later, by a user: `Int.MAX_VALUE` reaching the fit arithmetic, and an `expanded` flag
outliving its text.

This is a general move and not a trick for these two components. A custom layout's judgement is
usually a handful of comparisons over integers; the framework interaction around it is what cannot
be tested off-device. Splitting them puts the part that can be wrong under the gate.

## Not done

- **Nothing was measured on a device.** The agent's environment has no Android SDK —
  `dl.google.com` answers 403 on CONNECT — so the claims here about frames and pops are read off
  the phase model rather than off a trace. `docs/recomposition.md` makes the same disclaimer for the
  same reason.
- **No intrinsic implementations.** `LabelledValue` *asks* its children for intrinsics and does not
  *answer* a parent asking for its own. Nothing currently puts one inside an `IntrinsicSize.Min`
  column, and writing `MeasurePolicy.minIntrinsicWidth` without a caller means writing code no gate
  exercises.
- **`ExpandableText` does not animate.** Expanding is a resize between two frames, with no
  `animateContentSize`. Adding one interacts with the measurement — an animating height is a height
  the overflow probe must not be compared against — which is a decision, not a line.
- **The contract test reads source**, so an alias defeats it, and it cannot follow a slot id through
  a helper. That hole is what the enum closes: the type has four entries and nothing else can be
  passed.
