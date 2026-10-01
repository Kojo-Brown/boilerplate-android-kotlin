#!/usr/bin/env bash
#
# Records the baseline profile and measures cold startup, against whatever device `adb` is
# currently talking to. Run by the `startup-benchmark` job in .github/workflows/ci.yml with an
# emulator already booted; docs/baseline-profiles.md has the local equivalent.
#
# ## Why this is a file and not a `script:` block in the workflow
#
# `reactivecircus/android-emulator-runner` does not hand its `script:` input to a shell as written.
# It runs the lines through `/usr/bin/sh` joined together, so a `\` line continuation never reaches
# the shell as one: the backslash arrives as its own argument, and the first run of this gate died
# with
#
#     Task '\' not found in root project 'BoilerplateAndroidKotlin' and its subprojects.
#
# The same input is also `sh` and not `bash` — dash on an Ubuntu runner — which had already cost a
# run, answering "Illegal option -o pipefail" before the first Gradle invocation.
#
# Both problems are properties of how that action passes a string to a shell, and neither is worth
# encoding as a careful one-liner in YAML. A file with its own shebang has one shell, one set of
# options, and lines that can be as long as they need to be; the workflow invokes it in a single
# word, which is the one shape the action cannot mangle. It is also where a reviewer would look for
# the two commands this gate actually runs, rather than inside a workflow step.
set -euo pipefail

# One Gradle invocation per variant, because they are two runs against two different builds of the
# app, and `-Pandroid.testInstrumentationRunnerArguments.class` is what keeps each to its own test.
# Without the filter both classes would run on both variants: the generator would record a second
# profile from the shrunk build, in R8's names, and the benchmark would measure the unshrunk one.
#
# `app/build.gradle.kts` has the reasoning for the two build types. In short: a profile has to be
# recorded from a build that is not renamed, and startup has to be measured on the build a user
# would install.
GENERATOR_CLASS=com.kojo.boilerplate.benchmark.BaselineProfileGenerator
BENCHMARK_CLASS=com.kojo.boilerplate.benchmark.StartupBenchmark

# The generator goes first, and the order is load-bearing rather than tidy: its output is what the
# staleness half of `scripts/verify-startup-budget.py` compares the committed profile against, and
# the benchmark below is the step most likely to fail — `BaselineProfileMode.Require` fails outright
# when the committed profile is missing or ART refused it. Recording first means that failure still
# leaves a freshly generated profile on disk for the workflow to print and upload.
echo "==> Recording the baseline profile (nonMinifiedBenchmark)"
./gradlew :benchmark:connectedNonMinifiedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.class="$GENERATOR_CLASS" --stacktrace

echo "==> Measuring cold startup (benchmark)"
./gradlew :benchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.class="$BENCHMARK_CLASS" --stacktrace
