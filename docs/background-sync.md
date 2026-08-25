# Background sync with WorkManager

A periodic job that keeps the signed-in account fresh while the app is closed. Small enough to
read in one sitting, and shaped so that the parts worth testing are testable: the schedule
lives with WorkManager in `:data`, the decisions live in `:core:domain`, and the worker in
between does nothing but translate.

## The pieces

```
BoilerplateApp.onCreate ── ensurePeriodicSyncScheduled() ──▶ BackgroundSyncScheduler   (:core:domain)
                                                                     │
                                       WorkManagerBackgroundSyncScheduler              (:data)
                                                    enqueueUniquePeriodicWork(name, UPDATE, request)
                                                                     │
                                                          UserSyncSchedule.request()
                                                       constraints · backoff · interval · tag
                                                                     │
                       ── WorkManager wakes the app, hours later, in no Activity ──
                                                                     │
                                              UserSyncWorker.doWork()                  (:data)
                                                                     │
                                    PerformBackgroundSyncUseCase(runAttemptCount)       (:core:domain)
                                                                     │
                                    SyncStrategyFactory.create(CURRENT_USER).sync()
                                                                     │
                                       BackgroundSyncOutcome ──▶ Result.success/retry/failure
```

| Type | Module | What it is for |
| --- | --- | --- |
| `BackgroundSyncScheduler` | `:core:domain` | "This app keeps the account fresh in the background", with no WorkManager in the sentence |
| `PerformBackgroundSyncUseCase` | `:core:domain` | What a background run syncs, what counts as done, when to stop retrying |
| `BackgroundSyncOutcome` | `:core:domain` | `ListenableWorker.Result` with the framework taken out, so the decision is JVM-testable |
| `UserSyncSchedule` | `:data` | The interval, flex window, constraints, backoff and unique name |
| `UserSyncWorker` | `:data` | A `CoroutineWorker` that hands the run over and translates the answer |
| `WorkManagerBackgroundSyncScheduler` | `:data` | Enqueues the request as unique periodic work |
| `BackgroundSyncModule` | `:data` | Binds the scheduler and provides the `WorkManager` instance |

## The three things the spec item names

### Constraints

`NetworkType.CONNECTED` and `setRequiresBatteryNotLow(true)`, and nothing else.

Each constraint added is another condition that has to hold for the sync to run *at all*, so
the interesting question about a constraint set is not what it prevents but what it can
prevent forever. `setRequiresCharging` would mean a phone that is never plugged in overnight
silently stops syncing; `setRequiresDeviceIdle` would mean the same for a phone in constant
use. Neither failure has any signal attached — the app simply stops refreshing, and nothing
logs that it did.

`CONNECTED` rather than `UNMETERED` for the mirror-image reason. The payload is one user row.
Refusing to fetch a few hundred bytes off Wi-Fi would mean a phone on mobile data all week
never syncs, which costs more than the data does. `UNMETERED` is the right constraint for a
payload whose size a user would notice — a media prefetch, a full catalogue — and this is not
one.

### Backoff

`BackoffPolicy.EXPONENTIAL` from 30 seconds, paired with a budget of **4 attempts** in
`PerformBackgroundSyncUseCase`.

The two halves live in different modules and neither means much alone, which is the one thing
worth remembering about this page:

| | Where | Why there |
| --- | --- | --- |
| How long between attempts | `UserSyncSchedule`, as `setBackoffCriteria` | The process is not running between attempts; only the scheduler can implement this |
| How many attempts | `PerformBackgroundSyncUseCase.MAX_ATTEMPTS` | "Is another try worth the battery" is a product decision, and it is the half a test can assert |

Together the attempts land at roughly 0s, 30s, 60s and 120s, so a sync failing because the
device is offline gives up inside about four minutes and waits for the next period. Without the
budget, `Result.retry()` on a permanently failing sync is an exponential curve that keeps
waking the device for the rest of the day.

Giving up drops *that occurrence*, not the schedule. Periodic work whose run returns
`Result.failure()` is still enqueued and still runs at the next interval with a fresh attempt
count — which is why four attempts against a six-hour period costs almost nothing in
freshness.

30 seconds rather than WorkManager's 10-second floor because the failure this retry exists for
is a connection that is not there, and a device offline ten seconds ago is usually still
offline. The network constraint means a retry does not even run until connectivity returns, so
the delay is a floor on how soon it may run rather than a promise that it will.

### Unique work

`enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, UPDATE, request)`, called from
`Application.onCreate`.

`onCreate` runs every time the system brings the process back, which is far more often than
once per install, so an ordinary `enqueue` would pile up a new periodic job each time.
Uniqueness is keyed on the name string and nothing else, which has two consequences worth
knowing:

- **The name must be stable across app versions.** Changing it does not rename the existing
  work — it enqueues a second, unrelated schedule beside it and leaves the first running
  forever.
- **The name must not collide with a library's.** A collision silently replaces one schedule
  with the other. Prefixing with the package is the cheap insurance.

**`UPDATE` rather than `KEEP`,** which is the decision in this file with the least obvious
consequence. Both make the call idempotent. They differ when the schedule *changes* in a later
version of the app:

- `KEEP` leaves the enqueued work exactly as it was, so an install that has run the sync once
  keeps the old schedule for the life of that install. A fix shipped in an update reaches new
  installs only, and nothing anywhere disagrees with the source: the code says six hours, the
  device does whatever the version it first ran said.
- `UPDATE` replaces the specification while keeping the work's identity — same id, same run
  history, no cancel-and-re-enqueue.

`REPLACE`, `UPDATE`'s predecessor and deprecated for periodic work, had a trap that is worth
knowing because the call site looks identical: it cancelled and enqueued fresh, which **reset
the period**. An app calling this on every process start could restart the six-hour clock
before the sync ever came due, producing a schedule that never fires.

## Where the layer boundary is, and why it is there

WorkManager's vocabulary — `Constraints`, `BackoffPolicy`, `PeriodicWorkRequest` — stays in
`:data`. It is not mirrored into domain enums, because a translation layer whose only purpose
is to keep those types off the domain classpath is a second vocabulary to keep in step with
the first, and none of those values means anything without the scheduler that interprets them.

What *does* cross into `:core:domain` is the part that survives without WorkManager:

- **Which users a background sync covers.** `SyncMode.CURRENT_USER` — see
  [`sync-strategy.md`](./sync-strategy.md). A worker has no screen, so sizing a background
  fetch by whatever list was last open spends a metered connection on rows chosen by an app
  state that expired hours ago. This is the mirror of the policy `RefreshVisibleUsersUseCase`
  owns, and it is why `CurrentUserSyncStrategy` was written before it had a caller.
- **What counts as done.** Anything that did not arrive is worth another attempt. A screen
  showing eight of ten users is mostly right and the tenth can wait for a pull-to-refresh; a
  background sync has nobody waiting and no cheaper moment to try again in.
- **That a throw is a retry.** WorkManager reads an exception escaping `doWork()` as
  `Result.failure()` — the occurrence is dropped and never retried, whatever the backoff says
  — so an offline moment surfacing as an `IOException` would silently cost a sync cycle.
  `safeCall` catches it and the use case maps it to the same budget-aware retry as a counted
  shortfall.
- **That cancellation is not a failure.** WorkManager cancels a worker's coroutine when a
  constraint stops being met mid-run. `safeCall` rethrows `CancellationException` rather than
  reporting it, so a stopped run does not burn an attempt. See
  [`structured-concurrency.md`](./structured-concurrency.md).

The boundary also has a module rule behind it, not just a preference: `:app` may not name a
type from `:data` — `checkModuleDependencies` allows that edge only so `:data`'s Hilt modules
reach the component — so an `Application` that built its own `PeriodicWorkRequest` would need
that rule relaxed.

## Wiring a worker that takes dependencies

Three things have to be true together, and each fails differently when it is the one missing.

1. **`@HiltWorker` and `@AssistedInject` on the worker.** A worker is constructed by
   WorkManager, not by the graph, so two of its parameters come from the framework and the
   rest from Hilt. `@AssistedInject` expresses that split; `@HiltWorker` generates the
   `HiltWorkerFactory` entry. Both are required and neither implies the other.
2. **`androidx.hilt:hilt-compiler` on the KSP configuration.** Declaring only
   `hilt-work` compiles and then fails at run time with `Could not instantiate …Worker`,
   because nothing generated the binding the factory looks the worker up in.
3. **The default initializer removed from the manifest.** WorkManager's own manifest
   contributes an `androidx.startup` provider that initialises it eagerly with the *default*
   `WorkerFactory`, which can only build two-argument workers. Removing that entry switches
   WorkManager to on-demand initialisation, where it asks the `Application` for a
   `Configuration`. `BoilerplateApp` implements `Configuration.Provider` and supplies the
   `HiltWorkerFactory` there.

The removal is `tools:node="remove"` on the *meta-data*, not on the provider.
`InitializationProvider` is shared by every library using App Startup, so removing the
provider would take any other initializer down with it.

Note that `workManagerConfiguration` is read *before* `onCreate` in principle — WorkManager
asks for it from inside `getInstance` — while Hilt injects the `Application`'s fields during
`super.onCreate()`. Every path the app itself takes reaches `getInstance` after that, but a
worker factory read earlier would be reading an uninitialised field.

## What is tested where

| Question | Where | Runs in CI |
| --- | --- | --- |
| Which mode a background sync uses; shortfall, throw and cancellation handling; the attempt boundary | `PerformBackgroundSyncUseCaseTest` | Yes — `testDebugUnitTest`, and the offline harness |
| The enqueued request: interval, flex, constraints, backoff, tag, unique name, `UPDATE` | `WorkManagerBackgroundSyncSchedulerTest` | Yes — `testDebugUnitTest` only; WorkManager is a Google Maven artifact the offline harness cannot fetch |
| That Hilt can actually construct `UserSyncWorker`; that a run happens; that the platform honours the constraints | nowhere yet | No |

The third row is the honest gap. Verifying it needs `WorkManagerTestInitHelper` and
`TestListenableWorkerBuilder`, which need a `Context` and a real database, which means the
instrumented source set — and this repository has no emulator runner yet. That is Phase 12,
"Instrumented tests on an emulator matrix in CI". What stands in for it today is
`compileDebugKotlin`: KSP fails the build on an unsatisfied Hilt binding, so a worker asking
for something the graph cannot supply does not reach a device.

`WorkManagerBackgroundSyncSchedulerTest` reads `WorkRequest.workSpec`, which is
`@RestrictTo(LIBRARY_GROUP)`. There is no published alternative for reading a built request
back, and a test asserting that the request carries the schedule it was configured with is not
the case the restriction is aimed at. Lint does not analyse unit test sources at AGP's default,
and the file carries `@SuppressLint("RestrictedApi")` so that enabling `checkTestSources` later
stays a one-line change.

The values in that test are duplicated literals rather than reads of `UserSyncSchedule`.
Reading them back would make the test pass for any schedule at all, including an empty
`Constraints`; duplicating them means changing the interval fails the test and makes someone
confirm the change was meant.

## Known gaps

- **A failure's cause is not reported anywhere.** `safeCall` catches the throwable and the use
  case maps it to an outcome, so a background sync failing for a novel reason is visible as a
  retried worker and nothing more. `CoroutineFailureReporter` is scoped to *uncaught* coroutine
  failures, which this is not; the crash-reporting seam that should take it is a Phase 11 item.
- **`cancelPeriodicSync` has no production caller.** The two moments an app has to stop —
  sign-out, and a user turning background refresh off — do not exist in this boilerplate yet.
  It is declared and tested because a scheduler that can only add work is half an interface,
  and because the alternative at sign-out is a worker that keeps waking up to refresh an
  account nobody is signed in to.
- **Nothing observes the sync's state.** `WorkManager.getWorkInfosByTagFlow` would let a screen
  show "last synced 3 hours ago" or surface a persistent failure. That is a UI decision with no
  screen asking for it yet.
- **The sync runs whether or not anyone is signed in.** `CurrentUserSyncStrategy` will fail
  against an unauthenticated API and the run will retry and then give up, four times a day, on
  a device that has never signed in. Gating the schedule on the auth state is the natural
  follow-up and belongs with the sign-out cancellation above.

## Adding a second background job

1. Write the worker in `:data` with `@HiltWorker` and `@AssistedInject`, keeping its `doWork`
   to a translation of a use case's answer.
2. Put the decisions in a `…UseCase` in `core.domain.usecase`, and add it to
   `DOCUMENTED_USE_CASES` and `AUDITED_USE_CASES` — see
   [`clean-architecture.md`](./clean-architecture.md).
3. Give it its own schedule object with its own unique name, and add the shared
   `UserSyncSchedule.TAG` so that `cancelPeriodicSync` still stops everything.
4. Extend `BackgroundSyncScheduler` only if the *app* needs to say something new. A second job
   scheduled from the same `ensurePeriodicSyncScheduled` call needs no new method.
