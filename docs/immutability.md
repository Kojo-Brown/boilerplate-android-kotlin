# Immutability and Compose stability

Why the state classes in this app are `data class`es of `val`s carrying `@Immutable`, why
their collections are `ImmutableList` and not `List`, and what the annotation actually
promises.

Every claim on this page is pinned by
[`StabilityContractTest`](../app/src/test/kotlin/com/kojo/boilerplate/core/ui/StabilityContractTest.kt),
which walks the state graph reachable from every view model and fails the build when the
contract is broken — so this page cannot drift from the code without `testDebugUnitTest`
going red.

## What stability buys

Compose recomposes a function when its inputs change. Deciding *whether* they changed is
only possible if the runtime can trust an input's `equals`, and it can only trust `equals`
if nothing can change the object behind its back. A type it can trust is **stable**; a
composable whose parameters are all stable is **skippable**, and a skippable composable
whose arguments are unchanged is not re-executed at all.

The compiler infers this per class. A class is stable if every public property is a `val`
of a stable type. One `var`, or one property typed as an interface it cannot see through,
and the whole class is unstable.

## `List` is the trap

```kotlin
data class Success(val items: List<HomeItem>)   // unstable
```

Nothing here looks mutable. `items` is a `val` and `List` is the read-only interface. But
`List` is only read-only *at that reference* — the object behind it is an `ArrayList` that
some other holder may still be writing to, and the compiler cannot prove otherwise. So the
class is unstable, and `HomeContent` stops being skippable.

Strong skipping (on by default since Kotlin 2.0) softens this without fixing it: an
unstable parameter is compared by *instance identity* instead of `equals`. `HomeViewModel`
allocates a fresh list on every upstream emission, and Room re-delivers the whole table when
any row in it changes, so identity comparison fails every time even when nothing the user
can see has changed.

`ImmutableList` fixes it because the guarantee is in the type:

```kotlin
@Immutable
data class Success(val items: ImmutableList<HomeItem>)   // stable
```

The Compose compiler's `KnownStableConstructs` names every `kotlinx.collections.immutable`
interface as externally stable, so the property is stable, the class is stable, and equality
is structural again. Two refreshes that return identical users now skip.

## `ImmutableList` in types, `PersistentList` at the call site

The two interfaces answer different questions and the distinction is worth keeping:

- **`ImmutableList`** — "this will not change." The weakest type that carries the guarantee,
  so it is what properties and composable parameters are declared as.
- **`PersistentList`** — an `ImmutableList` that also has `add`/`remove`/`set`, each
  returning a *new* list that shares structure with the old one. That is what you build
  with, and what you reach for when state accumulates rather than being replaced.

Where a list is produced from another collection, build it in one pass:

```kotlin
items = filtered.mapTo(persistentListOf<HomeItem>().builder()) { user -> HomeItem(...) }.build()
```

rather than `map { }.toImmutableList()`, which fills an `ArrayList` and then copies all of it
into the persistent trie. `toImmutableList()` is still the right call when the source is a
list you did not build — the copy is unavoidable there, and it is the copy that makes the
result safe to hold.

Nothing in this app currently *accumulates* into a state list; every state is replaced
wholesale. If one grows — an infinite-scroll page appending to what is on screen — that is
the case `PersistentList` exists for, and `state.copy(items = state.items + page)` costs a
handful of nodes rather than a full copy.

## `@Immutable` is a promise, not a check

`@Immutable` tells the compiler to treat the type as stable **without verifying it**. Get it
wrong and the failure is silent: the runtime skips a recomposition it should have run, and
the screen shows data that has already changed. There is no warning, no crash, and no
diagnostic — just a stale pixel.

So the annotation may only be applied to a type where:

- every property is a `val`,
- every property's type is itself stable,
- and no property is a `kotlin.collections` interface.

`StabilityContractTest` enforces exactly that list. It reads the annotation out of the class
file rather than by reflection, because `@Immutable` is `@Retention(BINARY)` and so is
invisible to `KClass.annotations` at runtime.

### `@Immutable` vs `@Stable`

`@Immutable` says the values never change after construction. `@Stable` is the weaker claim:
values *may* change, but changes are always signalled to the composition (a `State`-backed
property), and `equals` is consistent. Everything in this app is genuinely the former, so
nothing here is annotated `@Stable`; the audit accepts either.

### Where the annotation goes

On the sealed parent **and** on each subclass that declares properties:

```kotlin
@Immutable
sealed interface HomeContent {
    data object Loading : HomeContent

    @Immutable
    data class Users(val items: ImmutableList<HomeItem>, val greeting: String) : HomeContent
}
```

The parent needs it because an abstract type's stability cannot be inferred from its own
body — Compose cannot enumerate the subclasses, so it assumes the worst. The subclasses need
their own because a Kotlin annotation is not inherited, and `HomeUserList` takes
`HomeContent.Users` directly. A `data object` is exempt: it has no state to go stale.

Enums are exempt too — the compiler treats every enum as stable, so `BarcodeFormat` carries
no annotation on purpose.

## What stability does *not* cover

**Lambdas.** Function types are stable and Compose memoizes them at the call site, so
`AdaptiveNavItem.onClick` costs the class nothing. What the compiler does not inspect is what
the lambda *captures*: a lambda closing over an unstable value is recreated on every
composition, and a nav item built from it is a new object each pass regardless of how the
class is annotated. `remember` the lambda, or hoist the capture.

**The data layer.** `FanOut.kt` builds its results in an `ArrayList` and returns a `List`,
which is correct: nothing there is a Compose input, and a mutable local builder is the right
tool. Stability is a property of what crosses into a composable, not a style rule for the
whole codebase — which is why `GoogleUser` is annotated despite living in `core/auth`, and
`FanOutResult` is not despite living next to it.

**One-shot effects.** A `UiEffect` is delivered through a `Channel` and consumed by
`ObserveAsEvents`; it is never held across a recomposition, so stability says nothing about
it and the audit does not walk it. See [state-and-events.md](state-and-events.md).
