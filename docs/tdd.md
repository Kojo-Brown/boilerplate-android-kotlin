# A TDD kata, in three commits

One use case built test-first, one commit per step of the cycle:

| Step | Commit | What it does |
| --- | --- | --- |
| **Red** | `test: the spec for a local profile edit, red` | Fifteen tests describing `EditUserProfileUseCase`, and a skeleton that fails twelve of them |
| **Green** | `feat: local profile edits, green` | The shortest implementation that passes all fifteen, every decision inline |
| **Refactor** | `refactor: make the edit's invariants structural` | The same behaviour, with the two invariants moved somewhere they cannot be forgotten |

The use case is a real one rather than a kata exercise: it applies a person's edit of
their own profile to the locally held row. `UserRepository.saveUser` had no caller
outside a test before it — half of finding 6 in [`solid.md`](./solid.md) — so this is
also the first time anything has had to decide what a local edit *means*.

The three commits are preserved in the pull request. This repository squash-merges, so
`main` carries them as one commit, which is why the diffs and the recorded output below
are in this file rather than left to `git log`.

## Why the cycle, rather than code and then tests

The claim worth testing is not "the tests pass" — tests written after the code almost
always pass, because they are written by someone who has just decided what the code
does. The claim is that the **test can fail**, and that it fails for the reason it
names. A test first written against a skeleton has demonstrated both. One written
afterwards has demonstrated neither, and this repository has shipped three gates that
could never have failed — a keep rule naming a package that never existed, a
baseline-profile rule matching no class in the APK, and a lint step that exited 0 when
`swiftlint` was absent in the sibling iOS repository. Each read as working code.

So the red run is evidence, and it is recorded below as output rather than as a claim.

## Step 1 — Red

The spec is `core/domain/src/test/kotlin/.../usecase/EditUserProfileUseCaseTest.kt`:
fifteen tests, each about a decision rather than about plumbing. What normalisation does
to an edit before anything else looks at it; which rules reject one; what "nothing
changed" means once normalisation has had its say; and the one case where a well-formed
edit still cannot be applied.

The production side is a skeleton that compiles, runs, and does none of it:

```kotlin
suspend operator fun invoke(
    userId: String,
    displayName: String,
    avatarUrl: String?,
): ProfileEditOutcome {
    val stored = userRepository.getUser(userId).first()
        ?: return ProfileEditOutcome.UnknownUser(userId)

    return ProfileEditOutcome.Unchanged(stored)
}
```

A skeleton rather than no file at all, deliberately. With no declaration the module does
not compile, and "does not compile" is a weaker red than an assertion failure: it proves
the name is missing, not that anything measures behaviour. This way every failure is a
test reporting the specific thing it is there for.

`scripts/jvm-harness/run.sh` on that commit — the harness prints JUnit's full
`MethodSource [className = …, methodName = …]` lines, shortened here to class and test
name:

```
  -- :core:domain
Failures (12):
    EditUserProfileUseCaseTest > a well-formed edit is written and reported as saved
    EditUserProfileUseCaseTest > the stored email is carried through untouched
    EditUserProfileUseCaseTest > surrounding whitespace is removed from the display name
    EditUserProfileUseCaseTest > a run of whitespace inside the display name is collapsed to one space
    EditUserProfileUseCaseTest > surrounding whitespace is removed from the avatar url
    EditUserProfileUseCaseTest > an avatar url of blank text is stored as no avatar at all
    EditUserProfileUseCaseTest > a display name that is blank once normalised is rejected
    EditUserProfileUseCaseTest > a display name longer than the limit is rejected
    EditUserProfileUseCaseTest > a name at the limit is accepted, and padding it does not push it over
    EditUserProfileUseCaseTest > an avatar url that is not https is rejected
    EditUserProfileUseCaseTest > every violation an edit commits is reported in one answer
    EditUserProfileUseCaseTest > validation does not read the store
[       102 tests found           ]
[        90 tests successful      ]
[        12 tests failed          ]
```

Twelve of fifteen, every failure inside the new file, and every other module green. Two
details in that are worth more than the count.

**The three that passed.** `an edit whose normalised form equals the stored row is
unchanged and is not written`, `a rejected edit writes nothing`, and `a valid edit for an
id the app does not hold is reported unknown and writes nothing` all pass against a
skeleton that never writes anything. That is not a flaw in the red step; it is what an
"and writes nothing" assertion is worth. Such a test can only ever fail on a *wrong*
write, so it is a companion to the test that pins the outcome, never a substitute for
one — and each of those three is paired with a sibling that does pin the outcome.

Worth knowing because the reverse mistake is easy: a suite of "it did not do the wrong
thing" assertions reports a high number and holds nothing up. The red run is where that
shows, and nowhere else.

**One test was strengthened by looking at the red run.** `the stored email is carried
through untouched` originally read the row back and compared the email, which a skeleton
that never writes also satisfies — the stored email is trivially still the stored email.
It now asserts against `ProfileEditOutcome.Saved`, so it fails until an edit is actually
applied. Reading which tests survive the skeleton is how a weak assertion gets found;
after the green step every test passes and the information is gone.

The two whole-list audits of the use-case layer — `SolidContractTest` and
`DomainLayerContractTest`, both of which assert the complete set of use cases by
fully-qualified name — gain the new name in the red commit rather than the green one, so
that the red run's failures are the spec's and nothing else's.

`FakeUserRepository` grew `savedUsers` in the same commit. "And writes nothing" cannot be
asserted by reading the rows back: the row a caller would have written is the row that is
already there, so the store looks identical whether the write happened or not.

## Step 2 — Green

All fifteen, with every decision inline — four `if`s, a `LinkedHashSet` of violations
accumulated by hand, `trim()` and a whitespace `Regex` applied at the top of `invoke`.
Nothing extracted, nothing named: the point of the step is to find out whether the spec
is satisfiable at all, and an abstraction chosen before that is an abstraction chosen
without evidence.

Per-module counts from that run, all passing — the harness runs each module's tests on
only that module's own test classpath, the isolation `testDebugUnitTest` gives them:

| Module | Tests | | Module | Tests |
| --- | --- | --- | --- | --- |
| `:core:common` | 122 | | `:data` | 130 |
| `:core:auth` | 2 | | `:feature:profile` | 9 |
| `:core:domain` | 102 | | `:feature:scanner` | 10 |
| `:core:ui` | 15 | | `:feature:signin` | 10 |
| | | | `:feature:textrecognition` | 15 |
| | | | `:app` | 53 |

468 tests where `main` has 453, 0 failures, `detekt: 0 findings`, 16 modules with 0
boundary violations, and `All harness gates passed.`

## Step 3 — Refactor

A refactor commit has to be judged by what it makes impossible, not by how it reads. Two
things in the green version were correct only because the code happened to be written in
the right order, and both are the kind of thing a later edit silently undoes.

**Normalise-before-compare became a type.** `ProfileEdit`'s constructor is private and
`ProfileEdit.normalising` is the only way to obtain one, so there is no un-normalised
edit in existence to be validated, compared or stored. In the green version the same
guarantee was the fact that two `val` declarations came before the `if`s.

What that prevents is specific, silent and permanent. `saveUser` records *which* fields
this client changed, and a local edit only ever adds to that set — so a display name
differing from the stored one by a trailing space is a real change as far as the write
path can tell. It marks `DISPLAY_NAME` locally owned, and from then on
`MergeConflictResolver` discards whatever the server holds for that field, for the life
of the row. Nothing throws and nothing is logged; the field just stops syncing. See
[`conflict-resolution.md`](./conflict-resolution.md).

It is deliberately not a `data class`. Nothing compares two edits — what gets compared is
the `User` that `appliedTo` produces — and a `data class` would publish a `copy` that
bypasses `normalising`, which is the one thing the type exists to prevent.

**Each rule moved onto its own enum entry.** `ProfileEditViolation` was a list of names
with the checks in `invoke`; the entries now carry them, and
`ProfileEditViolation.brokenBy` walks `entries`. A rule that is added is therefore a rule
that runs. The failure the old shape allowed is an entry that is declared and never
applied — it exists, a screen's exhaustive `when` renders a message for it, and nothing
ever produces it. That is the same shape as the dead keep rule and the dead profile rule,
and it is why
[`UserField`](../core/domain/src/main/kotlin/com/kojo/boilerplate/core/domain/sync/conflict/UserField.kt)
is written this way too.

`invoke` is left holding the four decisions and their order, which is the only thing in
it that is policy:

```kotlin
val edit = ProfileEdit.normalising(displayName, avatarUrl)

val violations = ProfileEditViolation.brokenBy(edit)
if (violations.isNotEmpty()) {
    return ProfileEditOutcome.Rejected(violations)
}

val stored = userRepository.getUser(userId).first()
    ?: return ProfileEditOutcome.UnknownUser(userId)

val edited = edit.appliedTo(stored)
if (edited == stored) {
    return ProfileEditOutcome.Unchanged(stored)
}

userRepository.saveUser(edited)
return ProfileEditOutcome.Saved(edited)
```

The fifteen behaviour tests are untouched by the green and refactor commits. That is what
says the refactor preserved behaviour, and it is the reason the cycle puts them first:
tests written against the refactored shape would have been written to fit it.

**One deviation from the strict form, stated rather than hidden.** The refactor commit
adds a sixteenth test, `every declared violation is produced by some edit`. A refactor
step is not supposed to add tests — but the refactor created something new that can go
wrong, an enum entry whose check can never answer `true`, and leaving it unguarded would
reintroduce the exact failure the enum shape was adopted to remove. It pins structure,
not behaviour, and the fifteen that pin behaviour stayed as they were:
`git diff <red> -- EditUserProfileUseCaseTest.kt` is additions only.

The run on that commit: 469 tests, 0 failures, `detekt: 0 findings`, 16 modules with 0
boundary violations, `All harness gates passed.` — 103 in `:core:domain` where green had
102 and `main` has 87.

## The decisions the use case actually owns

Recorded here because they are the reason this is a use case rather than something a
screen does, and because each one was a test before it was a line of code. The full
argument for each is in `EditUserProfileUseCase`'s KDoc.

| Decision | Why it is policy |
| --- | --- |
| Normalising is this layer's job, not the text field's | Two screens trimming differently is two rows with different stored names, and a cosmetic difference permanently pins a field as locally owned |
| Validation runs before the store is read | A rule measured against the input cannot be influenced by what the store holds, or by whether it can be read at all; and a field error is the one thing the person can fix |
| Every broken rule is reported, not the first | A form that reports one error per submission makes the person submit once per mistake, and the order of the checks decides which one they hear about |
| An unknown id is refused, never created | `saveUser` against a missing row can only answer "the client changed every field", so a write would fabricate a whole-profile mutation the next sync pushes as deliberate |
| An edit that changes nothing is not a write | Writing would be nearly harmless — no field pending, no key minted — and still a transaction and a Room emission that re-renders every screen observing the row |

## What this kata does not include

- **No edit screen.** Nothing calls `EditUserProfileUseCase` in production. It gives
  `saveUser` a caller outside a test, which is half of finding 6, but the finding does not
  close while the use case itself has no caller: the write path is still reached only from
  tests. The screen is its own item, and it would be the thing that decides where the
  `ProfileEditViolation` messages live.
- **No `ViewModel`, and no `UiState` arm for an edit in flight.** Both belong with the
  screen.
- **Email is not editable.** It is not a parameter and the stored value is carried
  through. Changing an address is a verification flow, and a use case that accepted one
  would be the place that forgot to verify it.
- **The avatar URL check is a prefix test.** It rejects `http://`, a bare host and a
  `file://` path. It does not assert that the URL resolves, that it points at an image, or
  that it parses.
- **None of the Gradle gates ran for any of the three commits.** `dl.google.com` answers
  403 on CONNECT in the environment this repository's scheduled agent runs in, so the
  Android SDK cannot be installed and no `./gradlew` task can configure. The offline JVM
  harness is what produced every number above; CI is the first thing to type-check these
  files against the real Android classpath. See
  [`scripts/jvm-harness/README.md`](../scripts/jvm-harness/README.md).

## Running the cycle yourself

```
scripts/jvm-harness/run.sh                 # boundaries, compile, test, detekt
scripts/jvm-harness/run.sh --skip-detekt   # the inner loop
```

Roughly five minutes for the whole repository from a warm jar cache, which is slow for a
red-green inner loop and is the honest state of this environment: the per-module Gradle
test task is what you would use on a machine with an SDK.
