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
    r"^(?P<flags>(?:[a-z]+ )*)"
    r"""(?:scheme\("[^"]*"\) )?"""
    r"fun (?:<[^>]*>\s*)?(?P<name>[^\s(]+)"
)

# A parameter line, indented under a declaration, e.g.
#   unstable state: HomeUiState
#   stable modifier: Modifier? = @static Companion
PARAMETER_RE = re.compile(
    r"^\s+(?P<stability>stable|unstable)?\s*(?P<name>\w+):\s*(?P<type>[^=]+?)\s*(?:=.*)?$"
)

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


def parse_composables(module: str, text: str) -> list[Composable]:
    composables: list[Composable] = []
    current: Composable | None = None
    parameters: list[Parameter] = []

    def flush() -> None:
        nonlocal current, parameters
        if current is not None:
            composables.append(
                Composable(current.module, current.name, current.flags, tuple(parameters))
            )
        current, parameters = None, []

    for line in text.splitlines():
        if not line.strip():
            continue
        if not line[0].isspace():
            flush()
            match = DECLARATION_RE.match(line)
            if match:
                flags = frozenset(match.group("flags").split())
                current = Composable(module, match.group("name"), flags, ())
            continue
        if current is None:
            continue
        match = PARAMETER_RE.match(line)
        if match:
            parameters.append(
                Parameter(
                    match.group("name"),
                    match.group("type").strip(),
                    match.group("stability") or "",
                )
            )

    flush()
    return composables


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
            report.composables += parse_composables(module, path.read_text(encoding="utf-8"))
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
    header = f"{'module':<28}{'composables':>12}{'skippable':>11}{'restartable':>13}{'unstable classes':>19}"
    lines = [header, "-" * len(header)]
    for report in sorted(reports, key=lambda r: r.module):
        metrics = report.metrics
        lines.append(
            f"{report.module:<28}"
            f"{metrics.get('totalComposables', 0):>12}"
            f"{metrics.get('skippableComposables', 0):>11}"
            f"{metrics.get('restartableComposables', 0):>13}"
            f"{metrics.get('inferredUnstableClasses', 0):>19}"
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
