#!/usr/bin/env python3
"""Offline gate harness: compile, test and lint as much of this repository as can be done
without the Android SDK.

Why this exists
---------------
The five gates in CLAUDE.md all go through Gradle, and Gradle cannot configure this project
without the Android Gradle Plugin, which lives on Google Maven. In the scheduled agent's
environment `dl.google.com` answers 403 on CONNECT, so AGP does not resolve, no Android SDK can
be installed, and none of the five gates run. Maven Central *is* reachable, and most of this
repository is ordinary JVM Kotlin: view models, use cases, repositories, the coroutine
utilities and their tests. This runs the Kotlin compiler, JUnit and detekt over that subset
directly.

It is a pre-flight check, not a replacement for CI. What it cannot cover is listed in
README.md next to this script.

What modularisation added
-------------------------
Each module is compiled on its own, in dependency order, against **only the modules it
declares a dependency on** — `api` edges travelling transitively and `implementation` edges
not, as Gradle does it. That makes this harness the one place outside CI where the module graph
is actually enforced rather than described: a file reaching into a module its own build file
does not name fails here, by name, in a couple of seconds.

Usage:  scripts/jvm-harness/harness.py [--jars-dir DIR] [--skip-detekt] [--skip-tests]
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
WORK = ROOT / "build" / "jvm-harness"
APP_PACKAGE = "com.kojo.boilerplate"

# Everything the harness needs, and where it comes from. Versions track
# gradle/libs.versions.toml; a mismatch here would test something the build does not ship.
DEPS = [
    "org/jetbrains/kotlin:kotlin-compiler:2.1.0",
    "org/jetbrains/kotlin:kotlin-stdlib:2.1.0",
    "org/jetbrains/kotlin:kotlin-reflect:2.1.0",
    "org/jetbrains:annotations:23.0.0",
    "org/jetbrains/intellij/deps:trove4j:1.0.20200330",
    "org/jetbrains/kotlinx:kotlinx-coroutines-core-jvm:1.9.0",
    "org/jetbrains/kotlinx:kotlinx-coroutines-test-jvm:1.9.0",
    "org/jetbrains/kotlinx:kotlinx-collections-immutable-jvm:0.3.8",
    "org/jetbrains/kotlinx:kotlinx-serialization-core-jvm:1.7.3",
    "javax/inject:javax.inject:1",
    "com/google/dagger:dagger:2.57.2",
    "com/google/dagger:hilt-core:2.57.2",
    "com/squareup/retrofit2:retrofit:2.11.0",
    "com/squareup/okhttp3:okhttp:4.12.0",
    "com/squareup/okhttp3:logging-interceptor:4.12.0",
    "com/squareup/okio:okio-jvm:3.6.0",
    "com/squareup/okhttp3:mockwebserver:4.12.0",
    # mockk and its own runtime dependencies. Resolved by hand because the harness has no
    # dependency resolver; the versions are the ones mockk 1.13.14 declares.
    "io/mockk:mockk-jvm:1.13.14",
    "io/mockk:mockk-dsl-jvm:1.13.14",
    "io/mockk:mockk-core-jvm:1.13.14",
    "io/mockk:mockk-agent-jvm:1.13.14",
    "io/mockk:mockk-agent-api-jvm:1.13.14",
    "org/objenesis:objenesis:3.3",
    "net/bytebuddy:byte-buddy:1.14.9",
    "net/bytebuddy:byte-buddy-agent:1.14.9",
    "org/slf4j:slf4j-api:2.0.9",
    "org/junit/platform:junit-platform-console-standalone:1.11.4",
    "io/gitlab/arturbosch/detekt:detekt-cli:1.23.8:all",
]

# A source file joins the harness when every one of its imports resolves against the jars above
# or against a stub in stubs/. Anything reaching for Room, DataStore, CameraX, ML Kit, Credential
# Manager, Navigation or Compose UI is left to CI. Computed from the imports rather than from a
# checked-in file list, so a new file is picked up — or skipped — on its own merits.
RESOLVABLE = re.compile(
    r"^(kotlin|kotlinx|java|javax\.inject|org\.junit|org\.jetbrains|io\.mockk"
    r"|dagger|retrofit2|okhttp3|okio|com\.kojo\.boilerplate)\."
)
STUBBED = re.compile(
    r"^(android\.util\.Log"
    r"|android\.content\.Context"
    r"|android\.net\.NetworkCapabilities"
    r"|androidx\.lifecycle\.(ViewModel|SavedStateHandle|viewModelScope)"
    r"|androidx\.compose\.runtime\.(Immutable|Stable)"
    r"|androidx\.compose\.ui\.graphics\.vector\.ImageVector"
    r"|androidx\.navigation\.toRoute"
    r"|androidx\.credentials\.exceptions\.GetCredential(Cancellation)?Exception"
    r"|dagger\.hilt\.android\.lifecycle\.HiltViewModel)$"
)

# Files the import scan admits but that cannot run here, each with the reason. Kept as an
# explicit list so an exclusion is a visible decision rather than a silent gap.
HARD_EXCLUDES = {
    # Asserts over the *whole* app's compiled output — every repository interface and every
    # implementation of one. The harness compiles a subset by construction, so the classes this
    # walks are missing and the assertion fails on the harness's own coverage rather than on
    # anything about the code. CI is what covers this one.
    "app/src/test/kotlin/com/kojo/boilerplate/architecture/SolidContractTest.kt":
        "asserts over every repository type in the app; the harness compiles a subset",
    # The same problem in its purest form: it asserts that at least one class was compiled for
    # every module. Under the harness the Compose-only modules contribute none, by design.
    "app/src/test/kotlin/com/kojo/boilerplate/architecture/CompiledAppTest.kt":
        "asserts every module contributed a class; the Compose modules cannot compile here",
}


# --------------------------------------------------------------------------------------- jars


def fetch(jars: Path) -> None:
    jars.mkdir(parents=True, exist_ok=True)
    for spec in DEPS:
        parts = spec.split(":")
        path, artifact, version = parts[0], parts[1], parts[2]
        classifier = parts[3] if len(parts) > 3 else ""
        name = f"{artifact}-{version}" + (f"-{classifier}" if classifier else "") + ".jar"
        target = jars / name
        if target.exists() and target.stat().st_size > 0:
            continue
        url = f"https://repo1.maven.org/maven2/{path}/{artifact}/{version}/{name}"
        # Maven Central answers 429 to a burst of requests, so back off rather than loop.
        for attempt in range(1, 6):
            try:
                with urllib.request.urlopen(url, timeout=120) as response:
                    target.write_bytes(response.read())
                print(f"  fetched {name}")
                break
            except (urllib.error.URLError, TimeoutError) as error:
                if attempt == 5:
                    sys.exit(f"could not fetch {url}: {error}")
                time.sleep(attempt * 3)


# ------------------------------------------------------------------------------- module graph


@dataclass
class Module:
    """One Gradle module, as much of it as this harness needs to know."""

    path: str                       # ":core:common"
    directory: Path                 # ROOT / "core/common"
    namespace: str = ""
    # True when the module puts the test libraries on its *main* classpath — `:core:testing`
    # does, because its fakes and JUnit rules are main-source classes that other modules'
    # tests depend on. Everywhere else JUnit is `testImplementation` and this stays false.
    ships_test_libraries: bool = False
    api: list[str] = field(default_factory=list)
    implementation: list[str] = field(default_factory=list)
    test: list[str] = field(default_factory=list)

    @property
    def name(self) -> str:
        return self.path.lstrip(":").replace(":", "/")


PROJECT_DEPENDENCY = re.compile(
    r"^\s*(api|implementation|testImplementation|androidTestImplementation)"
    r"\(project\(\"(:[\w:-]+)\"\)\)",
    re.M,
)


def read_modules() -> dict[str, Module]:
    """The module graph, read from `settings.gradle.kts` and the modules' own build files.

    Parsed rather than obtained from Gradle for the reason the whole harness exists: Gradle
    cannot configure this project here at all. The two files are the source of truth either way,
    and a parse that drifted from them would show up immediately as a compile failure.
    """
    settings = (ROOT / "settings.gradle.kts").read_text()
    modules: dict[str, Module] = {}
    for path in re.findall(r'^include\("(:[\w:-]+)"\)', settings, re.M):
        directory = ROOT / path.lstrip(":").replace(":", "/")
        module = Module(path=path, directory=directory)
        build_file = directory / "build.gradle.kts"
        if build_file.exists():
            text = build_file.read_text()
            namespace = re.search(r'^\s*namespace = "([\w.]+)"', text, re.M)
            module.namespace = namespace.group(1) if namespace else ""
            module.ships_test_libraries = bool(
                re.search(r"^\s*(api|implementation)\(libs\.(junit|kotlinx\.coroutines\.test)", text, re.M)
            )
            for configuration, dependency in PROJECT_DEPENDENCY.findall(build_file.read_text()):
                if configuration == "api":
                    module.api.append(dependency)
                elif configuration == "implementation":
                    module.implementation.append(dependency)
                else:
                    module.test.append(dependency)
        modules[path] = module
    return modules


def visible_from(modules: dict[str, Module], path: str, include_test: bool) -> list[str]:
    """The modules whose classes are on `path`'s compile classpath, in Gradle's own terms.

    Direct dependencies are visible whatever configuration declared them; their dependencies are
    visible only through `api`. That distinction is the whole point of `api` vs `implementation`,
    and reproducing it here is what lets this harness fail on a dependency the build file does
    not declare instead of quietly finding the class anyway.
    """
    direct = list(modules[path].api) + list(modules[path].implementation)
    if include_test:
        direct += modules[path].test
    seen: list[str] = []
    queue = list(direct)
    while queue:
        current = queue.pop(0)
        if current in seen or current not in modules:
            continue
        seen.append(current)
        queue.extend(modules[current].api)
    return seen


def topological_order(modules: dict[str, Module]) -> list[str]:
    """Compile order, over `main` dependencies only.

    Test dependencies are deliberately excluded: `:core:testing` depends on `:core:auth` and
    `:core:auth`'s *tests* depend on `:core:testing`, which is a cycle only if the two source
    sets are treated as one node. They are not — every module's `main` is compiled before any
    module's `test` — and Gradle models it the same way.
    """
    ordered: list[str] = []
    visiting: set[str] = set()

    def visit(path: str) -> None:
        if path in ordered:
            return
        if path in visiting:
            sys.exit(f"dependency cycle through {path}")
        visiting.add(path)
        module = modules[path]
        for dependency in module.api + module.implementation:
            if dependency in modules:
                visit(dependency)
        visiting.discard(path)
        ordered.append(path)

    for path in modules:
        visit(path)
    return ordered


# ------------------------------------------------------------------------------ source layout


PACKAGE = re.compile(r"^package\s+([\w.]+)", re.M)
IMPORT = re.compile(r"^import\s+([\w.]+)", re.M)


def sources_in(module: Module, source_set: str) -> list[Path]:
    root = module.directory / "src" / source_set / "kotlin"
    if not root.exists():
        return []
    return sorted(root.rglob("*.kt"))


def package_owners(modules: dict[str, Module]) -> dict[str, str]:
    """Package name to the module that declares it, for `main` source sets only.

    Test sources are deliberately absent: a test class is never importable from another module,
    so an import that resolves to one is a mistake this map should not be able to excuse.
    `:core:testing` is the exception that proves it — its fakes are in `src/main` precisely so
    that they *can* be imported.
    """
    owners: dict[str, str] = {}
    # AGP generates `BuildConfig` and `R` into the module's `namespace`, and nothing in `src`
    # declares that package. Registering it here is what lets an import of a generated class
    # resolve to the module that generates it instead of falling back to a shorter package
    # somewhere else — `com.kojo.boilerplate.data.BuildConfig` would otherwise land on `:app`.
    for path, module in modules.items():
        if module.namespace:
            owners[module.namespace] = path
    for path, module in modules.items():
        for source in sources_in(module, "main"):
            match = PACKAGE.search(source.read_text())
            if not match:
                continue
            package = match.group(1)
            previous = owners.get(package)
            if previous and previous != path:
                sys.exit(
                    f"package {package} is declared in both {previous} and {path}. A package "
                    f"split across modules breaks `internal` visibility and confuses every "
                    f"path-scoped rule in the repository; give one of them its own package."
                )
            owners[package] = path
    return owners


def test_package_owners(modules: dict[str, Module]) -> dict[str, str]:
    """Package to module for `test` source sets, used only to produce a better error.

    A test source set is invisible from every other module, so an import that resolves here and
    nowhere else is always a mistake — and one that is easy to make and hard to spot, because
    the file it names is right there in the repository. It cost a silent harness skip and would
    have cost a red CI run: `syncStrategyFactoryOver` sat in `:core:domain`'s tests while
    `:feature:home`'s tests imported it.
    """
    owners: dict[str, str] = {}
    for path, module in modules.items():
        for source in sources_in(module, "test"):
            match = PACKAGE.search(source.read_text())
            if match:
                owners.setdefault(match.group(1), path)
    return owners


def owning_module(owners: dict[str, str], imported: str) -> str | None:
    """The module declaring the symbol `imported` names, by longest matching package.

    `import a.b.C` and `import a.b.C.Companion.d` both have to land on package `a.b`, so the
    lookup walks segments off the end until a package matches.
    """
    candidate = imported
    while "." in candidate:
        candidate = candidate.rsplit(".", 1)[0]
        if candidate in owners:
            return owners[candidate]
    return None


# Import prefixes the harness puts on every module's classpath but Gradle does not. Each is
# supplied by exactly one thing a module has to opt into, and leaning on it without opting in
# compiles here and fails in CI — which is how `:core:testing` came to import `javax.inject`
# with nothing to provide it, after `syncStrategyFactoryOver` moved in.
#
# Deliberately only these two. The rest of the external classpath is androidx, which cannot be
# fetched in this environment and therefore cannot be modelled per module without inventing
# failures.
SUPPLIED_BY_HILT = {
    "javax.inject.": "libs.javax.inject (or the boilerplate.hilt convention)",
    "dagger.": "the boilerplate.hilt convention",
}


def check_supplied_externals(modules: dict[str, Module]) -> list[str]:
    """Flags a module importing `javax.inject` or `dagger` without anything that supplies it.

    Supplied means: the module applies `boilerplate.hilt`, or declares `libs.javax.inject`, or
    reaches a module that declares it as `api`. The harness cannot tell the difference on its
    own, because it hands every module the same jars — which is exactly why this is checked
    against the build files instead of against the compiler.
    """
    def supplies(path: str) -> bool:
        text = (modules[path].directory / "build.gradle.kts").read_text()
        return bool(
            re.search(r'^\s*id\("boilerplate\.hilt"\)', text, re.M)
            or re.search(r"^\s*(api|implementation)\(libs\.javax\.inject\)", text, re.M)
        )

    def exports(path: str) -> bool:
        text = (modules[path].directory / "build.gradle.kts").read_text()
        return bool(re.search(r"^\s*api\(libs\.javax\.inject\)", text, re.M))

    violations: list[str] = []
    for path, module in modules.items():
        reachable = [
            dependency
            for dependency in visible_from(modules, path, include_test=True)
            if dependency in modules
        ]
        available = supplies(path) or any(exports(dependency) for dependency in reachable)
        if available:
            continue
        for source_set in ("main", "test", "androidTest"):
            for source in sources_in(module, source_set):
                for imported in IMPORT.findall(source.read_text()):
                    for prefix, remedy in SUPPLIED_BY_HILT.items():
                        if imported.startswith(prefix):
                            violations.append(
                                f"{source.relative_to(ROOT)} imports {imported}, and {path} "
                                f"declares nothing that provides it — add {remedy}."
                            )
    return violations


# `:core:testing` never reaches `:app` — it is a test-only dependency of other modules — so the
# contract tests there cannot see it and must not expect to.
NOT_ON_APP_CLASSPATH = {":core:testing"}

COMPILED_APP = Path("app/src/test/kotlin/com/kojo/boilerplate/architecture/CompiledApp.kt")


def check_expected_module_packages(modules: dict[str, Module], owners: dict[str, str]) -> list[str]:
    """Cross-checks `CompiledApp.EXPECTED_MODULE_PACKAGES` against the packages that exist.

    That list is what makes the whole-app contract tests fail loudly when a module drops off
    `:app`'s classpath instead of quietly auditing less. It is hand-written, so it drifts: the
    theme's package sat in it for a full CI run after the theme moved into `:core:ui`, and the
    only thing that noticed was a red `CompiledAppTest`. Both directions are checked here —
    an entry matching no package, and a module no entry covers.
    """
    source = ROOT / COMPILED_APP
    if not source.exists():
        return []
    text = source.read_text()
    block = text.split("EXPECTED_MODULE_PACKAGES = listOf(", 1)[1].split(")", 1)[0]
    expected = [
        line.strip().strip('",').replace("$PACKAGE", APP_PACKAGE)
        for line in block.splitlines()
        if line.strip().startswith('"')
    ]

    findings: list[str] = []
    for entry in expected:
        if not any(package == entry or package.startswith(entry + ".") for package in owners):
            findings.append(
                f"{COMPILED_APP}: EXPECTED_MODULE_PACKAGES names {entry}, which no module "
                f"declares any more. Remove it, or point it at where those classes went."
            )
    for path, module in modules.items():
        if path in NOT_ON_APP_CLASSPATH:
            continue
        packages = [package for package, owner in owners.items() if owner == path]
        if not packages:
            continue
        covered = any(
            package == entry or package.startswith(entry + ".")
            for package in packages
            for entry in expected
        )
        if not covered:
            findings.append(
                f"{COMPILED_APP}: no entry in EXPECTED_MODULE_PACKAGES covers {path} "
                f"({', '.join(sorted(packages))}). The contract tests in :app would not notice "
                f"if that module stopped reaching them."
            )
    return findings


def check_module_boundaries(modules: dict[str, Module], owners: dict[str, str]) -> list[str]:
    violations: list[str] = []
    test_owners = test_package_owners(modules)
    for path, module in modules.items():
        for source_set in ("main", "test", "androidTest"):
            allowed = set(visible_from(modules, path, include_test=source_set != "main"))
            allowed.add(path)
            for source in sources_in(module, source_set):
                text = source.read_text()
                for imported in IMPORT.findall(text):
                    if not imported.startswith(APP_PACKAGE + "."):
                        continue
                    owner = owning_module(owners, imported)
                    if owner is None:
                        test_owner = owning_module(test_owners, imported)
                        if test_owner is not None and test_owner != path:
                            violations.append(
                                f"{source.relative_to(ROOT)} imports {imported}, which is "
                                f"declared in {test_owner}'s test sources — no module can see "
                                f"another module's test source set. Move it to :core:testing."
                            )
                        # Otherwise: this module's own test sources, or nowhere at all. kotlinc
                        # is the authority on the second case and reports it far better.
                        continue
                    if owner not in allowed:
                        violations.append(
                            f"{source.relative_to(ROOT)} imports {imported} from {owner}, "
                            f"which {path} ({source_set}) does not depend on"
                        )
    return violations


# ------------------------------------------------------------------ build scripts and comments


def unterminated_code_spans(paths: list[Path]) -> list[str]:
    """Finds a block comment that ends inside a backtick-quoted span.

    A Kotlin block comment ends at the first `*/`, and a path glob quoted inside one —
    ``**/core/domain/**`` — contains exactly that sequence. The comment closes early, the rest of
    the glob becomes source, and the file stops parsing with "Expecting a top level declaration"
    pointing at a line that looks fine. It cost a CI round trip once.

    The rule is narrow on purpose: a `*/` reached while an odd number of backticks is open on
    that line is always this mistake, and nothing else in this repository looks like it.
    """
    findings: list[str] = []
    for path in paths:
        in_block = False
        for number, line in enumerate(path.read_text().splitlines(), start=1):
            index = 0
            backticks = 0
            while index < len(line) - 1:
                pair = line[index:index + 2]
                if not in_block and pair == "/*":
                    in_block = True
                    index += 2
                    continue
                if in_block:
                    if line[index] == "`":
                        backticks += 1
                    if pair == "*/":
                        if backticks % 2 == 1:
                            findings.append(
                                f"{path.relative_to(ROOT)}:{number}: a block comment ends inside "
                                f"a `...` span — the `*/` closes the comment early. Reword it "
                                f"rather than quoting a glob that contains a star and a slash."
                            )
                        in_block = False
                        index += 2
                        continue
                index += 1
    return findings


# Characters that are invisible in an editor and in a diff, and that a reader will therefore
# assume are not there. One of these was load-bearing: a zero-width space sat between the stars
# and the slash of a path glob quoted inside a block comment, and was the only reason that
# comment did not terminate early. Deleting it — which looks like deleting nothing — broke the
# build. Nothing in this repository has a legitimate use for one.
INVISIBLE_CHARACTERS = {
    "\u200b": "ZERO WIDTH SPACE",
    "\u200c": "ZERO WIDTH NON-JOINER",
    "\u200d": "ZERO WIDTH JOINER",
    "\u2060": "WORD JOINER",
    "\ufeff": "ZERO WIDTH NO-BREAK SPACE",
    "\u00a0": "NO-BREAK SPACE",
}


def invisible_characters(paths: list[Path]) -> list[str]:
    findings: list[str] = []
    for path in paths:
        for number, line in enumerate(path.read_text().splitlines(), start=1):
            for character, name in INVISIBLE_CHARACTERS.items():
                if character in line:
                    findings.append(
                        f"{path.relative_to(ROOT)}:{number}: contains {name} "
                        f"(U+{ord(character):04X}), which is invisible to every reader and to "
                        f"every diff. Remove it and say what it was standing in for."
                    )
    return findings


def check_build_logic_parses(compiler: "Compiler") -> None:
    """Parses `build-logic` with no classpath, and reports only syntax diagnostics.

    Nothing offline can *type-check* these files: they compile against AGP, which is exactly
    what this environment cannot fetch. Parsing them is still worth doing — a syntax error here
    fails the very first Gradle invocation, so it costs a full CI round trip to learn something
    the parser knows in two seconds.

    Every semantic diagnostic is expected and discarded; only the parser's own vocabulary
    ("Expecting", "Unexpected", "Unclosed") is treated as a finding.
    """
    sources = sorted((ROOT / "build-logic").rglob("*.kt"))
    if not sources:
        return
    output = compiler.parse_only(sources)
    syntax = [
        line for line in output
        if re.search(r"error:.*(Expecting|Unexpected|Unclosed|Unresolved label)", line)
    ]
    if syntax:
        for line in syntax:
            print(f"  {line}")
        sys.exit(f"\n{len(syntax)} syntax error(s) in build-logic.")
    print(f"  build-logic: {len(sources)} files parse")


# ------------------------------------------------------------------------------- compilation


def selectable(source: Path) -> bool:
    relative = source.relative_to(ROOT).as_posix()
    if relative in HARD_EXCLUDES:
        return False
    for imported in IMPORT.findall(source.read_text()):
        if not RESOLVABLE.match(imported) and not STUBBED.match(imported):
            return False
    return True


def top_level_names(source: Path) -> set[str]:
    """Every top-level name a file declares, fully qualified.

    Used to answer "is this app import satisfied by the selection?" — an import of
    `core.database.entity.toDomain` is satisfied by whichever file declares that top-level
    function, which is not derivable from the path.
    """
    text = source.read_text()
    match = PACKAGE.search(text)
    package = match.group(1) if match else ""
    names: set[str] = set()
    declaration = re.compile(
        r"^\s*(?:@\w+\s*)*"
        r"(?:public |internal |private |abstract |open |sealed |data |value |enum |annotation "
        r"|inline |external |expect |actual |suspend |operator |infix )*"
        # `fun interface` first: matching bare `fun` there would capture the word `interface`
        # as the declared name, which is how `AppEventListener` came to be invisible to the
        # selection and took every file importing it down with it.
        r"(?:fun\s+interface|class|interface|object|fun|val|var|typealias)\s+"
        r"(?:<[^>]*>\s*)?"
        r"(?:[\w.<>?, \[\]]+\.)?"
        r"([A-Za-z_]\w*)",
        re.M,
    )
    for name in declaration.findall(text):
        names.add(f"{package}.{name}")
    return names


def close_selection(selected: list[Path], inherited: set[str]) -> tuple[list[Path], set[str]]:
    """Drop any selected file importing an app symbol the selection does not provide, repeatedly.

    One pass is not enough: dropping `UserRepositoryImpl` makes every file importing *it*
    unsatisfied in turn.
    """
    current = list(selected)
    while True:
        provided = set(inherited)
        for source in current:
            provided |= top_level_names(source)
        keep: list[Path] = []
        for source in current:
            satisfied = True
            for imported in IMPORT.findall(source.read_text()):
                if not imported.startswith(APP_PACKAGE + "."):
                    continue
                candidate = imported
                while candidate and candidate not in provided:
                    candidate = candidate.rsplit(".", 1)[0] if "." in candidate else ""
                if not candidate:
                    satisfied = False
                    break
            if satisfied:
                keep.append(source)
        if len(keep) == len(current):
            provided_final = set(inherited)
            for source in keep:
                provided_final |= top_level_names(source)
            return keep, provided_final
        current = keep


class Compiler:
    """`K2JVMCompiler` invoked as a library, because kotlinc is not published to Maven.

    Four things are needed that no error message names, and all four have been rediscovered more
    than once:

    | Missing                                        | Failure                                   |
    |------------------------------------------------|-------------------------------------------|
    | `org.jetbrains:annotations` on the *compiler's* | "Backend Internal error" —                |
    | classpath                                      | `NoClassDefFoundError: …Nullable` inside  |
    |                                                | `AnnotationCodegen`                       |
    | `kotlin-stdlib` + `kotlinx-coroutines-core`,   | dies in                                   |
    | same place                                      | `CoreApplicationEnvironment.createApplication` |
    |                                                | before reading a source file              |
    | `org.jetbrains.intellij.deps:trove4j`           | `NoClassDefFoundError:                     |
    |                                                | gnu/trove/TObjectHashingStrategy`         |
    | `-jvm-target 17`                                | the 1.8 default crashes the backend       |
    |                                                | generating `safeCall`                     |
    """

    def __init__(self, jars: Path):
        self.jars = jars
        self.compiler_classpath = os.pathsep.join(
            str(jars / name)
            for name in (
                "kotlin-compiler-2.1.0.jar",
                "kotlin-stdlib-2.1.0.jar",
                "kotlinx-coroutines-core-jvm-1.9.0.jar",
                "annotations-23.0.0.jar",
                "trove4j-1.0.20200330.jar",
            )
        )

    def parse_only(self, sources: list[Path]) -> list[str]:
        """Runs the compiler with no classpath and returns its diagnostics, unfiltered."""
        arguments = WORK / "parse.args"
        arguments.parent.mkdir(parents=True, exist_ok=True)
        arguments.write_text("\n".join(str(source) for source in sources) + "\n")
        result = subprocess.run(
            [
                "java", "-cp", self.compiler_classpath,
                "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                "-d", str(WORK / "classes" / "_parse"),
                "-jvm-target", "17",
                "-nowarn",
                "-Xsuppress-version-warnings",
                f"@{arguments}",
            ],
            capture_output=True,
            text=True,
        )
        return [
            line.replace(f"file://{ROOT}/", "")
            for line in (result.stdout + result.stderr).splitlines()
            if line.startswith(("e: ", "error:"))
        ]

    def compile(self, label: str, output: Path, classpath: list[str], sources: list[Path]) -> int:
        if not sources:
            return 0
        if output.exists():
            shutil.rmtree(output)
        output.mkdir(parents=True)
        arguments = WORK / f"{label.replace('/', '_')}.args"
        arguments.write_text("\n".join(str(source) for source in sources) + "\n")
        result = subprocess.run(
            [
                "java", "-cp", self.compiler_classpath,
                "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                "-classpath", os.pathsep.join(classpath),
                "-d", str(output),
                # -jvm-target 17 matches the build. It is also required rather than optional:
                # the 1.8 default crashes the JVM backend while generating `safeCall`, a suspend
                # function returning Result.
                "-jvm-target", "17",
                "-nowarn",
                "-Xsuppress-version-warnings",
                f"@{arguments}",
            ],
            capture_output=True,
            text=True,
        )
        noise = ("warning:", "Picked up JAVA_TOOL_OPTIONS", "info:")
        for line in (result.stdout + result.stderr).splitlines():
            if line and not line.startswith(noise):
                print(f"    {line}")
        produced = len(list(output.rglob("*.class")))
        if produced == 0:
            sys.exit(f"  {label}: FAILED (no classes produced)")
        return produced


# --------------------------------------------------------------------------------------- main


def library_classpath(jars: Path) -> list[str]:
    return [
        str(jars / name)
        for name in (
            "kotlin-stdlib-2.1.0.jar",
            "kotlin-reflect-2.1.0.jar",
            "kotlinx-coroutines-core-jvm-1.9.0.jar",
            "kotlinx-collections-immutable-jvm-0.3.8.jar",
            "kotlinx-serialization-core-jvm-1.7.3.jar",
            "javax.inject-1.jar",
            "dagger-2.57.2.jar",
            "hilt-core-2.57.2.jar",
            "retrofit-2.11.0.jar",
            "okhttp-4.12.0.jar",
            "logging-interceptor-4.12.0.jar",
            "okio-jvm-3.6.0.jar",
        )
    ]


def test_classpath(jars: Path) -> list[str]:
    return library_classpath(jars) + [
        str(jars / name)
        for name in (
            "kotlinx-coroutines-test-jvm-1.9.0.jar",
            "mockwebserver-4.12.0.jar",
            "mockk-jvm-1.13.14.jar",
            "mockk-dsl-jvm-1.13.14.jar",
            "mockk-core-jvm-1.13.14.jar",
            "mockk-agent-jvm-1.13.14.jar",
            "mockk-agent-api-jvm-1.13.14.jar",
            "objenesis-3.3.jar",
            "byte-buddy-1.14.9.jar",
            "byte-buddy-agent-1.14.9.jar",
            "slf4j-api-2.0.9.jar",
            "junit-platform-console-standalone-1.11.4.jar",
        )
    ]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jars-dir", default=os.environ.get(
        "JVM_HARNESS_JARS", str(Path.home() / ".jvm-harness" / "jars")))
    parser.add_argument("--skip-detekt", action="store_true")
    parser.add_argument("--skip-tests", action="store_true")
    options = parser.parse_args()
    jars = Path(options.jars_dir)

    print("==> Dependencies")
    fetch(jars)
    print(f"  jars in {jars}")

    WORK.mkdir(parents=True, exist_ok=True)
    modules = read_modules()
    owners = package_owners(modules)
    order = topological_order(modules)

    print()
    print("==> Module graph")
    for path in order:
        dependencies = modules[path].api + modules[path].implementation + modules[path].test
        print(f"  {path} -> {' '.join(dependencies) if dependencies else '(nothing)'}")

    print()
    print("==> Module boundaries")
    violations = (
        check_module_boundaries(modules, owners)
        + check_supplied_externals(modules)
        + check_expected_module_packages(modules, owners)
    )
    if violations:
        for violation in violations:
            print(f"  VIOLATION {violation}")
        sys.exit(
            f"\n{len(violations)} module boundary violation(s). Either the import is wrong or "
            f"the module's build file should declare what it reaches for — and if the dependency "
            f"is on another module, `moduleDependencyRules` in the root build.gradle.kts has to "
            f"allow it too."
        )
    print(f"  {len(modules)} modules, 0 violations")

    print()
    print("==> Build scripts")
    scripts = sorted(
        set((ROOT / "build-logic").rglob("*.kt"))
        | set(ROOT.glob("*.gradle.kts"))
        | {path for path in ROOT.glob("*/build.gradle.kts")}
        | {path for path in ROOT.glob("*/*/build.gradle.kts")}
        | set((ROOT / "build-logic").rglob("*.gradle.kts"))
    )
    spans = unterminated_code_spans(scripts + [
        source
        for module in modules.values()
        for source_set in ("main", "test", "androidTest")
        for source in sources_in(module, source_set)
    ])
    if spans:
        for finding in spans:
            print(f"  {finding}")
        sys.exit(f"\n{len(spans)} comment(s) closed by a glob inside a code span.")

    hidden = invisible_characters(scripts + [
        source
        for module in modules.values()
        for source_set in ("main", "test", "androidTest")
        for source in sources_in(module, source_set)
    ])
    if hidden:
        for finding in hidden:
            print(f"  {finding}")
        sys.exit(f"\n{len(hidden)} invisible character(s) in source.")

    print(f"  {len(scripts)} build scripts, 0 comments closed early, 0 invisible characters")

    compiler = Compiler(jars)
    check_build_logic_parses(compiler)

    stub_output = WORK / "classes" / "_stubs"
    base_classpath = library_classpath(jars)
    print()
    print("==> Compiling")
    compiler.compile("stubs", stub_output, base_classpath, sorted(HERE.joinpath("stubs").glob("*.kt")))

    main_outputs: dict[str, Path] = {}
    provided: dict[str, set[str]] = {}
    skipped_main: list[str] = []
    picked_main = total_main = 0

    for path in order:
        module = modules[path]
        visible = visible_from(modules, path, include_test=False)
        inherited: set[str] = set()
        for dependency in visible:
            inherited |= provided.get(dependency, set())
        candidates = [source for source in sources_in(module, "main") if selectable(source)]
        selected, names = close_selection(candidates, inherited)
        provided[path] = names
        total_main += len(sources_in(module, "main"))
        picked_main += len(selected)
        skipped_main += [
            str(source.relative_to(ROOT))
            for source in sources_in(module, "main")
            if source not in selected
        ]
        if not selected:
            continue
        output = WORK / "classes" / module.name / "main"
        module_base = test_classpath(jars) if module.ships_test_libraries else base_classpath
        classpath = module_base + [str(stub_output)] + [
            str(main_outputs[dependency]) for dependency in visible if dependency in main_outputs
        ]
        produced = compiler.compile(f"{module.name}:main", output, classpath, selected)
        main_outputs[path] = output
        print(f"  {path} main: {len(selected)} files, {produced} classes")

    test_outputs: list[tuple[str, Path, list[str]]] = []
    skipped_test: list[str] = []
    picked_test = total_test = 0
    base_test_classpath = test_classpath(jars)

    for path in order:
        module = modules[path]
        visible = visible_from(modules, path, include_test=True)
        inherited: set[str] = set(provided.get(path, set()))
        for dependency in visible:
            inherited |= provided.get(dependency, set())
        candidates = [source for source in sources_in(module, "test") if selectable(source)]
        selected, _ = close_selection(candidates, inherited)
        total_test += len(sources_in(module, "test"))
        picked_test += len(selected)
        skipped_test += [
            str(source.relative_to(ROOT))
            for source in sources_in(module, "test")
            if source not in selected
        ]
        if not selected:
            continue
        output = WORK / "classes" / module.name / "test"
        classpath = base_test_classpath + [str(stub_output)] + [
            str(main_outputs[dependency]) for dependency in visible if dependency in main_outputs
        ]
        if path in main_outputs:
            classpath.append(str(main_outputs[path]))
        produced = compiler.compile(f"{module.name}:test", output, classpath, selected)
        test_outputs.append((path, output, classpath))
        print(f"  {path} test: {len(selected)} files, {produced} classes")

    print()
    print(f"  main: {picked_main} of {total_main} files")
    print(f"  test: {picked_test} of {total_test} files")
    # Named, not just counted. A skipped *test* is a gate this run did not apply, and a silent
    # count reads as coverage — which is exactly how a contract test came to be verified only in
    # CI. Anything listed here is CI's job.
    for source in sorted(skipped_test):
        reason = HARD_EXCLUDES.get(source, "needs the Android toolchain")
        print(f"    not run here: {source} ({reason})")

    if not options.skip_tests:
        print()
        print("==> Tests")
        # One JUnit run per module, each with only that module's own test classpath — the same
        # isolation `testDebugUnitTest` gives each module. Running them together would put every
        # module's test classes on one classpath, and the contract tests in `:app` walk the
        # classpath: they would audit `FakeUserRepository` and `ResultTest` as if those were
        # application classes.
        failed: list[str] = []
        for path, output, classpath in test_outputs:
            print(f"  -- {path}")
            command = [
                "java", "-jar", str(jars / "junit-platform-console-standalone-1.11.4.jar"),
                "execute",
                "--class-path", os.pathsep.join([str(output)] + classpath),
                "--scan-class-path", str(output),
                "--details=summary",
                "--disable-banner",
                "--fail-if-no-tests",
            ]
            if subprocess.run(command).returncode != 0:
                failed.append(path)
        if failed:
            print(f"\n  tests failed in: {' '.join(failed)}")
            return 1

    if not options.skip_detekt:
        print()
        print("==> detekt (the same config and source sets the build gives the Gradle task)")
        inputs = []
        for module in modules.values():
            for source_set in ("main", "test", "androidTest"):
                directory = module.directory / "src" / source_set / "kotlin"
                if directory.exists():
                    inputs.append(str(directory))
        result = subprocess.run([
            "java", "-jar", str(jars / "detekt-cli-1.23.8-all.jar"),
            "--input", ",".join(inputs),
            "--config", str(ROOT / "config" / "detekt" / "detekt.yml"),
            "--build-upon-default-config",
            "--base-path", str(ROOT),
            "--jvm-target", "17",
        ])
        if result.returncode != 0:
            return 1
        print("  detekt: 0 findings")

    print()
    print("All harness gates passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
