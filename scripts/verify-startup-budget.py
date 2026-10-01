#!/usr/bin/env python3
"""Gate on the measured cold-startup time and on the baseline profile that produced it.

SPEC.md Phase 11, item 6. Two artifacts come out of a macrobenchmark run and neither can fail on
its own:

* `benchmarkData.json` is a number. Macrobenchmark prints it, Gradle exits 0 whatever it says, and
  a startup time that has doubled looks exactly like one that has not.
* `app/src/main/baseline-prof.txt` is a list of rules. AGP silently drops any rule that no longer
  matches a class in the APK, so a profile that has gone stale — a renamed package, a screen that
  moved, a dependency that reorganised its initialisation — shrinks to a file full of rules about
  nothing and the build stays green. This is the same failure mode as an R8 keep rule that matches
  nothing, which is what `verify-r8-mapping.py` exists for, and it is why both are checked by their
  effect rather than by their contents.

So this reads both and holds each to `config/benchmark/startup-budget.json`.

## What is checked

1. **The budget file parses and is complete.** An unknown key fails, and so does a case named in
   the config that the run did not measure. A gate configured for a benchmark that no longer exists
   is a gate that reports success about nothing.
2. **The run happened on the hardware the budget was calibrated against.** The API level is gated
   because it changes what ART does with a profile; the device model is printed and not gated,
   because one API level has several model strings and none of them changes the measurement.
3. **Each case's median is inside its budget.** `withoutCompilation` is the uncompiled floor and
   `withBaselineProfile` is what ships. Both are held separately on purpose: when the app's own
   startup regresses they move together, and when only the second moves the profile has stopped
   covering the startup path. A single number cannot tell those apart.
4. **The measurement setup has not drifted.** Iteration count at or above the configured minimum,
   and warmup iterations at or below the configured maximum. `CompilationMode.Partial`'s default is
   to let ART's JIT warm up first, which produces a better number that says nothing about the
   checked-in file; a warmup that appeared here would make every budget below unattributable
   without failing anything.
5. **The committed profile is a well-formed, non-trivial ART profile.** Every line has to parse as
   a human-readable-format rule — anything this script cannot classify fails the run, because a
   parser that skips what it does not recognise reports "nothing wrong" about a file it did not
   read. Plus a floor on the total rule count and on the count under each configured package, which
   is what a profile truncated to a handful of rules fails.
6. **Every class the profile must name is named.** The required set is deliberately small and
   consists of classes whose presence is a fact about the app rather than about Compose's internals:
   the `Application` and the launched `Activity` run on every cold start by definition.
7. **The committed profile is not stale with respect to a profile generated in the same run.** When
   `BaselineProfileGenerator` has written one into the benchmark module's outputs, every required
   class and every configured package present in the *generated* profile must also be present in the
   *committed* one. This is the direction that matters: the generated profile is what the app
   actually does today, and a committed file that no longer names what the app runs is the stale
   case this gate exists for.

## What it cannot check

Whether the committed profile is *byte-identical* to a freshly generated one, and that is on
purpose rather than a gap. Two generation runs of the same commit do not produce the same file:
rule order, and which methods ART saw often enough to mark hot, both vary with timing. A gate on
equality would fail on noise, so check 7 is a containment check over the sets the config names.

Absolute performance. The numbers are from a CI emulator with a software rasteriser, and a budget
calibrated there says nothing about a phone. Both facts are in the budget file beside the values.

Usage:  scripts/verify-startup-budget.py [--root DIR] [--budget FILE] [--outputs-dir DIR]
                                         [--profile FILE]

Producing what it reads:

    ./gradlew :benchmark:connectedNonMinifiedBenchmarkAndroidTest \\
        -Pandroid.testInstrumentationRunnerArguments.class=\\
            com.kojo.boilerplate.benchmark.BaselineProfileGenerator
    ./gradlew :benchmark:connectedBenchmarkAndroidTest \\
        -Pandroid.testInstrumentationRunnerArguments.class=\\
            com.kojo.boilerplate.benchmark.StartupBenchmark

Both need a device. ci.yml boots an emulator for them; docs/baseline-profiles.md has the local
equivalent and how to recalibrate the budget afterwards.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable

# The benchmark module's name, used to locate its build outputs from the repository root.
BENCHMARK_MODULE = "benchmark"

# Macrobenchmark names its JSON after the instrumented package; the suffix is the stable half.
BENCHMARK_DATA_SUFFIX = "-benchmarkData.json"

# BaselineProfileRule names its output after the test method, with this suffix.
GENERATED_PROFILE_SUFFIX = "-baseline-prof.txt"

# Only this class's results are budgeted. `BaselineProfileGenerator` runs in the same module and
# also writes a benchmarkData.json; filtering by class is what keeps the two runs apart without
# depending on which directory AGP put each one in.
BENCHMARK_CLASS_SUFFIX = "StartupBenchmark"

# The keys `startup-budget.json` may carry, so that a typo is a failure rather than a setting that
# silently does nothing.
BUDGET_KEYS = {"_comment", "metric", "statistic", "device", "cases", "profile"}
DEVICE_KEYS = {"_comment", "sdk"}
CASE_KEYS = {"budgetMs", "minimumIterations", "maximumWarmupIterations"}
PROFILE_KEYS = {
    "path",
    "minimumRules",
    "requiredClasses",
    "minimumRulesByPackage",
}

# A human-readable-format ART profile rule.
#
# The flags are the leading letters: `H` hot, `S` startup, `P` post-startup. `L` is not a flag — it
# begins the type descriptor, which is why the flag class excludes it and the descriptor group
# requires it. Wildcards (`*`, `**`, `?`) are part of the superset AGP accepts and appear inside the
# descriptor and the member signature alike, so neither group constrains them beyond the delimiters.
PROFILE_RULE = re.compile(
    r"^(?P<flags>[HSP]*)(?P<descriptor>L[^;]*;)(?:->(?P<member>\S.*))?$"
)


class VerificationError(Exception):
    """A failure to even read what is being checked, as opposed to a check that failed."""


@dataclass(frozen=True)
class CaseBudget:
    """One benchmark method's limits, straight out of the config."""

    budget_ms: float
    minimum_iterations: int
    maximum_warmup_iterations: int


@dataclass(frozen=True)
class ProfileBudget:
    """What the committed profile has to look like."""

    path: str
    minimum_rules: int
    required_classes: tuple[str, ...]
    minimum_rules_by_package: dict[str, int]


@dataclass(frozen=True)
class Budget:
    """`config/benchmark/startup-budget.json`, parsed."""

    metric: str
    statistic: str
    sdk: int
    cases: dict[str, CaseBudget]
    profile: ProfileBudget


@dataclass(frozen=True)
class Measurement:
    """One benchmark method's result, out of `benchmarkData.json`."""

    name: str
    class_name: str
    value: float
    repeat_iterations: int
    warmup_iterations: int
    source: Path


@dataclass
class Profile:
    """A parsed ART profile in human-readable format."""

    path: Path
    rules: list[str] = field(default_factory=list)
    descriptors: list[str] = field(default_factory=list)

    @property
    def classes(self) -> set[str]:
        return set(self.descriptors)

    def rules_under(self, package: str) -> int:
        return sum(1 for descriptor in self.descriptors if descriptor.startswith(package))


def parse_budget(path: Path) -> Budget:
    """Reads the budget file, rejecting anything it does not recognise."""
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as error:
        raise VerificationError(f"{path} does not exist") from error
    except json.JSONDecodeError as error:
        raise VerificationError(f"{path} is not valid JSON: {error}") from error

    if not isinstance(raw, dict):
        raise VerificationError(f"{path} must contain a JSON object")

    reject_unknown_keys(raw, BUDGET_KEYS, f"{path}")

    for required in ("metric", "statistic", "device", "cases", "profile"):
        if required not in raw:
            raise VerificationError(f"{path} is missing the required key '{required}'")

    device = raw["device"]
    if not isinstance(device, dict):
        raise VerificationError(f"{path}: 'device' must be an object")
    reject_unknown_keys(device, DEVICE_KEYS, f"{path}: device")
    if "sdk" not in device:
        raise VerificationError(f"{path}: device is missing 'sdk'")

    cases_raw = raw["cases"]
    if not isinstance(cases_raw, dict) or not cases_raw:
        raise VerificationError(f"{path}: 'cases' must be a non-empty object")

    cases: dict[str, CaseBudget] = {}
    for name, case in cases_raw.items():
        if not isinstance(case, dict):
            raise VerificationError(f"{path}: case '{name}' must be an object")
        reject_unknown_keys(case, CASE_KEYS, f"{path}: case '{name}'")
        missing = sorted(CASE_KEYS - case.keys())
        if missing:
            raise VerificationError(
                f"{path}: case '{name}' is missing {', '.join(missing)}"
            )
        cases[name] = CaseBudget(
            budget_ms=float(case["budgetMs"]),
            minimum_iterations=int(case["minimumIterations"]),
            maximum_warmup_iterations=int(case["maximumWarmupIterations"]),
        )

    profile_raw = raw["profile"]
    if not isinstance(profile_raw, dict):
        raise VerificationError(f"{path}: 'profile' must be an object")
    reject_unknown_keys(profile_raw, PROFILE_KEYS, f"{path}: profile")
    missing = sorted(PROFILE_KEYS - profile_raw.keys())
    if missing:
        raise VerificationError(f"{path}: profile is missing {', '.join(missing)}")

    by_package = profile_raw["minimumRulesByPackage"]
    if not isinstance(by_package, dict):
        raise VerificationError(f"{path}: profile.minimumRulesByPackage must be an object")

    return Budget(
        metric=str(raw["metric"]),
        statistic=str(raw["statistic"]),
        sdk=int(device["sdk"]),
        cases=cases,
        profile=ProfileBudget(
            path=str(profile_raw["path"]),
            minimum_rules=int(profile_raw["minimumRules"]),
            required_classes=tuple(profile_raw["requiredClasses"]),
            minimum_rules_by_package={
                str(key): int(value) for key, value in by_package.items()
            },
        ),
    )


def reject_unknown_keys(mapping: dict[str, Any], allowed: Iterable[str], where: str) -> None:
    """Fails on a key nobody reads, which is how a typo becomes a setting that does nothing."""
    unknown = sorted(set(mapping) - set(allowed))
    if unknown:
        raise VerificationError(
            f"{where} has unknown key(s) {', '.join(unknown)}. "
            f"Known keys: {', '.join(sorted(allowed))}"
        )


def find_files(root: Path, suffix: str) -> list[Path]:
    """Every file under `root` whose name ends with `suffix`, sorted for a stable report.

    Searched by name rather than by path because the directory AGP writes additional test output
    into differs between a connected device, a Gradle-managed device and a variant — and a verifier
    that hard-codes one of those layouts reports "no results" after an AGP upgrade, which is
    indistinguishable from a run that measured nothing.
    """
    if not root.is_dir():
        return []
    return sorted(path for path in root.rglob(f"*{suffix}") if path.is_file())


def parse_measurements(paths: list[Path], budget: Budget) -> tuple[list[Measurement], dict[str, Any]]:
    """Pulls the budgeted metric out of every `benchmarkData.json` the run produced."""
    measurements: list[Measurement] = []
    context: dict[str, Any] = {}

    for path in paths:
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as error:
            raise VerificationError(f"{path} is not valid JSON: {error}") from error

        benchmarks = data.get("benchmarks")
        if benchmarks is None:
            raise VerificationError(f"{path} has no 'benchmarks' key")

        relevant = [
            entry
            for entry in benchmarks
            if str(entry.get("className", "")).endswith(BENCHMARK_CLASS_SUFFIX)
        ]
        if not relevant:
            continue

        if not context:
            context = data.get("context") or {}

        for entry in relevant:
            name = str(entry.get("name", ""))
            metrics = entry.get("metrics") or {}
            metric = metrics.get(budget.metric)
            if metric is None:
                raise VerificationError(
                    f"{path}: benchmark '{name}' reported no '{budget.metric}'. "
                    f"It reported: {', '.join(sorted(metrics)) or 'nothing'}"
                )
            if budget.statistic not in metric:
                raise VerificationError(
                    f"{path}: '{budget.metric}' of '{name}' has no "
                    f"'{budget.statistic}'. It has: {', '.join(sorted(metric))}"
                )
            measurements.append(
                Measurement(
                    name=name,
                    class_name=str(entry.get("className", "")),
                    value=float(metric[budget.statistic]),
                    repeat_iterations=int(entry.get("repeatIterations", 0)),
                    warmup_iterations=int(entry.get("warmupIterations", 0)),
                    source=path,
                )
            )

    return measurements, context


def parse_profile(path: Path) -> Profile:
    """Parses an ART profile in human-readable format, failing on any line it cannot classify."""
    try:
        text = path.read_text(encoding="utf-8")
    except FileNotFoundError as error:
        raise VerificationError(
            f"{path} does not exist. It is generated by BaselineProfileGenerator and checked in; "
            f"see docs/baseline-profiles.md"
        ) from error

    profile = Profile(path=path)
    for number, raw_line in enumerate(text.splitlines(), start=1):
        line = raw_line.strip()
        if not line:
            continue
        match = PROFILE_RULE.match(line)
        if match is None:
            raise VerificationError(
                f"{path}:{number} is not a profile rule: {raw_line!r}. "
                f"A rule is optional HSP flags, a type descriptor such as Lcom/example/Foo; and "
                f"optionally ->member. The format has no comment syntax."
            )
        profile.rules.append(line)
        profile.descriptors.append(match.group("descriptor"))

    return profile


def check_device(context: dict[str, Any], budget: Budget) -> list[str]:
    """Holds the run to the API level the budget was calibrated on, and reports the rest."""
    build = (context or {}).get("build") or {}
    version = build.get("version") or {}
    sdk = version.get("sdk")

    if sdk is None:
        return [
            "benchmarkData.json carried no build.version.sdk, so there is no way to tell "
            "whether this run is comparable to the budget"
        ]
    if int(sdk) != budget.sdk:
        return [
            f"measured on API {sdk}, but the budget is calibrated for API {budget.sdk}. "
            f"Either run on the pinned emulator or recalibrate — see docs/baseline-profiles.md"
        ]
    return []


def check_measurements(
    measurements: list[Measurement], budget: Budget
) -> tuple[list[str], list[str]]:
    """Each case against its own budget, iteration counts included."""
    violations: list[str] = []
    report: list[str] = []

    by_name: dict[str, list[Measurement]] = {}
    for measurement in measurements:
        by_name.setdefault(measurement.name, []).append(measurement)

    for name in sorted(by_name):
        if name not in budget.cases:
            violations.append(
                f"'{name}' was measured but has no entry in the budget. Add one, or remove the "
                f"benchmark — an unbudgeted case is a measurement nothing gates on"
            )

    for name, case in budget.cases.items():
        found = by_name.get(name, [])
        if not found:
            violations.append(
                f"the budget names '{name}' but the run did not measure it. "
                f"Measured: {', '.join(sorted(by_name)) or 'nothing'}"
            )
            continue
        if len(found) > 1:
            sources = ", ".join(str(item.source) for item in found)
            violations.append(
                f"'{name}' was measured {len(found)} times ({sources}). Two runs of the same "
                f"benchmark cannot both be the one the budget applies to"
            )
            continue

        measurement = found[0]
        headroom = case.budget_ms - measurement.value
        report.append(
            f"  {name}: {budget.statistic} {budget.metric} "
            f"{measurement.value:.1f} ms against a budget of {case.budget_ms:.0f} ms "
            f"({headroom:+.1f} ms, {measurement.repeat_iterations} iterations, "
            f"{measurement.warmup_iterations} warmup)"
        )

        if measurement.value > case.budget_ms:
            violations.append(
                f"'{name}' took {measurement.value:.1f} ms, over its {case.budget_ms:.0f} ms "
                f"budget by {-headroom:.1f} ms"
            )
        if measurement.repeat_iterations < case.minimum_iterations:
            violations.append(
                f"'{name}' ran {measurement.repeat_iterations} iterations, below the "
                f"{case.minimum_iterations} the budget assumes. A median over fewer is noise, "
                f"not a measurement"
            )
        if measurement.warmup_iterations > case.maximum_warmup_iterations:
            violations.append(
                f"'{name}' ran {measurement.warmup_iterations} warmup iterations, above the "
                f"{case.maximum_warmup_iterations} allowed. Warmup lets ART's own JIT profile "
                f"compile the app, so the number stops being attributable to the baseline profile"
            )

    return violations, report


def check_profile(profile: Profile, budget: ProfileBudget) -> tuple[list[str], list[str]]:
    """The committed profile's shape: rule count, per-package floors, required classes."""
    violations: list[str] = []
    report = [f"  {profile.path}: {len(profile.rules)} rules, {len(profile.classes)} classes"]

    if len(profile.rules) < budget.minimum_rules:
        violations.append(
            f"{profile.path} has {len(profile.rules)} rules, below the "
            f"{budget.minimum_rules} the budget requires. A profile this small does not describe "
            f"a Compose startup path; regenerate it"
        )

    for package in sorted(budget.minimum_rules_by_package):
        minimum = budget.minimum_rules_by_package[package]
        count = profile.rules_under(package)
        report.append(f"    {package}: {count} rules (minimum {minimum})")
        if count < minimum:
            violations.append(
                f"{profile.path} has {count} rule(s) under {package}, below the required "
                f"{minimum}. Either startup no longer goes through that package or the profile "
                f"is stale"
            )

    for descriptor in budget.required_classes:
        if descriptor not in profile.classes:
            violations.append(
                f"{profile.path} does not name {descriptor}. It runs on every cold start, so a "
                f"profile without it was not recorded against this app's startup"
            )

    return violations, report


def check_not_stale(
    committed: Profile, generated: Profile | None, budget: ProfileBudget
) -> tuple[list[str], list[str]]:
    """The committed profile against one generated in the same run, over the sets the config names.

    Containment and not equality, in one direction only. Two generation runs of the same commit
    differ in rule order and in which methods ART happened to see often enough to mark hot, so
    equality would fail on noise. What is not noise is a class the app demonstrably runs at startup
    today that the committed file does not mention.
    """
    if generated is None:
        return [], [
            "  no freshly generated profile in the run's outputs, so the staleness check did not "
            "run. It is the generator's output that makes it possible — see "
            "docs/baseline-profiles.md"
        ]

    violations: list[str] = []
    report = [
        f"  {generated.path}: {len(generated.rules)} rules, {len(generated.classes)} classes "
        f"(freshly generated)"
    ]

    for descriptor in budget.required_classes:
        if descriptor in generated.classes and descriptor not in committed.classes:
            violations.append(
                f"{descriptor} is in the freshly generated profile and not in {committed.path}. "
                f"The committed profile is stale: commit the generated one"
            )

    for package in sorted(budget.minimum_rules_by_package):
        generated_count = generated.rules_under(package)
        committed_count = committed.rules_under(package)
        report.append(
            f"    {package}: {committed_count} committed vs {generated_count} generated"
        )
        if generated_count > 0 and committed_count == 0:
            violations.append(
                f"the freshly generated profile has {generated_count} rule(s) under {package} and "
                f"{committed.path} has none. The committed profile is stale: commit the generated "
                f"one"
            )

    return violations, report


def describe_context(context: dict[str, Any]) -> list[str]:
    """What the run was measured on, printed so an unexpected machine is visible in the log."""
    build = (context or {}).get("build") or {}
    version = build.get("version") or {}
    return [
        f"  device: {build.get('brand', '?')}/{build.get('model', '?')} "
        f"({build.get('device', '?')}), API {version.get('sdk', '?')}",
        f"  cpuCoreCount: {context.get('cpuCoreCount', '?')}, "
        f"cpuLocked: {context.get('cpuLocked', '?')}, "
        f"sustainedPerformanceModeEnabled: "
        f"{context.get('sustainedPerformanceModeEnabled', '?')}",
    ]


def verify(
    root: Path,
    budget_path: Path,
    outputs_dir: Path,
    profile_path: Path | None,
) -> int:
    """Runs every check and prints one report. Returns a process exit code."""
    budget = parse_budget(budget_path)

    data_files = find_files(outputs_dir, BENCHMARK_DATA_SUFFIX)
    if not data_files:
        raise VerificationError(
            f"no *{BENCHMARK_DATA_SUFFIX} under {outputs_dir}. The benchmark did not run, or it "
            f"ran and Gradle did not pull its output back from the device"
        )

    measurements, context = parse_measurements(data_files, budget)
    if not measurements:
        raise VerificationError(
            f"{len(data_files)} benchmark data file(s) under {outputs_dir} and none of them "
            f"contained a result from a class ending in {BENCHMARK_CLASS_SUFFIX}"
        )

    committed = parse_profile(
        profile_path if profile_path is not None else root / budget.profile.path
    )

    generated_files = find_files(outputs_dir, GENERATED_PROFILE_SUFFIX)
    generated = parse_profile(generated_files[0]) if generated_files else None

    violations: list[str] = []
    lines: list[str] = ["Measured on:", *describe_context(context)]

    violations += check_device(context, budget)

    measurement_violations, measurement_report = check_measurements(measurements, budget)
    violations += measurement_violations
    lines += ["Startup:", *measurement_report]

    profile_violations, profile_report = check_profile(committed, budget.profile)
    violations += profile_violations
    lines += ["Baseline profile:", *profile_report]

    stale_violations, stale_report = check_not_stale(committed, generated, budget.profile)
    violations += stale_violations
    lines += stale_report

    print("\n".join(lines))

    if violations:
        print(f"\n{len(violations)} problem(s):", file=sys.stderr)
        for violation in violations:
            print(f"  - {violation}", file=sys.stderr)
        return 1

    print(
        f"\nOK: {len(budget.cases)} case(s) inside budget, "
        f"{len(committed.rules)} profile rules checked."
    )
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--root",
        type=Path,
        default=Path(__file__).resolve().parent.parent,
        help="repository root (default: the parent of this script's directory)",
    )
    parser.add_argument(
        "--budget",
        type=Path,
        default=None,
        help="budget file (default: config/benchmark/startup-budget.json under --root)",
    )
    parser.add_argument(
        "--outputs-dir",
        type=Path,
        default=None,
        help=f"where to search for benchmark output (default: {BENCHMARK_MODULE}/build/outputs)",
    )
    parser.add_argument(
        "--profile",
        type=Path,
        default=None,
        help="committed baseline profile (default: the path named in the budget file)",
    )
    args = parser.parse_args(argv)

    root: Path = args.root
    budget_path: Path = args.budget or root / "config" / "benchmark" / "startup-budget.json"
    outputs_dir: Path = args.outputs_dir or root / BENCHMARK_MODULE / "build" / "outputs"

    try:
        return verify(root, budget_path, outputs_dir, args.profile)
    except VerificationError as error:
        print(f"error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
