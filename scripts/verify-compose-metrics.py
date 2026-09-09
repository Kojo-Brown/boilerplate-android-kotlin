#!/usr/bin/env python3
"""Gate on the Compose compiler's own stability verdict.

SPEC.md Phase 10, item 2. `StabilityContractTest` holds every type reachable from a view
model's `StateFlow` to the `@Immutable`/`@Stable` contract, by reflection over compiled
classes. This reads the other half of the question, which reflection cannot see: whether the
composables built out of those types actually **skip**.

The two are not the same claim. A screen can be made entirely of annotated state and still
recompose on every frame, because skipping is decided per composable over *all* of its
parameters — a `NavHostController`, a lambda that was not memoised, a `Modifier` chain built
in the body — and the compiler makes that decision silently. It records it in
`-composables.txt`, in a report nobody reads, and a screen that stops skipping produces no
build failure, no warning and no log line. It produces jank, months later, in a screen nobody
has touched.

## What is checked

1. **Every Compose module reported.** The expected set is read out of `settings.gradle.kts`
   and each module's `build.gradle.kts` — the modules applying a `*.compose` convention
   plugin — not from a list kept here, because a list omits the module added next month.
   A module that produced no report fails the run rather than being skipped over: the reports
   are written as a side effect of a Kotlin compile task that the build cache can restore
   without executing, so "no report" is the failure mode this check exists to name.
2. **Every restartable composable is skippable**, except the ones
   `config/compose-metrics/unskippable-allowlist.txt` names, each with a reason. A
   non-skippable composable recomposes whenever its parent does, however cheap its own body.
3. **The allowlist has no stale entries.** An entry that no longer matches a violation is a
   failure, not a no-op: an allowlist that is never pruned stops describing the code and
   starts hiding the next regression.
4. **The report was understood.** Anything in declaration position this script cannot parse
   fails the run, because the output of an audit like this is "nothing to report" and every
   way of reading less produces exactly that. See `assert_parser_understood_the_report`.

The composables this gates on are the **named** ones. `-composables.txt` prints top-level
composable functions; `-module.json` counts every composable including lambdas, which have no
name to print and no call site to fix — `:app` is 13 by the compiler's count and 2 by name.
The summary prints both so the gap is visible rather than surprising.

Usage:  scripts/verify-compose-metrics.py [--root DIR] [--allowlist FILE]

Generating the reports it reads:

    ./gradlew compileDebugKotlin -PcomposeCompilerReports=true --no-build-cache

`--no-build-cache` is not optional. The destinations are internal compiler-plugin options
rather than declared task outputs, so a compile task restored from the cache writes nothing.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

# Written by build-logic/convention/.../Conventions.kt. Spelled again rather than shared:
# nothing here loads Gradle, and a name only one side knows about shows up as "module reported
# nothing", which is a failure and not a silent pass.
REPORTS_DIR = "compose-reports"
METRICS_DIR = "compose-metrics"

DEFAULT_ALLOWLIST = Path("config/compose-metrics/unskippable-allowlist.txt")

# How much of a report to quote when the parser and the compiler disagree about it. Enough to
# show the shape of the format, not so much that a CI log becomes the file.
SAMPLE_LINES = 24
UNRECOGNISED_LINES = 12

COMPOSE_PLUGIN_IDS = (
    "boilerplate.android.application.compose",
    "boilerplate.android.library.compose",
)

INCLUDE_RE = re.compile(r"""^\s*include\(\s*["'](:[^"']+)["']\s*\)""", re.MULTILINE)

# A composable's declaration line, at column zero, e.g.
#   restartable skippable scheme("[androidx.compose.ui.UiComposable]") fun HomeScreen(
# The flags are a fixed vocabulary emitted in a fixed order; matching them as a set rather
# than in sequence keeps this working if the compiler adds one.
#
# The optional group before the name is a type-parameter list, and it has to be optional in
# both directions: a composable lambda is reported as `fun <anonymous>(`, which looks exactly
# like one. Dropping either would be a silent miss — the entry would parse as nothing and the
# composable would go unchecked while the run stayed green.
DECLARATION_RE = re.compile(
    r"""^(?P<prefix>(?:[a-z]+ |scheme\("[^"]*"\) )*)"""
    r"fun (?:<[^>]*>\s*)?(?P<name>[^\s(]+)"
)

# Pulled out of the prefix before it is split into flags. It cannot be split on whitespace
# with the rest: a scheme is a bracket expression that contains spaces of its own, as in
# `scheme("[0, [0]]")`.
SCHEME_RE = re.compile(r"""scheme\("[^"]*"\) """)

# A parameter line, indented under a declaration, e.g.
#   unstable state: HomeUiState
#   stable modifier: Modifier? = @static Companion
PARAMETER_RE = re.compile(
    r"^\s+(?P<stability>stable|unstable)?\s*(?P<name>\w+):\s*(?P<type>[^=]+?)\s*(?:=.*)?$"
)

# Quoted spans, removed before brackets are counted. A scheme is the common case —
# `scheme("[androidx.compose.ui.UiComposable]")` balances, but a string is not obliged to.
QUOTED_RE = re.compile(r"\"[^\"]*\"|'[^']*'")

# A class entry in -classes.txt, e.g.  `unstable class HomeUiState {`
CLASS_RE = re.compile(r"^(?P<stability>\w+) class (?P<name>[^\s<{]+)")

# A member line inside one, e.g.  `  unstable val users: List<User>`
MEMBER_RE = re.compile(r"^\s+(?P<stability>stable|unstable) (?P<decl>va[lr] .+)$")


@dataclass(frozen=True)
class Parameter:
    name: str
    type: str
    stability: str

    def __str__(self) -> str:
        return f"{self.stability or 'unknown'} {self.name}: {self.type}"


@dataclass(frozen=True)
class Composable:
    module: str
    name: str
    flags: frozenset[str]
    parameters: tuple[Parameter, ...]

    @property
    def is_unskippable(self) -> bool:
        """Restartable but not skippable: the shape that recomposes for nothing.

        `readonly` and `inline` composables are not restartable in the first place, so they
        are excluded by the `restartable` test itself rather than by naming them.
        """
        return "restartable" in self.flags and "skippable" not in self.flags

    @property
    def unstable_parameters(self) -> list[Parameter]:
        return [p for p in self.parameters if p.stability == "unstable"]


@dataclass
class ModuleReport:
    module: str
    composables: list[Composable] = field(default_factory=list)
    unstable_classes: dict[str, list[str]] = field(default_factory=dict)
    metrics: dict[str, int] = field(default_factory=dict)
    #: Declaration-position lines the parser did not recognise, and the head of the first
    #: report file. Both exist only to make a parse failure diagnosable from the CI log.
    unrecognised: list[str] = field(default_factory=list)
    sample: list[str] = field(default_factory=list)

    @property
    def expected_composables(self) -> int:
        return self.metrics.get("totalComposables", 0)

    @property
    def found_unskippable(self) -> int:
        return sum(1 for c in self.composables if c.is_unskippable)


@dataclass(frozen=True)
class AllowlistEntry:
    module: str
    name: str
    reason: str
    line_number: int


def module_directory(root: Path, module: str) -> Path:
    return root.joinpath(*module.strip(":").split(":"))


def compose_modules(root: Path) -> list[str]:
    """The modules that apply a Compose convention plugin, in declaration order.

    Read from the build rather than listed here, so a new UI module is covered the moment it
    is created and a module that drops Compose stops being expected without an edit.
    """
    settings = (root / "settings.gradle.kts").read_text(encoding="utf-8")
    modules = []
    for module in INCLUDE_RE.findall(settings):
        build_file = module_directory(root, module) / "build.gradle.kts"
        if not build_file.is_file():
            continue
        text = build_file.read_text(encoding="utf-8")
        if any(f'"{plugin_id}"' in text for plugin_id in COMPOSE_PLUGIN_IDS):
            modules.append(module)
    return modules


def bracket_depth(line: str) -> int:
    """The net change in bracket nesting this line makes, ignoring quoted text."""
    bare = QUOTED_RE.sub("", line)
    return (
        bare.count("(") - bare.count(")") + bare.count("{") - bare.count("}")
    )


def parse_composables(module: str, text: str) -> tuple[list[Composable], list[str]]:
    """Parse one `-composables.txt`, returning what was understood and what was not.

    ### Why this tracks brackets rather than indentation

    The obvious reading — a declaration is a line at column zero, a parameter is an indented
    one — is wrong, and wrong in the direction that loses composables without saying so. A
    parameter's default value is printed as the compiler's own lowered IR, which can run to
    twenty lines of `$composer` calls at arbitrary indentation, including at column zero:

        restartable skippable scheme("[…]") fun AppNavHost(
          unstable appEvents: Flow<AppEvent>
          unstable navController: NavHostController? = @dynamic rememberNavController(
          $composer   =   $composer  ,
          $changed   =   0
        )
          unstable startDestination: AppDestination? = @dynamic SignIn
        )

    That first `)` is at column zero and closes `rememberNavController`, not `AppNavHost`.
    Read by indentation it ends the composable early, and `startDestination` — an unstable
    parameter, exactly the kind this gate exists to find — is attributed to nothing and
    silently dropped. A `hiltViewModel(…, <block>{ … })` default does the same over a dozen
    lines and leaves `}, $composer, 0b01110000 and $dirty shl 0b0011, 0b0001)` sitting in
    declaration position.

    Counting brackets makes the structure say where the parameter list ends, which is what
    the compiler was expressing in the first place. Quoted spans come out first: a scheme
    like `scheme("[androidx.compose.ui.UiComposable]")` happens to balance, but nothing
    guarantees the next string will.

    Anything in declaration position that is not a declaration is collected rather than
    ignored, so `assert_parser_understood_the_report` can name it instead of the script
    quietly understanding less than it did yesterday.
    """
    composables: list[Composable] = []
    unrecognised: list[str] = []
    name: str | None = None
    flags: frozenset[str] = frozenset()
    parameters: list[Parameter] = []
    depth = 0

    def flush() -> None:
        nonlocal name, parameters
        if name is not None:
            composables.append(Composable(module, name, flags, tuple(parameters)))
        name, parameters = None, []

    for raw in text.splitlines():
        line = raw.rstrip()
        if not line.strip():
            continue

        if depth == 0:
            match = DECLARATION_RE.match(line)
            if match is None:
                # A stray closing bracket, or the `): Function1<E, Unit>` a composable with a
                # return type ends on — both are the tail of something already accounted for.
                if not line.lstrip().startswith((")", "}")):
                    unrecognised.append(line)
                continue
            flush()
            flags = frozenset(SCHEME_RE.sub("", match.group("prefix")).split())
            name = match.group("name")
            depth = max(0, bracket_depth(line))
            if depth == 0:
                # `fun EmptySignature()` — the whole declaration on one line.
                flush()
            continue

        # Inside the parameter list. Only its top level holds parameters; everything deeper
        # is the body of a default value.
        if depth == 1:
            match = PARAMETER_RE.match(line)
            if match:
                parameters.append(
                    Parameter(
                        match.group("name"),
                        match.group("type").strip(),
                        match.group("stability") or "",
                    )
                )
        depth += bracket_depth(line)
        if depth <= 0:
            depth = 0
            flush()

    flush()
    return composables, unrecognised


def parse_unstable_classes(text: str) -> dict[str, list[str]]:
    """Unstable classes and the members that made them so, for diagnosis only.

    Nothing is gated on this. Most of a module's classes are not Compose parameters at all —
    every view model in the app is reported unstable and correctly so — which is why the gate
    below is on composables, where the compiler has already decided the question that matters.
    """
    unstable: dict[str, list[str]] = {}
    current: str | None = None
    for line in text.splitlines():
        if not line.strip():
            continue
        if not line[0].isspace():
            match = CLASS_RE.match(line)
            current = (
                match.group("name") if match and match.group("stability") == "unstable" else None
            )
            if current is not None:
                unstable.setdefault(current, [])
            continue
        if current is None:
            continue
        match = MEMBER_RE.match(line)
        if match and match.group("stability") == "unstable":
            unstable[current].append(match.group("decl"))
    return unstable


def read_reports(root: Path, modules: list[str]) -> tuple[list[ModuleReport], list[str]]:
    reports: list[ModuleReport] = []
    missing: list[str] = []

    for module in modules:
        build_dir = module_directory(root, module) / "build"
        composable_files = sorted((build_dir / REPORTS_DIR).glob("*-composables.txt"))
        class_files = sorted((build_dir / REPORTS_DIR).glob("*-classes.txt"))
        metric_files = sorted((build_dir / METRICS_DIR).glob("*-module.json"))

        if not composable_files or not metric_files:
            missing.append(module)
            continue

        report = ModuleReport(module)
        for path in composable_files:
            text = path.read_text(encoding="utf-8")
            if not report.sample:
                report.sample = text.splitlines()[:SAMPLE_LINES]
            parsed, unrecognised = parse_composables(module, text)
            report.composables += parsed
            report.unrecognised += unrecognised
        for path in class_files:
            report.unstable_classes.update(parse_unstable_classes(path.read_text(encoding="utf-8")))
        for path in metric_files:
            for key, value in json.loads(path.read_text(encoding="utf-8")).items():
                if isinstance(value, int):
                    report.metrics[key] = report.metrics.get(key, 0) + value
        reports.append(report)

    return reports, missing


def read_allowlist(path: Path) -> tuple[list[AllowlistEntry], list[str]]:
    """Parse the allowlist, and reject an entry that does not say why it is there.

    A bare name is how an allowlist becomes permanent: nobody can tell later whether the
    exemption was a considered trade-off or the quickest way past a red build.
    """
    entries: list[AllowlistEntry] = []
    errors: list[str] = []
    if not path.is_file():
        return entries, errors

    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        line = raw.split("#", 1)
        body, reason = line[0].strip(), (line[1].strip() if len(line) > 1 else "")
        if not body:
            continue
        fields = body.split()
        if len(fields) != 2 or not fields[0].startswith(":"):
            errors.append(f"{path}:{number}: expected `:module ComposableName  # reason`, got {raw!r}")
            continue
        if not reason:
            errors.append(f"{path}:{number}: {fields[0]} {fields[1]} has no `# reason`")
            continue
        entries.append(AllowlistEntry(fields[0], fields[1], reason, number))
    return entries, errors


def assert_parser_understood_the_report(reports: list[ModuleReport]) -> list[str]:
    """Check the reader against the report, so a parse failure cannot pass as a clean audit.

    This is the check on the check, and it is here because the version without it shipped
    green while dropping parameters — the gate's whole output is "nothing to report", so
    every way of reading less produces exactly the answer that means everything is fine.

    Three things are asserted, and one deliberately is not.

    - **Nothing in declaration position went unread.** This is the one that catches format
      drift, and it is the one that caught the multi-line default values described in
      `parse_composables`.
    - **Every module named at least one composable.** A report this script understands
      nothing of would otherwise be indistinguishable from a module with no UI in it.
    - **No module named more composables than the compiler counted.** The named ones are a
      subset of all of them; reading more than exist means entries are being invented, most
      likely by matching something that is not a declaration.
    - Not asserted: **equality with `totalComposables`.** They count different things.
      `-composables.txt` names top-level composable functions; `-module.json` counts every
      composable including lambdas, which have no name to print — `:app` has 13 by the
      compiler's count and 2 with names. An equality here reads as a much stronger check than
      it is and fails permanently on a correct parser, which is why it is written down as a
      non-check rather than left out.
    """
    failures = []
    for report in sorted(reports, key=lambda r: r.module):
        found, counted = len(report.composables), report.expected_composables
        problem = None
        if report.unrecognised:
            problem = f"{len(report.unrecognised)} line(s) in declaration position did not parse"
        elif found == 0:
            problem = "no composable was read out of the report at all"
        elif found > counted:
            problem = f"read {found} named composable(s), more than the {counted} the compiler counted"
        if problem is None:
            continue

        detail = [f"  {report.module}: {problem}."]
        if report.unrecognised:
            detail += [f"        {line}" for line in report.unrecognised[:UNRECOGNISED_LINES]]
            if len(report.unrecognised) > UNRECOGNISED_LINES:
                detail.append(
                    f"        ... and {len(report.unrecognised) - UNRECOGNISED_LINES} more"
                )
        detail.append("      the head of the report this was read from:")
        detail += [f"        {line}" for line in report.sample]
        failures += detail

    if not failures:
        return []
    return [
        "This script did not understand the report it is gating on. Whatever it could not\n"
        "  read is a composable with no violation to report, so this fails rather than\n"
        "  passing — fix the parser in scripts/verify-compose-metrics.py:\n"
        + "\n".join(failures)
    ]


def describe(composable: Composable, report: ModuleReport) -> list[str]:
    lines = [f"  {composable.module} {composable.name}"]
    unstable = composable.unstable_parameters
    if not unstable:
        lines.append(
            "      no unstable parameter — the body is what stops it skipping "
            "(an unstable default, or a non-Unit return)"
        )
    for parameter in unstable:
        lines.append(f"      unstable parameter: {parameter}")
        bare = parameter.type.rstrip("?").split("<", 1)[0]
        for member in report.unstable_classes.get(bare, []):
            lines.append(f"          {bare}.{member}")
    return lines


def summarise(reports: list[ModuleReport]) -> list[str]:
    """The compiler's counts beside this script's, per module.

    `read` and `not skipping` are what the parser made of `-composables.txt`; the columns to
    their left are what the compiler said in `-module.json`. Printing both on a green run is
    deliberate: the numbers that would have exposed the parse bug were available in the very
    first CI log, and only the compiler's half was printed.
    """
    header = (
        f"{'module':<28}{'composables':>12}{'restartable':>12}{'skippable':>10}"
        f"{'unstable':>9}{'read':>7}{'not skipping':>14}"
    )
    lines = [header, "-" * len(header)]
    for report in sorted(reports, key=lambda r: r.module):
        metrics = report.metrics
        lines.append(
            f"{report.module:<28}"
            f"{metrics.get('totalComposables', 0):>12}"
            f"{metrics.get('restartableComposables', 0):>12}"
            f"{metrics.get('skippableComposables', 0):>10}"
            f"{metrics.get('inferredUnstableClasses', 0):>9}"
            f"{len(report.composables):>7}"
            f"{report.found_unskippable:>14}"
        )
    return lines


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path.cwd(), help="repository root")
    parser.add_argument("--allowlist", type=Path, default=None, help="override the allowlist path")
    args = parser.parse_args(argv)

    root: Path = args.root
    allowlist_path: Path = args.allowlist or (root / DEFAULT_ALLOWLIST)

    modules = compose_modules(root)
    if not modules:
        print(
            "::error::No module applies a Compose convention plugin. Either settings.gradle.kts "
            "was not read (wrong --root?) or the plugin ids in this script are stale.",
            file=sys.stderr,
        )
        return 1

    reports, missing = read_reports(root, modules)
    entries, failures = read_allowlist(allowlist_path)

    if missing:
        failures.append(
            "No Compose compiler report under build/"
            + REPORTS_DIR
            + " for: "
            + ", ".join(missing)
            + "\n  The reports are a side effect of the Kotlin compile task and are not declared\n"
            "  outputs of it, so a task restored from the build cache produces none. Generate\n"
            "  them with:\n"
            "    ./gradlew compileDebugKotlin -PcomposeCompilerReports=true --no-build-cache"
        )

    failures += assert_parser_understood_the_report(reports)

    unskippable = [c for report in reports for c in report.composables if c.is_unskippable]
    allowed = {(e.module, e.name): e for e in entries}
    by_module = {report.module: report for report in reports}

    unlisted = [c for c in unskippable if (c.module, c.name) not in allowed]
    if unlisted:
        detail = []
        for composable in sorted(unlisted, key=lambda c: (c.module, c.name)):
            detail += describe(composable, by_module[composable.module])
        failures.append(
            f"{len(unlisted)} restartable composable(s) cannot skip. Each one recomposes every\n"
            "  time its caller does, whatever its arguments. Fix the parameter the compiler\n"
            f"  names, or add it to {allowlist_path} with the reason it has to stay:\n"
            + "\n".join(detail)
        )

    found = {(c.module, c.name) for c in unskippable}
    stale = [e for e in entries if (e.module, e.name) not in found]
    if stale:
        failures.append(
            f"{len(stale)} allowlist entry/entries no longer match anything. The composable now\n"
            "  skips, or was renamed or deleted — remove the line:\n"
            + "\n".join(
                f"  {allowlist_path}:{e.line_number}: {e.module} {e.name}"
                for e in sorted(stale, key=lambda e: e.line_number)
            )
        )

    print("\n".join(summarise(reports)))
    print(
        f"\n{len(modules)} Compose module(s), "
        f"{sum(len(r.composables) for r in reports)} composable(s), "
        f"{len(unskippable)} not skippable, {len(entries)} allowed."
    )

    if failures:
        print("", file=sys.stderr)
        for failure in failures:
            print(f"::error::{failure}", file=sys.stderr)
        return 1

    print("Compose compiler metrics: every restartable composable skips or is accounted for.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
