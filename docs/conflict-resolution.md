# Conflict resolution

Two copies of one row disagree. This is how the app decides which one the database keeps.

## What was there before

An unconditional upsert. Every fetch in `UserRepositoryImpl` wrote its response straight over
whatever Room held:

```kotlin
private suspend fun cache(user: User): User {
    withContext(NonCancellable) { userDao.upsert(user.toEntity()) }
    return user
}
```

That is correct exactly while two things hold: the network is the only writer, and responses
arrive in the order they were sent. Neither holds here.

- **`syncUsers` fans out concurrently.** Twenty ids, twenty requests in flight, and nothing
  makes the slow response the older one. Two responses for the same id race, and the loser
  wins if it lands second.
- **`CachingUserRepository` coalesces.** Callers joining one in-flight request all receive
  that response, and a screen that subscribed later can commit an answer fetched before it
  asked.
- **`saveUser` writes to the same rows.** A local edit and a refresh are two writers to one
  row with no rule between them.

So the stored row was decided by arrival order, which is not a policy — it is the absence of
one. The visible symptom is a row that flickers between two values with no user action behind
it, and a profile edit that disappears without any request having failed.

## The version column

```kotlin
@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val id: String,
    …
    @ColumnInfo(defaultValue = "0") val version: Long = 0L,
    @ColumnInfo(defaultValue = "") val locallyChanged: Set<UserField> = emptySet(),
)
```

`version` is **the server's**, and only ever the server's. It comes down on `UserDto`, it is
written only from a response, and `saveUser` carries the existing value over untouched. A
client that increments it would make a row claim to have seen a server version that does not
exist, and the fetch eventually carrying the real one would be discarded as stale.

### Not a timestamp, and this is the part worth arguing

The obvious alternative is `updatedAt` and "newest wins". On a mobile client that compares a
server clock against a device one, and a device running ten minutes fast then wins every
conflict it takes part in — including the ones where its data is older. Nothing fails, no
error is shown; the server's data simply refuses to arrive until the skew is corrected. A
counter assigned by a single writer has no such failure mode, and it is why there is no clock
anywhere in `core.domain.sync.conflict`.

The sister repository hit exactly this: `boilerplate-ios-swift`'s freshness stamp is
wall-clock because it has to survive process death, and its `SPEC.md` records the consequence —
"a row stamped in its own future is stale rather than fresh until the skew is corrected". That
is an acceptable price for a *freshness* decision, which only costs a redundant request when it
is wrong. It is not an acceptable price for a *conflict* decision, which costs data.

### `locallyChanged`, and why a boolean is not enough

"This row has unsaved changes" is all `LAST_WRITE_WINS` needs, because it discards the local
side wholesale. Merging needs to know *which* fields are local, and given only a flag it can
either keep the whole local row — last-write-wins with the winner reversed — or none of it,
which is last-write-wins. The set is what makes the two policies actually differ.

It is stored as a comma-separated list of enum **names** through `UserFieldSetConverter`.
Ordinals are a byte shorter and one refactor away from being wrong: inserting a constant is a
source change with no compile error and no migration, and every stored row would silently start
meaning a different field.

## The decision, in order

`VersionOrderedConflictResolver` implements four of the five cases, because there is one
defensible answer to each and duplicating it per policy is how two policies drift apart.

| # | Condition | Result |
| --- | --- | --- |
| 1 | no stored row | write the response |
| 2 | `remote.version < local.version` | keep local — a stale response |
| 3 | no pending local edit | write the response, unless it is identical to the stored row *at the same version* |
| 4 | pending edit, `remote.version == local.version` | keep local — the server has said nothing new |
| 5 | pending edit, `remote.version > local.version` | **the policy decides** |

Case 3's exception is not an optimisation the caller may skip. Room invalidates every query
observing a table on any write, identical row or not, so a list refresh over twenty unchanged
rows would re-emit to every screen showing them. That is what `ConflictResolution.KeepLocal`
is for: it is the difference between "the store already says this" and "the store now says
this". Both halves of the condition are load-bearing — identical fields under a *higher*
version still need writing, because the version is what the next response is ordered against.

Case 4 is the one that reads oddly and matters most. The server is still at the version this
row was fetched at, so whatever the two rows disagree about is this client's own unpushed
change. Taking the server's copy of it is how a refresh undoes the edit it was meant to
confirm.

## The fifth case: the two policies

### `LAST_WRITE_WINS`

The response replaces the row, whole, and the local edit is gone.

The name is misleading in the way that matters: this does not prefer the most recent *arrival*,
it prefers the higher version. An older response that lands late still loses, which is what
makes the policy safe to run over a concurrent fan-out at all.

Right when the server is the only writer that matters — a feed, a catalogue, a profile fetched
from an identity provider. Wrong wherever the user can type.

### `MERGE`

The response becomes the new base, and the fields in `locallyChanged` are laid back over it.
A rename survives a server update to the avatar.

Three properties are worth stating because each one is a way the obvious implementation goes
wrong:

- **The merged row takes the server's version.** This is what makes merging terminate. Keeping
  the local version would leave the row permanently behind, so every later fetch would arrive
  as a fresh conflict and re-run the same merge forever.
- **A converged field stops being pending.** If the response carries the same value the client
  was holding, the server has caught up — whether the edit was pushed or someone else made the
  same change, which this client cannot tell apart and does not need to. Leaving it marked
  would re-apply a value identical to the server's on every sync, over a row nothing is
  actually holding.
- **A `null` is a value.** Clearing an avatar locally is an edit, and it has to beat a server
  row that still carries one. Treating an absent value as "nothing to apply" is the ordinary
  way a merge loses a deletion.

`MERGE` is the default. The one row this app writes locally is the signed-in user's profile,
and the one thing a user does to it is edit a field; under the other policy the six-hourly
background sync would be enough to silently undo that, with no request having failed and
nothing to show the user.

## Where the decision is made, and where it is applied

The policy is chosen **once**, in `ConflictResolverModule`, by an exhaustive `when` over
`ConflictPolicy.DEFAULT`. It is deliberately not a Dagger multibinding like `SyncStrategyModule`
next door: a sync mode is picked per call, so the map buys a runtime choice that is genuinely
needed, whereas a conflict policy is a property of the *data*, not of the caller. A per-call
choice would mean a row's history depended on which screen happened to refresh it.

The `when` is also the stronger check. Dagger cannot tell that a `ConflictPolicy` has no
binding; the compiler can tell that a `when` has no branch for one, and it says so where the
policy is added rather than where it is first asked for.

The decision is *applied* inside a transaction:

```kotlin
@Transaction
open suspend fun upsertResolving(id: String, resolve: (local: UserEntity?) -> UserEntity?): UserEntity?
```

Conflict resolution is a read-modify-write, and `syncUsers` runs several concurrently against
one database. Without the transaction two syncs of one id can both read the pre-write row, both
decide they are newer, and both write — and the second writer wins regardless of which carried
the higher version, which is precisely the ordering bug the resolver exists to remove. The
resolver is passed in as a lambda rather than injected, because atomicity is all this method
contributes and a Room DAO cannot take collaborators.

`cache` returns **what the store holds afterwards**, not the response. `syncUser` returns
`Result<User>` and a caller reads it as "the user, now"; when the resolver declines the write,
the response is precisely what the store does not hold, and handing it back would let a caller
render a value Room will never emit.

## Migrating

`AppDatabase` goes to version 2 with `MIGRATION_1_2`, two `ALTER TABLE`s and no data written.
Both columns are `NOT NULL DEFAULT`, which is the one schema change SQLite makes in place.

The defaults are also the semantically correct values for rows that pre-date them. A version-1
row was written by the unconditional upsert, so it is the server's copy, unedited —
`locallyChanged` empty is exactly true of it. Version `0` is deliberately pessimistic: those
rows carry no record of which server version they came from, so they are treated as older than
anything the server can now assign and the first fetch after upgrading overwrites them. That
costs one redundant write per row and is the only reading that cannot lose a change.

`@ColumnInfo(defaultValue = …)` on the entity and the `DEFAULT` in the migration have to agree.
Room compares the schema it generates against the one the database actually has at open time,
and a default declared in only one of the two places fails that comparison at runtime rather
than at build time. `DatabaseModule` declares the migration and deliberately declares no
`fallbackToDestructiveMigration()` — that one line would turn a missing migration into a
dropped database, which here means dropping the very local edits this is all for.

## Against a server that does not send a version

Every row sits at `0`, no fetch is ever strictly newer than what is stored, and only case 3 can
fire. That degrades to "the latest response wins" — which is what this repository did before the
column existed, so nothing regresses. What is still gained is case 4: an unpushed local edit is
protected, because that branch does not depend on the version having moved.

Versions buy ordering; the dirty set buys the edit. They are worth having separately, and
`VersionOrderedConflictResolverTest` pins the degraded behaviour so that a later change cannot
quietly make an unversioned backend stop updating rows.

## What this does not do

- **Push.** Nothing here sends a local edit anywhere, so a field that stays divergent keeps
  beating the server's value on this device on every sync, forever. Merging is the read half of
  a bidirectional sync and it is only half; the write half is an outbox, and it is what turns
  "unpushed" from a permanent state into a transient one.
- **Merge within a field.** Two clients editing one display name still produce a winner rather
  than a combination. Character-level merging needs an operational transform or a CRDT, which
  is a different class of machinery and a different data model.
- **Tell anyone.** A discarded or merged edit produces no event and nothing for a screen to
  render. `UserProfile` has no arm for "this row was reconciled", and giving it one is a UI
  decision that belongs with the Phase 10 items — the same gap `docs/offline-first.md` records
  for a stale row after a failed refresh.
- **Record row age.** There is still no `updatedAt`, and "stale" still means "the refresh this
  subscription ran did not land" rather than "this row is four days old".
  `docs/offline-first.md` expected that column to arrive here and it deliberately has not: a
  wall-clock column would be read as a conflict tiebreaker sooner or later, which is the skew
  hazard this design is built to avoid. Row age belongs with the freshness policy that would
  actually read it — today an in-memory window in `CachingUserRepository` — and it can be added
  there without a conflict resolver ever seeing it.
- **Test the migration.** `MIGRATION_1_2` is written and registered, but nothing runs it against
  a real version-1 database. That needs exported schemas and `MigrationTestHelper` under
  `androidTest`, which is the next `SPEC.md` item but one and is where it should land.
