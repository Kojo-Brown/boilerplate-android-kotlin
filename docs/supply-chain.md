# Dependency and licence scanning

An Android app is mostly other people's code. `gradle/libs.versions.toml` declares about seventy
coordinates and the APK ships several hundred modules, because most of what is in it arrived
transitively: Compose through a BOM, Guava's annotations through protobuf, the Play services
basement through ML Kit, Okio through OkHttp. Two questions follow from that, and until something
asks them on every pull request the answer to both is "nobody has looked".

1. **May we ship it?** Every one of those artifacts is published under a licence, and a few of
   them are not published under one this repository can accept.
2. **Should we ship it?** A dependency with a published vulnerability is in the app the moment it
   is on the classpath, whether or not this app calls the affected code.

Both are answered from one resolution of one configuration, and that is the design decision worth
reading first.

## The pieces

| Where | What it is |
|---|---|
| `libs.plugins.licensee` on `:app` | Licensee 1.12.0. Resolves `:app`'s debug runtime classpath, reads each artifact's POM, and validates the licences against the allow list. |
| `licensee { }` in `app/build.gradle.kts` | The licence policy: three SPDX identifiers and one URL. |
| `app/build/reports/licensee/debug/artifacts.json` | Every external module in the shipped set, with its licences. Licensee's output and the scanner's input. |
| `scripts/scan-dependencies.py` | Queries OSV.dev for every coordinate in that report. |
| `config/supply-chain/vulnerability-allowlist.txt` | Vulnerabilities this project ships with anyway, each with a reason and an expiry. |
| `scripts/scan-dependencies.test.py` | The scanner's failure paths, driven against a local stand-in for the OSV API. |
| `supply-chain` job in `.github/workflows/ci.yml` | Runs all of it on every push and pull request. |

## One resolution, two questions

A scanner fed `gradle/libs.versions.toml` would check the tenth of the graph somebody typed and
report nothing about the rest — and "no vulnerabilities found" from a tool that looked at seventy
of six hundred modules is worse than no tool, because it is a green check that answers a narrower
question than the one it appears to answer.

What both halves need, then, is the resolved classpath. Licensee already produces exactly that:
it resolves the configuration, walks every POM including parents, normalises what it finds to
SPDX identifiers, and writes the lot to `artifacts.json`. So the licence gate is Licensee, and the
vulnerability gate reads Licensee's report rather than resolving the graph a second time.

That is also why the two live in one CI job. They are two questions about one list.

## Why `:app`, and why `debug`

**`:app`**, because the licence question is about the artifact a user installs and `:app` is the
only module that knows what goes into one. Every `:feature:*` and `:core:*` module's runtime
classpath is a subset of this one's — a transitive dependency of any module reaches `:app` by
definition — so applying Licensee fifteen times would ask the same question fifteen times and
answer it once. Project dependencies are not licence-checked at all: Licensee skips
`project(...)` components, which is right, because the licence of this repository's own code is
this repository's own business.

**`debug`**, because `release` is deliberately unbuildable here. `:data` fails
`compileReleaseKotlin` while `gradle/certificate-pins.properties` and
`gradle/play-integrity.properties` are empty, and they are empty because the values would have to
name a real server's keys and a real Play Console project — see `docs/certificate-pinning.md` and
`docs/root-detection.md`. A gate wired to `release` would be a gate that never produces a result.
`debug` is also a superset for this question: every dependency in this repository is declared
`implementation`, so nothing is in `releaseRuntimeClasspath` without being in
`debugRuntimeClasspath`.

The other variants' Licensee tasks are disabled in `app/build.gradle.kts` rather than deleted, so
the aggregate `licensee` task — the one Licensee attaches `check` to — still runs the debug check
and `./gradlew check` gates licences without touching the release classpath.

## The licence policy

```kotlin
licensee {
    allow("Apache-2.0")
    allow("BSD-3-Clause")
    allow("MIT")
    allowUrl("https://developer.android.com/studio/terms.html") { it.because("…") }
    violationAction(ViolationAction.FAIL)
    unusedAction(UnusedAction.LOG)
}
```

Three identifiers cover almost the whole graph: Apache-2.0 for everything AndroidX, JetBrains,
Square and Dagger publish, BSD-3-Clause for `protobuf-javalite`, MIT for
`org.checkerframework:checker-qual`.

The URL is the entry worth explaining. Google ships Play services, Play Integrity and ML Kit under
the **Android Software Development Kit License**, which has no SPDX identifier at all — so
Licensee reports it as an *unknown* licence carrying only the URL from the POM, and the only way
to accept it is by that URL. It is accepted because it is the licence every Android app built on
Play services already ships under.

Pinned by URL rather than waved through with `ignoreDependencies("com.google.mlkit")`, because the
two differ in exactly the case that matters: a Google artifact appearing under some *other*
non-SPDX licence still fails the URL form, and would have been silently accepted by the group
form.

`it.because` rather than a bare `because`: Licensee declares that overload as
`Action<AllowUrlOptions>`, so Kotlin's SAM conversion hands the options in as the lambda's
parameter. The Groovy form in Licensee's own README reads `because '…'` because a Groovy
closure gets the delegate instead.

Nothing permissive is added speculatively. An `allow` that no longer matches anything is reported
by `unusedAction`, which is `LOG` below because the plugin offers only `LOG` and `IGNORE` — see
**What is not covered**.

## The vulnerability gate

`scripts/scan-dependencies.py` reads `artifacts.json`, deduplicates the coordinates, and asks
OSV.dev's `querybatch` endpoint about each one by Maven ecosystem coordinate and exact resolved
version. Anything the database reports fails the run unless the allowlist names it.

Five things it checks, and the first is the one that matters most:

1. **The report exists, parses, and is not empty.** Every other way a scanner can fail is loud.
   "Scanned nothing successfully" is the one that looks exactly like success, so a missing,
   truncated or empty `artifacts.json` fails.
2. **Every coordinate is complete** — a group, an artifact and a version. Skipping an incomplete
   one would make the gate's coverage depend on what a POM happened to omit.
3. **Every coordinate is queried**, in batches of a hundred. A `querybatch` answer whose length
   does not match the questions fails rather than being lined up by guess: OSV's results array is
   positional, and a wrong alignment attributes a vulnerability to the wrong artifact.
4. **The allowlist is checked in both directions.** An entry naming a vulnerability the scan no
   longer finds fails the run, exactly as `config/r8/shrunk-away-allowlist.txt` is checked both
   ways. An exception that outlives the fact it records is how a suppression becomes permanent.
   Entries also carry an expiry and fail once it passes, so somebody decides a second time.
5. **An unreachable database fails.** A scanner that passes when it could not ask is worse than
   no scanner.

Withdrawn advisories are not findings: a build that goes red for an advisory its own source has
retracted is a build nobody trusts.

**Severity is reported, never thresholded.** A gate with a CVSS floor argues about scores instead
of about whether to ship. Anything the database knows about fails, and the allowlist is where a
decision to ship anyway is written down — with a reason and a date.

### The allowlist

```
<vulnerability-id>  <group:artifact>  <expires YYYY-MM-DD>  <reason>
```

The id may be the OSV identifier or any alias OSV records for it, a CVE included. The coordinate
carries no version on purpose: a suppression pinned to one would stop applying the next time the
dependency moved, which is the moment it matters least and goes unnoticed most.

It is empty today, and adding an entry is the last resort. The first is raising the dependency to
a release that fixes it — and because every version here is pinned in `gradle/libs.versions.toml`
with the reason for the pin beside it, that is a change with a written trail either way.

## Running it

```bash
./gradlew :app:licenseeDebug
python3 scripts/scan-dependencies.py
```

Neither works in the scheduled agent's environment and that is not a shortcut taken by choice:
`dl.google.com` answers 403 to its egress proxy, so AGP and the Android SDK cannot be fetched and
no Gradle gate in this repository runs there — and `api.osv.dev` is refused the same way. CI is
the source of truth, as it is for every other gate here.

What does run anywhere is the scanner's own test suite, which needs no Gradle, no SDK, no APK and
no network:

```bash
python3 scripts/scan-dependencies.test.py
```

It serves OSV's API from a local HTTP server with canned records and drives the real script
through each failure path: a finding that must fail, an allowlist entry that must stop it failing,
an expiry that must start it failing again, an entry matching nothing, a withdrawn advisory, four
shapes of malformed allowlist line, a truncated batch response, and an unreachable database. CI
runs it ahead of the Gradle work so a broken scanner is reported immediately rather than after the
step it would have gated.

## What is not covered

- **Build-time and test-only dependencies.** Nothing in `testImplementation`, nothing on the
  Gradle plugin classpath, nothing KSP runs. None of it is in the APK, and a CVE in a test double
  is not a vulnerability in the app. They are not unexamined — every version is pinned in the
  catalog and `dependency-resolution.yml` resolves all of them — but they are out of this gate's
  scope.
- **An `allow` that stopped matching.** Licensee's `unusedAction` offers `LOG` and `IGNORE` and
  not `FAIL`, so a licence entry that is no longer needed appears in the job log rather than
  failing the build. The allowlist in `config/supply-chain/` has no such gap: that one is checked
  both ways by a script this repository owns.
- **Reachability.** The gate asks whether a vulnerable version is on the classpath, not whether
  this app calls the affected code path. That is the right question for a gate — call-graph
  reachability analysis is a research problem, and "we do not call it" is an argument that belongs
  in an allowlist entry's reason, where a human writes it down.
- **Anything OSV does not know.** A vulnerability with no published advisory is invisible here,
  and a newly published one turns a previously green commit red on the next run with no code
  change. That is the gate working: the dependency did not change, what is known about it did.
- **The licences of transitive *source*, and NOTICE file generation.** Licensee validates
  licences; it does not produce the attribution screen an app shipping to Play should carry.
  `artifacts.json` is the right input for one, and nothing in this repository builds it yet.
