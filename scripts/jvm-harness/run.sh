#!/usr/bin/env bash
#
# Entry point for the offline gate harness. Everything it does lives in harness.py next to
# this file, which is where the notes are: the module graph it reproduces, the four
# non-obvious arguments the Kotlin compiler needs, and what it cannot cover.
#
# It moved from bash to Python when the app became thirteen Gradle modules. The work stopped
# being "compile a directory" and became "read a dependency graph, order it, and give each
# module exactly the classpath its build file entitles it to" — which is the part that makes
# this a check on the architecture and not just on the code.
#
# Usage:  scripts/jvm-harness/run.sh [--jars-dir DIR] [--skip-detekt] [--skip-tests]
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec python3 "$HERE/harness.py" "$@"
