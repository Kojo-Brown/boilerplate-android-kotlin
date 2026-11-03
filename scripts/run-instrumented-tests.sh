#!/usr/bin/env bash
#
# Runs every module's instrumented tests against whatever device `adb` is currently talking to.
# Invoked by the `instrumented` job in .github/workflows/ci.yml with an emulator already booted;
# docs/instrumented-testing.md has the local equivalent.
#
# ## Why this is a file and not a `script:` block in the workflow
#
# `reactivecircus/android-emulator-runner` does not hand its `script:` input to a shell as
# written. It runs the lines through `/usr/bin/sh` joined together, so a `\` line continuation
# never reaches the shell as one — the backslash arrives as its own argument and Gradle answers
#
#     Task '\' not found in root project 'BoilerplateAndroidKotlin' and its subprojects.
#
# and the same input is `sh` rather than `bash`, which is dash on an Ubuntu runner and answers
# "Illegal option -o pipefail". Both cost `scripts/run-startup-benchmark.sh` a CI round trip each
# before that gate learned the same lesson; the comment at the top of that file has the detail.
# A file with its own shebang has one shell, one set of options and lines that can be as long as
# they need to be, and the workflow invokes it in the single word the action cannot mangle.
set -euo pipefail

# What the suite actually ran against, in the log, before anything that can fail. A red leg of
# the matrix is read by somebody who needs to know which API level produced it, and the job name
# says which level was *requested* — this says which one answered.
echo "==> Device"
adb devices -l
echo "    API level:    $(adb shell getprop ro.build.version.sdk | tr -d '\r')"
echo "    Release:      $(adb shell getprop ro.build.version.release | tr -d '\r')"
echo "    ABI:          $(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
echo "    Fingerprint:  $(adb shell getprop ro.build.fingerprint | tr -d '\r')"

# No project path, like every gate in CLAUDE.md: the task exists in each of the fifteen modules,
# so one invocation covers all of them and a module that takes up instrumented testing later is
# picked up without an edit here. Five have `src/androidTest` today; the other ten report
# NO-SOURCE and install nothing.
#
# `:benchmark` is the sixteenth project and is deliberately not in this set — it disables its own
# `connectedDebugAndroidTest`, and benchmark/build.gradle.kts says why next to the line that does
# it. Nothing is excluded from here, which is what keeps this command the same one a developer
# would type.
#
# `--no-daemon` is deliberately absent: `gradle/actions/setup-gradle` manages the daemon and the
# cache, and this is the only Gradle invocation in the job that has a booted emulator in front of
# it.
echo
echo "==> connectedDebugAndroidTest"
./gradlew connectedDebugAndroidTest --stacktrace
