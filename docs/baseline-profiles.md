# Baseline profiles and the startup budget

ART installs an app's dex and compiles none of it. Everything on a cold start runs interpreted the
first time, and only becomes machine code days later, once the device's own background profiler has
watched the app long enough to decide what is worth compiling. A baseline profile is that decision,
shipped in the APK: a list of the classes and methods on the paths a user hits immediately, which
ART compiles before first launch instead of learning.

A baseline profile is also the second configuration in this repository that cannot fail on its own —
`app/proguard-rules.pro` being the first, and `docs/r8.md` is its counterpart to this page. AGP
silently drops a profile rule that no longer matches a class in the APK, exactly as R8 ignores a
keep rule whose pattern names nothing. So a profile can rot into a file of rules about a package
that was renamed two releases ago, and the only symptom is that the app starts as slowly as it would
with no profile at all. Nothing goes red. Nothing is logged.

Everything below follows from that: the profile is recorded rather than written, it is checked in
and reviewed, and CI holds it to what the app demonstrably does today rather than to its own
contents.

## The pieces

| Where | What it is |
|---|---|
| `app/src/main/baseline-prof.txt` | The profile. Checked in, in source names, in the human-readable ART format. AGP compiles it into `assets/dexopt/baseline.prof`. |
| `libs.androidx.profileinstaller` in `:app` | The runtime half. Below API 31 nothing installs the profile without it. |
| `:benchmark` | A `com.android.test` module: `BaselineProfileGenerator` records the profile, `StartupBenchmark` measures startup. |
| `:app`'s `benchmark` build type | Minified, not debuggable, profileable. What is measured. |
| `:app`'s `nonMinifiedBenchmark` build type | The same thing without R8. What the profile is recorded from. |
| `config/benchmark/startup-budget.json` | The budget, and the shape the profile has to have. |
| `scripts/verify-startup-budget.py` | The gate. Reads both artifacts and fails on either. |

## Why there are two benchmark build types

`BaselineProfileGenerator` records the names ART saw. Run against the shrunk build those names are
R8's — `a.b.c.d` — and a profile of obfuscated names cannot be the checked-in file, because the next
R8 run assigns different ones. AGP's pipeline runs the other way: it takes source names out of
`src/main/baseline-prof.txt` and rewrites them through the mapping as it packages the APK.

So recording needs a variant that is not renamed, and measuring needs the variant a user would
install. That is the same split the `androidx.baselineprofile` Gradle plugin makes for itself, and
`nonMinifiedBenchmark` is deliberately its vocabulary.

The plugin itself is not used here. It derives its generated build types from `release`, and
`release` cannot be built in this repository while `gradle/certificate-pins.properties` and
`gradle/play-integrity.properties` are empty — the comment beside the `minified` build type in
`app/build.gradle.kts` has the full reasoning, and it is the same reason R8 is verified against
`minified` rather than `release`. Both halves of the plugin's job are therefore done by hand: the
recording by a checked-in rule, the plumbing by two build types and a verifier.

`debug` is not an option for recording either, and the reason is worth stating because it is the
shortcut somebody will reach for. `debugImplementation` pulls in `ui-tooling` and the Compose test
manifest, so a profile recorded there names classes no shipped build contains; and
`isDebuggable = true` changes what ART compiles in the first place, so it would not be a recording
of this app's startup.

## What the gate actually checks

`scripts/verify-startup-budget.py`, run by the `startup-benchmark` job in `ci.yml`. Seven things,
and its docstring is the authoritative list; the ones worth understanding from outside are:

**Both compilation modes, separately.** `StartupBenchmark` measures cold startup twice — once with
`CompilationMode.None()` and once with `CompilationMode.Partial(BaselineProfileMode.Require)` — and
each has its own budget. One number could not tell the two interesting failures apart. When the
app's own startup regresses, both move together. When only the profiled case moves, the *profile* has
stopped covering the startup path, and the app is shipping a file that describes code it no longer
runs. The second failure is invisible to any single measurement and it is the one a checked-in
profile is prone to.

**`Require`, not `UseIfAvailable`.** `UseIfAvailable` falls back to measuring an uncompiled app when
the profile is missing or ART refused it, and reports a perfectly plausible number for a build whose
profile silently stopped being packaged. `Require` fails the test instead.

**`warmupIterations = 0`, and the gate re-checks it.** `CompilationMode.Partial`'s default is to run
the app a few times first and let ART's JIT compile whatever it saw, *on top of* the baseline
profile. The result is faster and attributable to nothing: you can no longer separate what the
checked-in file bought from what the warmup did. The budget file caps warmup at zero and the
verifier fails on a run that exceeded it, because this is a setting a future edit could change
without anything else noticing.

**The profile is non-trivial and names what startup must touch.** A total rule-count floor, a
per-package floor for `Lcom/kojo/boilerplate/` and `Landroidx/compose/`, and a required set of class
descriptors — the `Application` and the launched `Activity`, which run on every cold start by
definition. Every line of the file has to parse as a rule, too: a parser that skipped what it did
not recognise would report "nothing wrong" about a file it had not read.

**The committed profile against one generated in the same run.** Containment over the sets the config
names, in one direction: a class or package the freshly recorded profile covers and the committed one
does not means the committed one is stale. Not equality — two recordings of the same commit differ in
rule order and in which methods ART happened to see often enough to mark hot, so an equality check
would fail on noise rather than on staleness.

## The numbers are emulator numbers

The only hardware CI has is an emulator: a software rasteriser on a CPU shared with the rest of a
GitHub runner, no thermal behaviour, and clocks nobody can lock. Macrobenchmark refuses to run there
and it is right to; `benchmark/build.gradle.kts` suppresses that refusal with
`androidx.benchmark.suppressErrors=EMULATOR,UNLOCKED` and says so in a comment.

What survives the trade is a regression gate measured the same way every time. What does not survive
is any claim about a user's phone. The milliseconds in `config/benchmark/startup-budget.json` must
not be quoted as startup times; they are a line this repository's own history stays under.

`DEBUGGABLE` is deliberately left unsuppressed. The `benchmark` build type is not debuggable, and if
that ever changes this is the error that has to fire — a debuggable build's JIT behaviour would make
every number here meaningless rather than merely noisy.

The API level *is* gated. It decides what ART does with a profile, so `ci.yml` pins API 34 on the
emulator, the budget file declares the same, and the verifier fails when a run disagrees. The device
model is printed and not gated: one API level has several model strings and none of them changes the
measurement, so a check on it would be a flake rather than a guard.

Gating on a real device is the upgrade, and it is a Phase 12 shape of problem rather than a setting:
it needs a self-hosted runner with a phone attached, or a device-farm account and a secret to reach
it. Both are decisions about infrastructure, not about this item.

## Regenerating the profile

The profile is a recording, so it is regenerated rather than edited. Do it after anything that
changes what runs before the first screen: a new start destination, work moved into
`BoilerplateApp.onCreate`, a dependency whose initialisation moved, a Compose or AndroidX bump.

With a device or emulator attached (API 33 or newer — `BaselineProfileRule` needs root below that):

```
./gradlew :benchmark:connectedNonMinifiedBenchmarkAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.kojo.boilerplate.benchmark.BaselineProfileGenerator
cp "$(find benchmark/build/outputs -name '*-baseline-prof.txt' | head -1)" \
    app/src/main/baseline-prof.txt
```

Then measure, and the budget's headroom tells you whether the change was worth it:

```
./gradlew :benchmark:connectedBenchmarkAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.kojo.boilerplate.benchmark.StartupBenchmark
python3 scripts/verify-startup-budget.py
```

Without a device, CI does both: the `startup-benchmark` job prints the profile it generated into the
job log inside a collapsed group, and uploads it in the `startup-benchmark` artifact. That is there
on purpose rather than as a convenience — the scheduled agent that maintains this repository has no
Android SDK at all, because its network answers 403 on CONNECT to `dl.google.com`, so the log is the
only route by which a generated profile reaches a commit.

## Recalibrating the budget

Lowering a budget needs no ceremony: it is a tightened gate, and the commit that earns it is the
commit that lowers it.

Raising one is a decision, and it belongs in the same commit as the change that made it necessary,
with the reason in the message. A budget raised in a commit of its own is a gate that was in the way,
and the next person has no way to tell that from a regression that was accepted deliberately. If
startup got slower and the cause is not understood, the honest move is a red gate, not a bigger
number.

The one legitimate recalibration that is nobody's regression is a change of measuring hardware —
a different runner image, a different emulator API level. That is a change to `device.sdk` and to
both budgets at once, and the verifier's own output is the input to it: it prints each case's median
and its headroom on every run, pass or fail.

## What this does not cover

Startup is one path. `StartupTimingMetric` reports `timeToInitialDisplayMs` and nothing else here,
because this app never calls `reportFullyDrawn()` — there is no `timeToFullDisplayMs` to budget, and
adding the call is a change to the app rather than to its gates.

Scrolling, navigation and frame timing are not measured. `FrameTimingMetric` over the paged home list
is the obvious next benchmark and it is a different item: it needs a scroll that is stable enough to
compare run to run, which an emulator makes considerably harder than a cold start.
