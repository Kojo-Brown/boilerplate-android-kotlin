# Paging 3 over Room and the network

The user list is paged with Paging 3, backed by Room, filled by a `RemoteMediator`. This page
is what each piece does, and — the part worth reading — the three places where this
implementation deliberately differs from the shape the official codelab teaches, because in
each one the codelab's answer would break something this repository already got right.

## The shape

```
  PagedUserRepository            (:core:paging)   fun users(): Flow<PagingData<User>>
        ▲
        │ implements
  PagedUserRepositoryImpl        (:data)          builds the Pager
        │
        ├── pagingSourceFactory ──▶ UserPagingDao.pagingSource()   Room. The only source of data.
        └── remoteMediator ───────▶ UsersRemoteMediator            Network. Writes; never returns.
```

Three properties follow from that split, and they are the reason to use Paging this way rather
than hand-rolling a "load more" button:

- **Every row on screen came out of the database.** The `PagingSource` is Room's, so the list
  renders fully offline, from the first frame, with no request in the path.
- **An error is never an empty screen.** `MediatorResult.Error` becomes a `LoadState.Error` on
  the append. The cached pages stay exactly where they were and the reader gets a retry.
- **A write anywhere in the app updates the list.** Room invalidates the `PagingSource` on any
  write to `users`, so an edit through `UserRepository.saveUser` — or a row cached by
  `syncCurrentUser` — appears in the paged list with nothing subscribing it to anything.

## Why the contract is its own module

`PagedUserRepository` lives in `:core:paging`, a module whose entire content is that one
interface. It is not in `:core:domain`, where `UserRepository` is, because a paged read is
`Flow<PagingData<User>>` and `PagingData` is an `androidx` type — and `:core:domain` is held to
having no `android`/`androidx` reference at all, by the `ForbiddenImport` detekt rule, by
`DomainLayerContractTest` reading the compiled constant pool, and by its own empty dependency
list.

The alternative would be to define a framework-free `Page<T>` and map to it. That is not a
smaller amount of code — it is invalidation, placeholders, load state and prefetch, which is
the whole of what Paging 3 is — and the mapped-over version would still have to be constructed
from a `PagingData` somewhere.

It cannot go in `:data` either: a feature will consume it, and no feature may see `:data`. So
it is a module, the same answer `:core:auth` is. The difference is that `:core:auth` exists
because of a leak a fix would remove, and this one does not — see
[`modularisation.md`](./modularisation.md) and finding 9 in [`solid.md`](./solid.md).

## Three deliberate departures from the codelab

### 1. The cursor is one row, not a key per user

The codelab keeps a `remote_keys` table with a row per item, carrying the page each item
arrived on, so a `REFRESH` can resume from wherever the reader is. Here the cursor is a single
row in `user_page_keys` holding the next page to fetch.

The reason is that `users` has writers other than the mediator. `syncCurrentUser` and
`syncUser` cache single rows fetched by id, and those rows have never been on a page — so a
per-item table has no key for them. If one of them sorts last, `APPEND` reads a null key off it
and reports end-of-pagination while the server still has users: the list silently truncates,
and only for users whose profile someone happened to open. The local order is `displayName`
anyway, chosen by the DAO rather than by the endpoint, so "the page this item came from" is not
a property of where it sits locally.

What is given up is resuming a `REFRESH` from the middle of the list. This mediator does not do
that — a refresh restarts at page one — and the reader's scroll position is held by the local
Room `PagingSource`, which is positional, not by the mediator.

### 2. `REFRESH` does not clear the table

The codelab deletes every cached row inside the refresh transaction, so that the local order
and the server's page order cannot drift apart. Doing that here would delete the rows
`syncCurrentUser` and `syncUser` cached for the profile screen, and — the serious half — any
row carrying an edit the server has not acknowledged yet. Scrolling a list would become the one
way to silently destroy an unpushed local change, arriving through a door
[`conflict-resolution.md`](./conflict-resolution.md) did not think to lock.

So a refresh refills rather than replaces. The cost is a stale row the server no longer returns
lingering in the list until something else removes it. That is a much smaller cost than losing
a user's unsent change, and it is visible where the alternative is not.

### 3. Pages go through the conflict resolver

A page is a batch of fetched rows, so it is the same kind of write as `syncUser`'s and gets the
same treatment: `UserPagingDao.commitPage` reads the stored row, hands it and the fetched one to
the `ConflictResolver`, and writes what comes back — or nothing, when the policy declines. The
cursor moves either way, because whether a *row* was worth overwriting says nothing about
whether the page was delivered.

Without this, everything [`conflict-resolution.md`](./conflict-resolution.md) establishes would
hold for every write except the one that happens most often.

## End of pagination

`GET /users?page=&per_page=` returns a bare list. A page shorter than `per_page` is the end.

There is no `total_pages` envelope, deliberately: the short-page rule is true of every
page-number API whether or not it also reports a total, and it is one fewer field for a server
to get wrong. The trade-off is that a list whose length is an exact multiple of the page size
costs one extra empty request to discover its end.

The one thing this rule cannot survive is a server that caps `per_page` below what the client
asked for — every page then looks short and the list stops after one. A server that does that
should either honour the requested size or be adapted to here explicitly; it is called out
because it fails quietly, showing a list that is merely shorter than it should be.

## What is not built yet

**No screen consumes this.** `HomeViewModel` still reads `UserRepository.getUsers()`, which is
the right shape for the handful of users it shows. Wiring a `LazyColumn` to
`collectAsLazyPagingItems()` — with `key`, `contentType` and the load-state footer — is the
Phase 10 `LazyColumn` item, and doing it here would have meant rewriting `HomeUiState`,
`HomeScreen` and their tests inside a data-layer change.

**No instrumented test.** `UsersRemoteMediatorTest` covers which page is asked for, what is
stored, and what is reported back, against a fake DAO. What it cannot cover is atomicity —
`FakeUserPagingDao` has no transaction — and the generated Room `PagingSource` itself. Both need
a real database, which is `androidTest`, which needs an emulator this project's CI does not yet
run. The Phase 12 instrumented-matrix item is where that arrives.
