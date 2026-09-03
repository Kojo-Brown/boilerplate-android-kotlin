# Idempotent sync requests with client-generated keys

A retry must not apply a change twice. The client cannot arrange that on its own, and the
server cannot arrange it without being told which request is which. A client-generated key is
how it is told.

## The problem, stated exactly

A request that changes something on the server has three outcomes, and the client can only
distinguish two of them:

| What happened | What the client sees |
| --- | --- |
| The server never received it | a timeout, or an `IOException` |
| The server applied it and the response was lost | a timeout, or an `IOException` |
| The server applied it and answered | a `200` |

The first two are the same event as far as the client is concerned, and they call for opposite
responses: retry, or do nothing. Picking "retry" is right in the first case and applies the
change a second time in the second. Picking "do nothing" is right in the second case and loses
the edit in the first.

There is no client-side answer to this. Waiting longer does not help; polling for the result
needs a request that has the same problem; a longer timeout narrows the window and does not
close it. The only way out is for the two requests to be *recognisable as one*, which is a
property of the request rather than of the retry logic.

## The mechanism

```
saveUser("Ada Renamed")
   │
   ├── users row: locallyChanged = {DISPLAY_NAME}
   └──             pendingChangeKey = "b1e2…"     ← the mutation is named, here, once

… hours later, in a different process …

pushPendingChanges()
   │
   ├─▶ PATCH /users/1        Idempotency-Key: b1e2…      → timed out
   ├─▶ PATCH /users/1        Idempotency-Key: b1e2…      → timed out
   └─▶ PATCH /users/1        Idempotency-Key: b1e2…      → 200
                                                            server applied it once
```

Three attempts, one name, one change. The server stores the outcome under the key and replays
it rather than re-applying; a server that does not do that is no worse off than it would have
been without the header, which is worth knowing because **it is the one failure this client
cannot detect** — a server that ignores `Idempotency-Key` answers exactly like one that honours
it.

## The three rules that make it work

Everything else in this document follows from these.

### 1. The key is minted where the mutation is created, and nowhere else

`UserRepositoryImpl.saveUser` is the only caller of `IdempotencyKeyGenerator`. Generating one
in the push path instead reads perfectly well and removes the entire guarantee: every attempt
would introduce itself as a new change, so the retry after a lost response applies the edit
again — arriving at exactly the bug the header exists to prevent, by way of the code that was
supposed to prevent it.

### 2. The key changes when, and only when, the payload changes

*When*, because the key promises the server that two requests carrying it ask for the same
thing. A second edit to a row whose first edit is still unsent asks for something different;
reusing the name lets a server that has already seen the first request recognise the second as a
duplicate and drop it. The user is shown a change the app then silently abandons.

*Only when*, because a key that changes for any other reason is not a name. A save that writes
the values already stored — a re-submitted form, a retry of `saveUser` itself — is the same
mutation and keeps the same key.

The payload is *the pending fields and their local values*, which is exactly what
`updateUserRequest` sends. Nothing but a local edit can change it:

- `MergeConflictResolver` intersects the pending set and keeps the local value for every field
  left in it;
- `LastWriteWinsConflictResolver` clears the set entirely;
- nothing else writes either column.

So a fetch can only shrink the payload or delete it, never alter it — which is what lets the
key be decided once, at save time, instead of being recomputed at send time.

### 3. The key is non-null exactly when the pending set is non-empty

Two columns, one fact. Both halves break silently if they drift: a key left on a clean row makes
a later push send a change nobody made, and a pending row with no key is an edit that can never
be safely retried.

`VersionedUser.toEntity` takes the key as a required parameter for this reason — there is no
default to forget — and `keyForResolvedPendingSet` is the single expression that decides it for
every write that is not a local edit: *carry the existing key while anything is still pending,
drop it when nothing is.*

## Why the key is stored and not derived

The tempting alternative is a key computed from the change itself — a hash of the row id, the
pending fields and their values. It makes rule 2 true by construction and needs no column.

It also makes **edit → revert → edit back** indistinguishable from a single change. The third
mutation hashes to the first one's key, the server recognises a duplicate, and the user's change
is dropped with every layer reporting success. A random UUID keeps two mutations that happen to
look alike apart, and the column is what that costs.

A counter is worse than both: it repeats after a reinstall and collides immediately across two
devices signed into one account.

## Why the acknowledgement compares keys

A request is not instantaneous, and `saveUser` can land while one is in the air. At that point
the row carries a *newer* mutation under a new key, and the acknowledgement in hand says nothing
about it.

```
push sends key-7
                      saveUser() → row now holds key-9
response to key-7 arrives
```

Clearing the pending set because "the push succeeded" would discard an edit the user made
seconds earlier, with no error anywhere and nothing left to notice afterwards.
`ResolvingUserWriter.commitAcknowledging` clears only while the row still holds the key that was
sent; when it does not, the response takes the ordinary conflict-resolution path, so it is still
committed — it is the server's current row — and the newer edit stays pending under its own
name.

## Where the push runs

`PerformBackgroundSyncUseCase`, before the fetch, on the six-hourly WorkManager schedule
described in [background sync](./background-sync.md). The order is not arbitrary: under
`LAST_WRITE_WINS` a fetch that ran first would *destroy* the unsent edit — the fetched row wins
wholesale and the pending fields are cleared — so the change would be gone before anything tried
to send it.

That is also the retry that matters. In-process backoff (`RetryingUserRepository`) covers a few
seconds; what a push has to survive is an offline device, a killed app, and an attempt that
arrives hours later in a different process, against a row read back out of SQLite. A key held in
memory would not survive any of that, which is the second reason it is a column.

## What is deliberately not here

- **A base version on the wire.** The client's `version` is an ordering aid for local conflict
  resolution ([conflict resolution](./conflict-resolution.md)); making it a wire-level
  precondition is an API contract — a header, a `412`, and a defined recovery — rather than a
  field, and it is a different item. Nothing loses an edit without it: an update the server
  should have rejected is reconciled by the next fetch, through the resolver that already
  handles exactly that case.
- **A general outbox.** There is one queue, it is the `users` table's own pending-field set, and
  it holds at most one unsent mutation per row: a second edit supersedes the first rather than
  queueing behind it. That is right for "the user changed their display name twice" and wrong
  for "the user made two payments". An entity whose mutations must not collapse needs a real
  outbox table — the key's shape and the acknowledgement rule carry over unchanged.
- **Telemetry on the push.** `PendingUserChangeRepository` is bound undecorated, so the push is
  the one repository operation `RepositoryTelemetry` does not measure. Caching and in-process
  retry are wrong for a write for the reasons above; telemetry is not wrong, it is simply not
  worth a decorator stack around a one-method interface yet.
- **A server.** Every claim here about deduplication is a claim about a server that honours the
  header. `docs/` has no backend to point at, and the client half is what a boilerplate can
  ship.

## The parts

| | |
| --- | --- |
| `IdempotencyKeyGenerator` | `:core:domain`. An interface so a test can name the key it expects. |
| `UuidIdempotencyKeyGenerator` | `:data`. `UUID.randomUUID()`, and why not a hash or a counter. |
| `users.pendingChangeKey` | The column. `MIGRATION_3_4` adds it and backfills the edits already stranded on device. |
| `UserRepositoryImpl.saveUser` | Rule 1 and rule 2. |
| `VersionedUser.toEntity` / `keyForResolvedPendingSet` | Rule 3. |
| `UpdateUserRequest` | The payload, and why `changed_fields` is a list rather than absent keys. |
| `UserApi.updateUser` | The `PATCH` and the header. |
| `PendingUserChangeRepository` | The push, and why it is not a seventh method on `UserRepository`. |
| `ResolvingUserWriter.commitAcknowledging` | The mid-flight-edit check. |
