# Lazy lists: keys, content types, and prefetch

Three techniques that a `LazyColumn` gets by default and that the default is sometimes wrong
about. This page is what each one actually buys, which of the app's two lazy lists needed it, and
— the half worth reading — the places the reflex answer buys nothing and was deliberately not
applied.

The rules that a source check can settle live in
[`LazyListContractTest`](../app/src/test/kotlin/com/kojo/boilerplate/architecture/LazyListContractTest.kt).
The ones that cannot are pinned there instead, and this page is where the claim behind each pin
is made.

## The two lists

| List | Slots | Shapes | Keys | `contentType` |
| --- | --- | --- | --- | --- |
| `HomeScreen.HomeUserList` | one `items` | one | `HomeItem.id` | none, deliberately |
| `TextRecognitionScreen.TextDetectedContent` | six `item`, one `itemsIndexed` | seven | named on the six; index on the blocks | `TextDetectedItem` |

## `contentType`: what the reuse pool is for

A lazy layout does not keep a composition per row. It keeps a small pool of subcompositions from
items that have scrolled off and offers one to each item scrolling in, so that the incoming item
re-uses a slot table instead of building one. The offer is only made between items that declare
the **same content type**, and the reason is structural rather than cosmetic: a slot table is
already shaped like the tree that wrote it, so handing a `Button`'s table to a bordered card finds
nothing in it that matches and the runtime composes from scratch anyway.

The default content type is `null` — one type, shared by every slot in the list. For a list of one
shape that is exactly right, and most lists are one shape. For a list that mixes shapes it is
wrong, and wrong *silently*: every item still renders, in the right order, at the right size. The
only symptom is that the pool never hits, which looks like nothing at all until the list is long
enough to scroll on a device slow enough to notice.

`TextDetectedContent` was the mixed one — a heading, a full-text panel, a blocks heading, a run of
block cards, two buttons and a bottom spacer, all at the default type. `TextDetectedItem` names
the seven shapes, and the one that matters is `Block`: it is the only slot there is ever more than
one of, so it is the only one the pool can pay off on, and it now has a pool to itself.

`HomeUserList` was **not** changed, and that is the point of the rule's shape. It emits one
`items` call over one card, so every item in it already shares one content type; naming it would
partition the pool into the single class it already was. `LazyListContractTest` therefore asks for
a content type only of a list that emits more than one kind of slot — a rule whose fix is
pointless is a rule people learn to satisfy rather than read.

## `key`: what identity is for, and when the index is it

A slot with no key is identified by its index in the item provider. That is correct exactly while
indices are stable, and two things move them.

**A reorder.** `HomeUserList` is rebuilt from the database on every write to `users` and sorted by
display name, so a rename moves rows. Under index identity the runtime would hand row 4's
composition — and everything it remembered — to whoever moved into position 4. It is keyed on the
user id, which is what survives the sort.

**A conditional sibling.** This is the one `TextDetectedContent` had. When `scan.blocks` is empty
the block heading and every card are not emitted, so the copy button, the scan-again button and
the bottom spacer all shift up by `blocks.size + 1`. Index identity reads that as three items
being replaced by three different ones: remembered state is dropped and a scroll anchored to one
of them jumps. Naming those slots makes them the same three items they always were, which is why
the contract test requires a key of every **fixed** slot in a mixed list — a fixed slot is one
nameable piece of content, so a name is always available.

### Why the blocks are still index-keyed

`RecognizedTextBlock` is a `String` and a `Float`. There is no id, and there is no derivable one:
two blocks in a single frame can hold the same text, so a key computed from the content would
repeat — and a repeated key is an `IllegalArgumentException` out of the lazy layout, not a
mis-render. Its index is therefore the only identity a block has.

It is also the right one. The list is only ever replaced whole, by the next scan, with the screen
returning to the camera in between; nothing reorders it and nothing inserts into it. That is
precisely the case index identity is correct for, and writing `key = { index, _ -> index }` would
restate the default while implying a decision had been made about the model.

Because "the model has no id worth keying on" is a claim about the model rather than about the
source, `LazyListContractTest.EXPECTED_INDEX_KEYED` pins it: the set of unkeyed `items` calls is
asserted, so a second one has to arrive through this page.

### Keys have to be `Bundle`-safe

`TextDetectedItem` is an `enum` rather than a set of string constants for two reasons. A key is
written to a `Bundle` when saved item state is preserved across process death, and Compose's
registry accepts a key that is `Serializable`, which every enum entry is. And it is the narrower
type: a typo in a string key is a silent duplicate, and a duplicate key throws.

## Prefetch: two mechanisms, and only one of them is in the UI

"Item prefetch" names two different things in a Compose list, and they sit either side of a line
worth drawing.

**Compose's lazy-layout prefetch** composes and measures one item beyond the viewport during the
scroll, off the critical frame, so the item is ready before it is scrolled to. It is on by
default, needs no configuration, and — the part that matters here — it can only prefetch an item
whose **data it already has**. It is a composition optimisation, not a fetch.

**Paging's `prefetchDistance`** is the one that fetches. It is how close to the loaded edge an
access has to be before the next page is requested, and it is the parameter that decides whether a
reader scrolling steadily ever reaches the bottom of what has been loaded. It lives in
`PagingConfig`, in `PagedUserRepositoryImpl` — so whether this list ever stalls is settled in the
data layer, and there is nothing a `LazyColumn` can do about it.

It is set to one page ahead, written as `PAGE_SIZE` rather than as a number. That is also
`PagingConfig`'s own default, because the default *is* `pageSize` — and restating it is the point.
Left defaulted, halving the page size to make requests cheaper would silently halve the prefetch
window too: a scroll-smoothness regression arriving out of a line that says nothing about
scrolling. Written out, the coupling is deliberate and both numbers move in front of a reviewer.

The value itself was not tuned, and this is the place to say so plainly: nothing here was measured
on a device. The agent's environment has no Android SDK and no emulator, so the same limit
[`recomposition.md`](./recomposition.md) records applies — what is written above is the documented
behaviour of the two mechanisms and the reasoning from it, not a profile. One page ahead is the
library's default for the ordinary reason defaults are what they are; moving it is a decision for
whoever first watches this list scroll on real hardware.

## What is not done here

**No screen consumes the paged stream.** `HomeViewModel` still reads
`UserRepository.getUsers()`, and `PagedUserRepository` still has no caller outside its tests —
`prefetchDistance` above is therefore a setting on a list nothing renders yet.
[`paging.md`](./paging.md) expected that wiring to arrive with this item, and it did not, because
it is not one change:

- **Search would have to move into the DAO.** `HomeViewModel` filters the full list in memory.
  Filtering a `PagingData` cannot work — pages are loaded lazily, so an in-memory filter sees only
  what has been loaded and shortens the list instead of searching it — so the `LIKE` has to become
  part of `UserPagingDao.pagingSource()`, which changes the `PagedUserRepository` contract and the
  mediator's relationship to it.
- **The refresh fan-out loses its input.** `RefreshVisibleUsersUseCase` is called with the ids of
  the users currently on screen, read from `state.value.content`. Under paging the loaded items
  live in the presenter, not in the view model, so either the event carries the ids — putting
  state in the composable, against `docs/state-and-events.md` — or the refresh stops meaning "what
  I am looking at".

Both are real design decisions with tests attached, and neither is about `LazyColumn` performance.
They belong in the Phase 9 item that wires a screen to the paged stream.

**Nothing was measured.** See above: no device, no emulator, no Macrobenchmark. The claims here
are about mechanisms, not about frame times on this app.

**The contract test reads source.** So it cannot see through an alias — a wrapper composable named
something other than `LazyColumn` would not be audited. That hole is why the orphan pin exists:
slots inside an unrecognised container belong to nothing and are reported, which is how a renamed
container fails rather than quietly stops being checked.
