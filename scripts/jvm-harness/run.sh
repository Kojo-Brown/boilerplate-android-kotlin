#!/usr/bin/env bash
#
# Offline gate harness: compile, test and lint as much of this module as can be done
# without the Android SDK.
#
# Why this exists
# ---------------
# The five gates in CLAUDE.md all go through Gradle, and Gradle cannot configure this
# project without the Android Gradle Plugin, which lives on Google Maven. In the scheduled
# agent's environment `dl.google.com` answers 403 on CONNECT, so AGP does not resolve, no
# Android SDK can be installed, and none of the five gates run. Maven Central *is*
# reachable, and most of this module is ordinary JVM Kotlin: view models, use cases,
# repositories, the coroutine utilities and their tests. This runs the Kotlin compiler,
# JUnit and detekt over that subset directly.
#
# It is a pre-flight check, not a replacement for CI. What it cannot cover is listed in
# README.md next to this script.
#
# Usage:  scripts/jvm-harness/run.sh [--jars-dir DIR] [--skip-detekt]
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
JARS="${JVM_HARNESS_JARS:-$HOME/.jvm-harness/jars}"
WORK="$ROOT/build/jvm-harness"
RUN_DETEKT=1

while [ $# -gt 0 ]; do
  case "$1" in
    --jars-dir) JARS="$2"; shift 2 ;;
    --skip-detekt) RUN_DETEKT=0; shift ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

# Everything the harness needs, and where it comes from. Versions track
# gradle/libs.versions.toml; a mismatch here would test something the build does not ship.
DEPS=(
  "org/jetbrains/kotlin:kotlin-compiler:2.1.0"
  "org/jetbrains/kotlin:kotlin-stdlib:2.1.0"
  "org/jetbrains/kotlin:kotlin-reflect:2.1.0"
  "org/jetbrains:annotations:23.0.0"
  "org/jetbrains/intellij/deps:trove4j:1.0.20200330"
  "org/jetbrains/kotlinx:kotlinx-coroutines-core-jvm:1.9.0"
  "org/jetbrains/kotlinx:kotlinx-coroutines-test-jvm:1.9.0"
  "org/jetbrains/kotlinx:kotlinx-collections-immutable-jvm:0.3.8"
  "org/jetbrains/kotlinx:kotlinx-serialization-core-jvm:1.7.3"
  "javax/inject:javax.inject:1"
  "com/google/dagger:dagger:2.57.2"
  "com/google/dagger:hilt-core:2.57.2"
  "com/squareup/retrofit2:retrofit:2.11.0"
  "com/squareup/okhttp3:okhttp:4.12.0"
  "com/squareup/okhttp3:logging-interceptor:4.12.0"
  "com/squareup/okio:okio-jvm:3.6.0"
  "com/squareup/okhttp3:mockwebserver:4.12.0"
  # mockk and its own runtime dependencies. Resolved by hand because the harness has no
  # dependency resolver; the versions are the ones mockk 1.13.14 declares.
  "io/mockk:mockk-jvm:1.13.14"
  "io/mockk:mockk-dsl-jvm:1.13.14"
  "io/mockk:mockk-core-jvm:1.13.14"
  "io/mockk:mockk-agent-jvm:1.13.14"
  "io/mockk:mockk-agent-api-jvm:1.13.14"
  "org/objenesis:objenesis:3.3"
  "net/bytebuddy:byte-buddy:1.14.9"
  "net/bytebuddy:byte-buddy-agent:1.14.9"
  "org/slf4j:slf4j-api:2.0.9"
  "org/junit/platform:junit-platform-console-standalone:1.11.4"
  "io/gitlab/arturbosch/detekt:detekt-cli:1.23.8:all"
)

fetch() {
  mkdir -p "$JARS"
  local spec path artifact version classifier name url
  for spec in "${DEPS[@]}"; do
    IFS=':' read -r path artifact version classifier <<<"$spec"
    name="$artifact-$version${classifier:+-$classifier}.jar"
    [ -s "$JARS/$name" ] && continue
    url="https://repo1.maven.org/maven2/$path/$artifact/$version/$name"
    # Maven Central answers 429 to a burst of requests, so back off rather than loop.
    local attempt
    for attempt in 1 2 3 4 5; do
      if curl -sSfL -o "$JARS/$name.part" "$url"; then
        mv "$JARS/$name.part" "$JARS/$name"
        echo "  fetched $name"
        break
      fi
      rm -f "$JARS/$name.part"
      [ "$attempt" = 5 ] && { echo "could not fetch $url" >&2; exit 1; }
      sleep $((attempt * 3))
    done
  done
}

jar() { echo "$JARS/$1"; }

echo "==> Dependencies"
fetch
echo "  jars in $JARS"

# The Kotlin compiler is invoked as a library rather than through kotlinc, which is not
# published as a Maven artifact. Three entries on its own classpath are not obvious and each
# one fails in a way that does not name what is missing:
#
#   - org.jetbrains:annotations — without it the JVM backend dies inside AnnotationCodegen
#     with a NoClassDefFoundError for Nullable, reported as "Backend Internal error".
#   - kotlin-stdlib and kotlinx-coroutines-core — without them the compiler dies in
#     CoreApplicationEnvironment.createApplication before reading a single source file.
#   - trove4j — NoClassDefFoundError: gnu/trove/TObjectHashingStrategy, thrown from source
#     collection.
COMPILER_CP="$(jar kotlin-compiler-2.1.0.jar):$(jar kotlin-stdlib-2.1.0.jar)"
COMPILER_CP="$COMPILER_CP:$(jar kotlinx-coroutines-core-jvm-1.9.0.jar)"
COMPILER_CP="$COMPILER_CP:$(jar annotations-23.0.0.jar):$(jar trove4j-1.0.20200330.jar)"

LIB_CP="$(jar kotlin-stdlib-2.1.0.jar):$(jar kotlin-reflect-2.1.0.jar)"
LIB_CP="$LIB_CP:$(jar kotlinx-coroutines-core-jvm-1.9.0.jar)"
LIB_CP="$LIB_CP:$(jar kotlinx-collections-immutable-jvm-0.3.8.jar)"
LIB_CP="$LIB_CP:$(jar kotlinx-serialization-core-jvm-1.7.3.jar)"
LIB_CP="$LIB_CP:$(jar javax.inject-1.jar):$(jar dagger-2.57.2.jar):$(jar hilt-core-2.57.2.jar)"
LIB_CP="$LIB_CP:$(jar retrofit-2.11.0.jar):$(jar okhttp-4.12.0.jar)"
LIB_CP="$LIB_CP:$(jar logging-interceptor-4.12.0.jar):$(jar okio-jvm-3.6.0.jar)"

TEST_CP="$LIB_CP:$(jar kotlinx-coroutines-test-jvm-1.9.0.jar)"
TEST_CP="$TEST_CP:$(jar mockwebserver-4.12.0.jar)"
TEST_CP="$TEST_CP:$(jar mockk-jvm-1.13.14.jar):$(jar mockk-dsl-jvm-1.13.14.jar)"
TEST_CP="$TEST_CP:$(jar mockk-core-jvm-1.13.14.jar)"
TEST_CP="$TEST_CP:$(jar mockk-agent-jvm-1.13.14.jar):$(jar mockk-agent-api-jvm-1.13.14.jar)"
TEST_CP="$TEST_CP:$(jar objenesis-3.3.jar):$(jar byte-buddy-1.14.9.jar)"
TEST_CP="$TEST_CP:$(jar byte-buddy-agent-1.14.9.jar):$(jar slf4j-api-2.0.9.jar)"
TEST_CP="$TEST_CP:$(jar junit-platform-console-standalone-1.11.4.jar)"

# A source file joins the harness when every one of its imports resolves against the jars
# above or against a stub in stubs/. Anything reaching for Room, DataStore, CameraX, MLKit,
# Credential Manager, Navigation or Compose UI is left to CI. Computed from the imports rather
# than from a checked-in file list, so a new file is picked up — or skipped — on its own
# merits instead of being silently missed.
RESOLVABLE='^(kotlin|kotlinx|java|javax\.inject|org\.junit|org\.jetbrains|io\.mockk|dagger|retrofit2|okhttp3|okio|com\.kojo\.boilerplate)\.'
STUBBED='^(android\.util\.Log|android\.content\.Context|android\.net\.NetworkCapabilities|androidx\.lifecycle\.(ViewModel|SavedStateHandle|viewModelScope)|androidx\.compose\.runtime\.(Immutable|Stable)|androidx\.compose\.ui\.graphics\.vector\.ImageVector|androidx\.navigation\.toRoute|androidx\.credentials\.exceptions\.GetCredential(Cancellation)?Exception|dagger\.hilt\.android\.lifecycle\.HiltViewModel)$'

# Files the import scan admits but that cannot run here, each with the reason. Kept as an
# explicit list so an exclusion is a visible decision rather than a silent gap.
declare -a HARD_EXCLUDES=(
  # Asserts over the *whole* app's compiled output — every repository interface and every
  # implementation of one. The harness compiles a subset by construction, so the classes
  # this walks are missing and the assertion fails on the harness's own coverage rather
  # than on anything about the code. `StabilityContractTest` and `DomainLayerContractTest`
  # read compiled output too and do run: they walk out from the view models, which the
  # harness does compile in full. CI is what covers this one.
  "core/architecture/SolidContractTest.kt"
)

select_sources() {
  local dir="$1" file line ok import
  while IFS= read -r file; do
    ok=1
    for exclude in "${HARD_EXCLUDES[@]:-}"; do
      [ -n "$exclude" ] && [[ "$file" == *"$exclude" ]] && ok=0
    done
    if [ "$ok" = 1 ]; then
      while IFS= read -r line; do
        import="${line#import }"
        import="${import%% *}"
        if ! [[ "$import" =~ $RESOLVABLE ]] && ! [[ "$import" =~ $STUBBED ]]; then
          ok=0
          break
        fi
      done < <(grep '^import ' "$file" || true)
    fi
    if [ "$ok" = 1 ]; then echo "$file"; fi
  done < <(find "$dir" -name '*.kt' | sort)
  # Explicit, because the loop's last evaluation is a failed test whenever the final file
  # was skipped, and `set -e` would take that as the function failing.
  return 0
}

# Every top-level name a file declares, fully qualified. Used to answer "is this app import
# satisfied by the selection?" — an import of `core.database.entity.toDomain` is satisfied by
# whichever file declares that top-level function, which is not derivable from the path.
provides() {
  awk '
    /^package / { pkg = $2; next }
    /^(public |internal |private )?(expect |actual )?(abstract |open |sealed |data |value |enum |annotation |inline |external )*(class|interface|object|fun|val|var|typealias) / {
      for (i = 1; i <= NF; i++) {
        if ($i == "class" || $i == "interface" || $i == "object" || $i == "fun" || $i == "val" || $i == "var" || $i == "typealias") {
          name = ""
          for (j = i + 1; j <= NF; j++) name = name (j > i + 1 ? " " : "") $j
          # `fun <T, R> UiState<T>.onSuccess(...)`: drop the type-parameter list, then the
          # signature after the name, then the receiver, then any type arguments on the name.
          # Extension declarations are the reason this is not just $(i + 1): the top-level
          # name they contribute is the part after the last dot.
          if (substr(name, 1, 1) == "<") sub(/^<[^>]*> */, "", name)
          sub(/[(:={ ].*$/, "", name)
          sub(/^.*\./, "", name)
          sub(/<.*$/, "", name)
          if (name ~ /^[A-Za-z_][A-Za-z0-9_]*$/) print pkg "." name
          break
        }
      }
    }
  ' "$1"
}

# Drop any selected file that imports an app symbol the selection does not provide, and
# repeat until nothing more drops. One pass is not enough: dropping UserRepositoryImpl makes
# every file importing *it* unsatisfied in turn.
close_selection() { # sources-file [already-provided-file]
  local sources="$1" inherited="${2:-/dev/null}" changed=1 file import
  while [ "$changed" = 1 ]; do
    changed=0
    cat "$inherited" > "$sources.provided"
    while IFS= read -r file; do provides "$file" >> "$sources.provided"; done < "$sources"
    sort -u "$sources.provided" -o "$sources.provided"
    : > "$sources.next"
    while IFS= read -r file; do
      local ok=1
      while IFS= read -r import; do
        import="${import#import }"
        import="${import%% *}"
        case "$import" in
          com.kojo.boilerplate.*)
            # `import a.b.C.Companion.d` names a member of a top-level declaration, and
            # `provides` only records top-level names — so trim segments off the end until
            # one matches. Without this a test importing a companion helper was dropped
            # without a word, which is how two tests came to run in CI and not here.
            candidate="$import"
            while [ -n "$candidate" ]; do
              grep -qxF "$candidate" "$sources.provided" && break
              case "$candidate" in
                *.*) candidate="${candidate%.*}" ;;
                *) candidate="" ;;
              esac
            done
            [ -n "$candidate" ] || { ok=0; break; } ;;
        esac
      done < <(grep '^import com\.kojo\.boilerplate\.' "$file" || true)
      if [ "$ok" = 1 ]; then echo "$file" >> "$sources.next"; else changed=1; fi
    done < "$sources"
    mv "$sources.next" "$sources"
  done
  return 0
}

compile() { # label output-dir classpath sources-file
  local label="$1" out="$2" cp="$3" sources="$4"
  rm -rf "$out"
  mkdir -p "$out"
  # -jvm-target 17 matches app/build.gradle.kts. It is also required rather than optional:
  # the 1.8 default crashes the JVM backend while generating `safeCall`, a suspend function
  # returning Result.
  java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -classpath "$cp" \
    -d "$out" \
    -jvm-target 17 \
    -nowarn \
    -Xsuppress-version-warnings \
    @"$sources" 2>&1 | grep -v '^warning:' || true
  # K2JVMCompiler's exit code is lost through the pipe above, so the presence of output
  # classes is what decides. An empty output directory after a compile that was given
  # sources is a failure however quiet it was.
  if [ -z "$(find "$out" -name '*.class' -print -quit)" ]; then
    echo "  $label: FAILED (no classes produced)" >&2
    return 1
  fi
  echo "  $label: $(find "$out" -name '*.class' | wc -l) classes"
}

echo
echo "==> Selecting sources"
mkdir -p "$WORK"
select_sources "$ROOT/app/src/main/kotlin" > "$WORK/main.args"
close_selection "$WORK/main.args"
find "$HERE/stubs" -name '*.kt' | sort >> "$WORK/main.args"
select_sources "$ROOT/app/src/test/kotlin" > "$WORK/test.args"
close_selection "$WORK/test.args" "$WORK/main.args.provided"
MAIN_TOTAL=$(find "$ROOT/app/src/main/kotlin" -name '*.kt' | wc -l)
TEST_TOTAL=$(find "$ROOT/app/src/test/kotlin" -name '*.kt' | wc -l)
MAIN_PICKED=$(grep -c 'src/main/kotlin' "$WORK/main.args" || true)
TEST_PICKED=$(wc -l < "$WORK/test.args")
echo "  main: $MAIN_PICKED of $MAIN_TOTAL files (plus $(find "$HERE/stubs" -name '*.kt' | wc -l) stubs)"
echo "  test: $TEST_PICKED of $TEST_TOTAL files"
echo "  skipped (needs the Android toolchain): $(( MAIN_TOTAL - MAIN_PICKED )) main, $(( TEST_TOTAL - TEST_PICKED )) test"
# Named, not just counted. A skipped *test* is a gate this run did not apply, and a silent
# count reads as coverage — which is exactly how a contract test came to be verified only
# in CI. Anything listed here is CI's job.
comm -23 \
  <(find "$ROOT/app/src/test/kotlin" -name '*.kt' | sort) \
  <(sort "$WORK/test.args") |
  sed "s|$ROOT/app/src/test/kotlin/com/kojo/boilerplate/|    not run here: |"

echo
echo "==> Compiling"
compile "main" "$WORK/classes/main" "$LIB_CP" "$WORK/main.args"
compile "test" "$WORK/classes/test" "$TEST_CP:$WORK/classes/main" "$WORK/test.args"

echo
echo "==> Tests"
java -jar "$(jar junit-platform-console-standalone-1.11.4.jar)" execute \
  --class-path "$WORK/classes/test:$WORK/classes/main:$TEST_CP" \
  --scan-class-path "$WORK/classes/test" \
  --details=summary \
  --disable-banner \
  --fail-if-no-tests

if [ "$RUN_DETEKT" = 1 ]; then
  echo
  echo "==> detekt (the same config and source set app/build.gradle.kts uses)"
  java -jar "$(jar detekt-cli-1.23.8-all.jar)" \
    --input "$ROOT/app/src/main/kotlin,$ROOT/app/src/test/kotlin,$ROOT/app/src/androidTest/kotlin" \
    --config "$ROOT/config/detekt/detekt.yml" \
    --build-upon-default-config \
    --base-path "$ROOT" \
    --jvm-target 17
  echo "  detekt: 0 findings"
fi

echo
echo "All harness gates passed."
