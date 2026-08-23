# The domain layer, and the rule that keeps it one

`core.domain` holds the application policy that used to live in `ViewModel`s. Nothing in it
imports Android, and that is enforced twice — once by a linter that runs on every commit, once
by a test that reads the compiled bytes.

This page is the argument for both halves: why the layer exists, what belongs in it, and why
one check was not enough.

## Why a layer, given that a use case is usually a hop

The honest starting position is the one [`solid.md`](./solid.md) takes: a use case that
forwards one method to one repository is a file that costs a hop and buys nothing. At this
size, `ViewModel` → repository is a defensible shape and most of this app still uses it.
`HomeViewModel` still calls `userRepository.getUsers()` directly, and should.

What changed the argument was not the count of layers. It was that the policy had already been
**copied**. `ProfileViewModel` and `ProfileDetailPaneViewModel` held the same pipeline —
retry with backoff, drop duplicate emissions, treat a missing row as an error, map to
`ProfileData` — in two verbatim copies, differing only in where the user id came from. The
decision that a missing row is an *error* rather than an empty state is a product decision, and
it was written down twice, in the layer furthest from the data. A third caller would have made
it three.

So the test for whether something belongs here is not "is it business logic". It is:

> Would a second screen have to make this decision again, and could it get a different answer?

Two things passed that test:

| Use case | The policy it owns | Was duplicated in |
| --- | --- | --- |
| `ObserveUserProfileUseCase` | Retry, dedupe, and "a missing row is a failed load, not an empty one" | `ProfileViewModel`, `ProfileDetailPaneViewModel` |
| `RefreshVisibleUsersUseCase` | Which sync a list refresh performs: a person tapping refresh covers what the screen is showing | `HomeViewModel.refresh()` |

`getUsers()` did not, and is still called on the repository directly.

`RefreshVisibleUsersUseCase` owned three more decisions — empty selection makes no request,
ids are deduped, a partial failure is a partial success — until the Factory + Strategy item
later in this phase. Those turned out to be decisions about *one way of syncing* rather than
about refreshing in general, and they moved into `VisibleUsersSyncStrategy` with it;
`SyncMode.CURRENT_USER` answers all three differently. What is left in the use case is the
choice of mode, and that is the decision a second list screen would have to make again — see
[`sync-strategy.md`](./sync-strategy.md).

## What the layer may and may not depend on

```
feature/*  ──▶  core.domain  ──▶  core.data (repository interfaces, models)
   │                                    │
   └──────────── androidx ──────────────┘   ✗ never from core.domain
```

Allowed: `javax.inject`, coroutines, `core.data` interfaces and models, `core.coroutines`
helpers.

Not allowed: `android.*`, `androidx.*`, `com.google.android.*`, `dagger.hilt.android.*`,
`kotlinx.coroutines.android.*`.

`javax.inject.Inject` is on the allowed side and `dagger.hilt.android` is not, deliberately:
`@Inject` on a constructor is an annotation the layer can carry anywhere, while
`@HiltViewModel` and `@AndroidEntryPoint` name Android components. Hilt still constructs these
use cases — `@Inject` on the constructor is all it needs, and no module binds them.

Two consequences worth stating, because they look like omissions:

- **Domain types carry no `@Immutable`.** `CLAUDE.md` asks for `@Immutable`/`@Stable` on
  models, but that annotation is `androidx.compose.runtime`. It is absent here because
  nothing in `core.domain` reaches a composable — `UserProfile` is consumed inside a
  `ViewModel` and rendered as `ProfileData`, which *is* `@Immutable`. If a domain type ever
  needs to cross into Compose, the answer is a presentation type beside it, not an import.
- **`retryWithBackoff` is not framework-free all the way down.** It lives in
  `core.coroutines` and imports `retrofit2.HttpException` to decide what is transient. That
  is not Android, so the rule permits it, but it does mean the layer is not yet portable to a
  non-JVM target. Recorded rather than fixed; an error taxonomy owned by this app is the real
  answer and is [`solid.md`](./solid.md)'s open finding on `Result.toUiState()`.

## Enforcement, and why it is two checks

### 1. `ForbiddenImport`, scoped by path — `config/detekt/detekt.yml`

```yaml
style:
  ForbiddenImport:
    active: true
    includes: ['**/core/domain/**']
    forbiddenPatterns: '^(android|androidx|com\.google\.android|dagger\.hilt\.android|kotlinx\.coroutines\.android)\.'
```

This is the gate a contributor actually hits. It runs in the `detekt` job, fails the build on
the first offending line, and names the file, the line and the import.

It is scoped with `includes` rather than applied globally because everywhere else in this app
importing `androidx` is *correct* — `ViewModel`s, composables, Room entities and the DAO all
should. A blanket ban would have to be un-banned in more places than it banned, and a rule
carrying a long exemption list is a rule nobody reads.

**What it cannot see:** it reads import directives. A fully-qualified reference written inline
never appears in the import list:

```kotlin
// No import. ForbiddenImport has nothing to flag.
class Leaky(private val context: android.content.Context)
```

Neither does a framework type that arrives through a supertype or a generic signature.

### 2. `DomainLayerContractTest`, reading the compiled classes

Every one of those has already collapsed into the same place by the time the compiler is done:
the class file's constant pool. Parameter and return descriptors, supertypes, annotations,
field types and the target of every call are all UTF-8 entries in that table. The test walks
the compiled output of `core.domain`, parses the pool, and fails on any entry containing
`android/` or `androidx/`. Both prefixes are needed and neither implies the other —
`androidx/lifecycle/ViewModel` does not contain the substring `android/`.

The two checks are deliberately redundant and deliberately different. The linter is fast,
precise about *where*, and blind to non-imports. The test is slower, coarser about location,
and blind to nothing.

**What it scans, and what it does not.** Compiler-generated classes are in scope: a `$`
lambda, a nested type or a suspend continuation is the compiler's rendering of source someone
here wrote, and a lambda capturing a `Context` is precisely the leak being hunted.
Annotation-processor output is not: KSP and Hilt generate `ObserveUserProfileUseCase_Factory`
and its kin *into this package* from a template nobody here wrote, its references are not a
statement about this layer's dependencies, and no edit to `core.domain` could fix a finding in
it. `_` is the marker — every generated name carries one and no hand-written declaration in
this app does, the same heuristic `SolidContractTest` uses.

### The exemption that modularisation deleted

`androidx.compose.runtime.internal.StabilityInferred` used to be forgiven, and finding out why
was the most useful thing this test has done.

The Compose compiler plugin stamps `@StabilityInferred` onto **every** class it compiles — not
just composables, not just UI types. While the app was one module that plugin was applied to
the domain layer as well, and `RefreshOutcome`, all three `UserProfile` arms and both use cases
came back carrying it on the first CI run. So the honest version of this page's headline was
narrower than "no androidx in the domain layer": in a single-module Compose app it was not
achievable at the bytecode level, and no edit to these files could achieve it. The annotation
was the toolchain's, applied module-wide, and encoded nothing about what the code depended on.

`:core:domain` is now its own Gradle module and the Compose plugin is not applied to it — see
[modularisation](./modularisation.md). Nothing stamps the annotation any more, the exemption is
gone, and the scan forgives nothing at all. That is what the module boundary bought here, and
it is a better argument for splitting than build times: the layering used to be a convention
this test policed, and it is now a fact about the build.

The three checks are deliberately different in kind, and each catches what the others cannot:

| Check | Reads | Blind to |
|---|---|---|
| `ForbiddenImport` (detekt), scoped to `**/core/domain/**` | import directives | a fully-qualified reference, an inherited supertype |
| `DomainLayerContractTest` | the compiled constant pool | nothing about references; it cannot say *which line* |
| `core/domain/build.gradle.kts` | the dependency list | a type that arrives transitively through another module |

It also pins two things that a rule alone cannot:

- **The layer is not empty.** A path-scoped rule over a package that has been deleted passes
  forever while enforcing nothing.
- **The use-case roster is exactly the documented one.** A use case gaining or losing members
  changes what `solid.md` finding 1 says.

And `SolidContractTest` covers the remaining escape hatch from the other side: it asserts
app-wide that every type named `…UseCase` lives in `core.domain`. A use case declared in
`feature/home/` would sit outside the linter's `includes` glob and could import anything at
all; that assertion is what notices.

## Adding a use case

1. Put it in `core.domain.usecase`, named `…UseCase`.
2. Constructor-inject its dependencies with `@Inject`. No Hilt module is needed.
3. Give it a single `operator fun invoke`. One public entry point is what keeps a use case
   from growing into a service object.
4. Add it to `DOCUMENTED_USE_CASES` in `DomainLayerContractTest` and `AUDITED_USE_CASES` in
   `SolidContractTest` — both fail with the reason and the file to edit if you forget.
5. Test it on a plain JVM. If a test needs Robolectric or a `Dispatchers.Main` substitute,
   something Android-shaped got in.

## What this does not do

It does not enforce direction *between* the other layers. `core.datastore` importing
`ui.theme.ThemeMode` — data depending on UI, finding 4 in [`solid.md`](./solid.md) — is
untouched by a rule scoped to `core.domain`, and closing it means moving `ThemeMode` into a
domain package first. That is a separate change with a naming decision in it.

It also says nothing about *layering within* the domain: nothing stops one use case depending
on another, or a use case reaching for two repositories. Those are design questions a lint rule
would answer badly.
