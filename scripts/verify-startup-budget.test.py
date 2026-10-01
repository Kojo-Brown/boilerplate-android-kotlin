#!/usr/bin/env python3
"""Tests for scripts/verify-startup-budget.py.

The happy path runs on every CI build that boots an emulator. The failure paths — the only reason
that script exists — run nowhere else at all: a green startup gate says nothing about whether a
doubled startup time, a profile that stopped covering the app, or a warmup that quietly made the
numbers unattributable would actually be caught, and "caught" is the whole claim.

Each case writes a fixture repository into a temp directory — a budget file, a fabricated
`benchmarkData.json` in the directory layout AGP really uses, a committed profile and optionally a
freshly generated one — runs the real script over it, and checks that it fails when it should and
only when it should.

It needs nothing but Python: no Gradle, no Android SDK, no emulator, no network. That is deliberate
and it is the only reason any part of this item is checkable where it was written. The scheduled
agent's environment answers 403 on CONNECT to dl.google.com, so neither AGP nor the Android SDK can
be fetched there and not one Gradle gate can run; a macrobenchmark additionally needs hardware that
no CI container has. The Python half of the gate is therefore held to its own tests here, and the
Gradle and device halves are held to CI.

Usage: scripts/verify-startup-budget.test.py
"""

from __future__ import annotations

import json
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any

HERE = Path(__file__).resolve().parent
VERIFY = HERE / "verify-startup-budget.py"

BUDGET_PATH = Path("config/benchmark/startup-budget.json")
PROFILE_PATH = Path("app/src/main/baseline-prof.txt")

# The layout AGP writes additional test output into for a connected device, reproduced so that the
# script's search-by-name is exercised against a realistic depth rather than a flat directory.
OUTPUT_DIR = Path(
    "benchmark/build/outputs/connected_android_test_additional_output/benchmark/connected/"
    "Pixel_6_API_34(AVD) - 14"
)
DATA_NAME = "com.kojo.boilerplate-benchmarkData.json"
GENERATED_NAME = "BaselineProfileGenerator_startup-baseline-prof.txt"

GOOD_BUDGET: dict[str, Any] = {
    "metric": "timeToInitialDisplayMs",
    "statistic": "median",
    "device": {"sdk": 34},
    "cases": {
        "withoutCompilation": {
            "budgetMs": 2600,
            "minimumIterations": 10,
            "maximumWarmupIterations": 0,
        },
        "withBaselineProfile": {
            "budgetMs": 2100,
            "minimumIterations": 10,
            "maximumWarmupIterations": 0,
        },
    },
    "profile": {
        "path": str(PROFILE_PATH),
        "minimumRules": 200,
        "requiredClasses": [
            "Lcom/kojo/boilerplate/BoilerplateApp;",
            "Lcom/kojo/boilerplate/MainActivity;",
        ],
        "minimumRulesByPackage": {
            "Lcom/kojo/boilerplate/": 20,
            "Landroidx/compose/": 50,
        },
    },
}


def benchmark_entry(
    name: str,
    median: float,
    *,
    repeat_iterations: int = 10,
    warmup_iterations: int = 0,
    metric: str = "timeToInitialDisplayMs",
) -> dict[str, Any]:
    """One entry of the `benchmarks` array, shaped the way Macrobenchmark writes it."""
    return {
        "name": name,
        "params": {},
        "className": "com.kojo.boilerplate.benchmark.StartupBenchmark",
        "totalRunTimeNs": 42_000_000_000,
        "metrics": {
            metric: {
                "minimum": median - 120.0,
                "maximum": median + 180.0,
                "median": median,
                "runs": [median] * repeat_iterations,
            }
        },
        "sampledMetrics": {},
        "warmupIterations": warmup_iterations,
        "repeatIterations": repeat_iterations,
        "thermalThrottleSleepSeconds": 0,
    }


def benchmark_data(
    benchmarks: list[dict[str, Any]] | None = None, *, sdk: int = 34
) -> dict[str, Any]:
    """A whole `benchmarkData.json`, context included."""
    if benchmarks is None:
        benchmarks = [
            benchmark_entry("withoutCompilation", 2100.0),
            benchmark_entry("withBaselineProfile", 1500.0),
        ]
    return {
        "context": {
            "build": {
                "brand": "google",
                "device": "emu64x",
                "fingerprint": "google/sdk_gphone64_x86_64/emu64x:14/UE1A.230829.036/-:user/"
                "release-keys",
                "model": "sdk_gphone64_x86_64",
                "version": {"codename": "REL", "sdk": sdk},
            },
            "cpuCoreCount": 4,
            "cpuLocked": False,
            "cpuMaxFreqHz": -1,
            "memTotalBytes": 6_527_471_616,
            "sustainedPerformanceModeEnabled": False,
        },
        "benchmarks": benchmarks,
    }


def profile(
    *,
    app_rules: int = 30,
    compose_rules: int = 80,
    include_application: bool = True,
    include_activity: bool = True,
    extra: str = "",
) -> str:
    """An ART profile in human-readable format, at a realistic size and rule mix.

    The flag mix is the one a real recording has: hot-and-startup methods, startup-only classes, and
    bare class descriptors with no flags at all.
    """
    lines: list[str] = []
    if include_application:
        lines.append("Lcom/kojo/boilerplate/BoilerplateApp;")
        lines.append("HSPLcom/kojo/boilerplate/BoilerplateApp;->onCreate()V")
    if include_activity:
        lines.append("Lcom/kojo/boilerplate/MainActivity;")
        lines.append(
            "HSPLcom/kojo/boilerplate/MainActivity;->onCreate(Landroid/os/Bundle;)V"
        )
    for index in range(app_rules):
        lines.append(
            f"HSPLcom/kojo/boilerplate/feature/signin/GoogleSignInScreenKt;"
            f"->access$method{index}(Ljava/lang/Object;)V"
        )
    for index in range(compose_rules):
        lines.append(
            f"HSPLandroidx/compose/runtime/ComposerImpl;->method{index}(I)Ljava/lang/Object;"
        )
    # Padding from a package nothing in the budget names, so the total-count floor and the
    # per-package floors cannot be confused for each other.
    for index in range(150):
        lines.append(f"PLandroidx/collection/MutableIntList;->pad{index}()V")
    if extra:
        lines.append(extra)
    return "\n".join(lines) + "\n"


def write(
    root: Path,
    *,
    budget: dict[str, Any] | None,
    data: dict[str, Any] | None,
    committed: str | None,
    generated: str | None,
) -> Path:
    """Lays out a fixture repository. `None` means "this artifact is absent"."""
    if budget is not None:
        path = root / BUDGET_PATH
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(budget, indent=2), encoding="utf-8")
    if data is not None:
        path = root / OUTPUT_DIR / DATA_NAME
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(data, indent=2), encoding="utf-8")
    if committed is not None:
        path = root / PROFILE_PATH
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(committed, encoding="utf-8")
    if generated is not None:
        path = root / OUTPUT_DIR / GENERATED_NAME
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(generated, encoding="utf-8")
    return root


# `None` already means "this artifact is absent" in `write`, so the default has to be something
# else entirely: three of the cases below exist precisely to check that a missing budget file,
# missing benchmark data or a missing committed profile is reported rather than passed over.
DEFAULT = object()

FAILURES: list[str] = []


def check(
    name: str,
    *,
    expect_exit: int,
    expect_in_output: str = "",
    budget: dict[str, Any] | None | Any = DEFAULT,
    data: dict[str, Any] | None | Any = DEFAULT,
    committed: str | None | Any = DEFAULT,
    generated: str | None = None,
) -> None:
    with tempfile.TemporaryDirectory() as directory:
        root = write(
            Path(directory),
            budget=GOOD_BUDGET if budget is DEFAULT else budget,
            data=benchmark_data() if data is DEFAULT else data,
            committed=profile() if committed is DEFAULT else committed,
            generated=generated,
        )
        result = subprocess.run(
            [sys.executable, str(VERIFY), "--root", str(root)],
            capture_output=True,
            text=True,
            check=False,
        )

    output = result.stdout + result.stderr
    if result.returncode != expect_exit:
        FAILURES.append(
            f"{name}: expected exit {expect_exit}, got {result.returncode}\n"
            + "\n".join(f"    {line}" for line in output.splitlines())
        )
        return
    if expect_in_output and expect_in_output not in output:
        FAILURES.append(
            f"{name}: expected {expect_in_output!r} in the output, got\n"
            + "\n".join(f"    {line}" for line in output.splitlines())
        )
        return
    print(f"  ok  {name}")


def without(mapping: dict[str, Any], *path: str) -> dict[str, Any]:
    """A deep copy of `mapping` with the key at `path` removed."""
    copy = json.loads(json.dumps(mapping))
    cursor = copy
    for key in path[:-1]:
        cursor = cursor[key]
    cursor.pop(path[-1], None)
    return copy


def with_value(mapping: dict[str, Any], value: Any, *path: str) -> dict[str, Any]:
    """A deep copy of `mapping` with the key at `path` set to `value`."""
    copy = json.loads(json.dumps(mapping))
    cursor = copy
    for key in path[:-1]:
        cursor = cursor[key]
    cursor[path[-1]] = value
    return copy


def main() -> int:
    print("verify-startup-budget.py")

    # --- the happy paths -------------------------------------------------------------------

    check(
        "a run inside budget with a covering profile passes",
        expect_exit=0,
        expect_in_output="case(s) inside budget",
    )

    check(
        "the measured numbers and the headroom are reported, not just the verdict",
        expect_exit=0,
        expect_in_output="against a budget of 2100 ms",
    )

    check(
        "the build context is reported so an unexpected machine is visible",
        expect_exit=0,
        expect_in_output="sdk_gphone64_x86_64",
    )

    check(
        "a freshly generated profile the committed one covers passes",
        generated=profile(),
        expect_exit=0,
        expect_in_output="freshly generated",
    )

    check(
        "no generated profile says so rather than claiming the staleness check ran",
        expect_exit=0,
        expect_in_output="staleness check did not run",
    )

    # --- reading the inputs at all ---------------------------------------------------------

    check(
        "a missing budget file is an error, not an empty audit",
        budget=None,
        expect_exit=2,
        expect_in_output="does not exist",
    )

    check(
        "an unknown key in the budget fails rather than doing nothing",
        budget={**GOOD_BUDGET, "budgetMs": 900},
        expect_exit=2,
        expect_in_output="unknown key(s) budgetMs",
    )

    check(
        "an unknown key inside a case fails too",
        budget=with_value(
            GOOD_BUDGET,
            {
                "budgetMs": 2100,
                "minimumIterations": 10,
                "maximumWarmupIterations": 0,
                "tolerancePercent": 10,
            },
            "cases",
            "withBaselineProfile",
        ),
        expect_exit=2,
        expect_in_output="unknown key(s) tolerancePercent",
    )

    check(
        "a case missing a limit fails rather than defaulting to permissive",
        budget=without(GOOD_BUDGET, "cases", "withBaselineProfile", "budgetMs"),
        expect_exit=2,
        expect_in_output="is missing budgetMs",
    )

    check(
        "no benchmark data at all is an error",
        data=None,
        expect_exit=2,
        expect_in_output="benchmark did not run",
    )

    check(
        "benchmark data with no StartupBenchmark results is an error",
        data=with_value(benchmark_data(), [], "benchmarks"),
        expect_exit=2,
        expect_in_output="none of them contained a result",
    )

    check(
        "a result missing the budgeted metric is an error, not a pass",
        data=with_value(
            benchmark_data(),
            [
                benchmark_entry("withoutCompilation", 2100.0),
                benchmark_entry("withBaselineProfile", 1500.0, metric="timeToFullDisplayMs"),
            ],
            "benchmarks",
        ),
        expect_exit=2,
        expect_in_output="reported no 'timeToInitialDisplayMs'",
    )

    # --- the budget ------------------------------------------------------------------------

    check(
        "a median over budget fails",
        data=with_value(
            benchmark_data(),
            [
                benchmark_entry("withoutCompilation", 2100.0),
                benchmark_entry("withBaselineProfile", 2400.0),
            ],
            "benchmarks",
        ),
        expect_exit=1,
        expect_in_output="over its 2100 ms budget by 300.0 ms",
    )

    check(
        "a median exactly on budget passes",
        data=with_value(
            benchmark_data(),
            [
                benchmark_entry("withoutCompilation", 2600.0),
                benchmark_entry("withBaselineProfile", 2100.0),
            ],
            "benchmarks",
        ),
        expect_exit=0,
    )

    check(
        "a different API level fails rather than being compared to the wrong baseline",
        data=benchmark_data(sdk=33),
        expect_exit=1,
        expect_in_output="calibrated for API 34",
    )

    check(
        "benchmark data with no API level at all fails",
        data=without(benchmark_data(), "context", "build"),
        expect_exit=1,
        expect_in_output="no way to tell",
    )

    check(
        "too few iterations fails even when the number is inside budget",
        data=with_value(
            benchmark_data(),
            [
                benchmark_entry("withoutCompilation", 500.0, repeat_iterations=3),
                benchmark_entry("withBaselineProfile", 400.0),
            ],
            "benchmarks",
        ),
        expect_exit=1,
        expect_in_output="ran 3 iterations",
    )

    check(
        "warmup iterations fail, because the number stops being attributable",
        data=with_value(
            benchmark_data(),
            [
                benchmark_entry("withoutCompilation", 2100.0),
                benchmark_entry("withBaselineProfile", 900.0, warmup_iterations=3),
            ],
            "benchmarks",
        ),
        expect_exit=1,
        expect_in_output="warmup iterations, above the 0 allowed",
    )

    check(
        "a budgeted case the run did not measure fails",
        data=with_value(
            benchmark_data(), [benchmark_entry("withBaselineProfile", 1500.0)], "benchmarks"
        ),
        expect_exit=1,
        expect_in_output="the budget names 'withoutCompilation' but the run did not measure it",
    )

    check(
        "a measured case nothing budgets fails",
        data=with_value(
            benchmark_data(),
            [
                benchmark_entry("withoutCompilation", 2100.0),
                benchmark_entry("withBaselineProfile", 1500.0),
                benchmark_entry("withWarmedJit", 800.0),
            ],
            "benchmarks",
        ),
        expect_exit=1,
        expect_in_output="'withWarmedJit' was measured but has no entry in the budget",
    )

    check(
        "the same case measured twice is ambiguous and fails",
        data=with_value(
            benchmark_data(),
            [
                benchmark_entry("withoutCompilation", 2100.0),
                benchmark_entry("withBaselineProfile", 1500.0),
                benchmark_entry("withBaselineProfile", 900.0),
            ],
            "benchmarks",
        ),
        expect_exit=1,
        expect_in_output="was measured 2 times",
    )

    # --- the committed profile -------------------------------------------------------------

    check(
        "a missing committed profile is a finding that names the generator",
        committed=None,
        expect_exit=1,
        expect_in_output="BaselineProfileGenerator",
    )

    # The case that cost a CI round trip: the gate exited on the missing profile before printing
    # the medians, which are the only thing a budget can be recalibrated from and are otherwise
    # thousands of lines of generated profile away in the job log.
    check(
        "a missing committed profile still reports the measured startup numbers",
        committed=None,
        expect_exit=1,
        expect_in_output="against a budget of 2100 ms",
    )

    check(
        "a missing committed profile still names the freshly generated one as the fix",
        committed=None,
        generated=profile(),
        expect_exit=1,
        expect_in_output="this is the file to commit",
    )

    check(
        "an unparsable committed profile also still reports the numbers",
        committed=profile(extra="# regenerated 2026-10-01"),
        expect_exit=1,
        expect_in_output="against a budget of 2100 ms",
    )

    check(
        "a line that is not a rule fails rather than being skipped",
        committed=profile(extra="# regenerated 2026-10-01"),
        expect_exit=1,
        expect_in_output="is not a profile rule",
    )

    check(
        "a descriptor with no leading L fails",
        committed=profile(extra="HSPLcom/kojo/boilerplate/Oops->onCreate()V"),
        expect_exit=1,
        expect_in_output="is not a profile rule",
    )

    check(
        "a wildcard rule parses, because AGP accepts them",
        committed=profile(extra="Landroidx/compose/ui/**;"),
        expect_exit=0,
    )

    check(
        "a blank line is not a parse failure",
        committed=profile() + "\n\n",
        expect_exit=0,
    )

    check(
        "a truncated profile fails the total-rule floor",
        committed=(
            "Lcom/kojo/boilerplate/BoilerplateApp;\nLcom/kojo/boilerplate/MainActivity;\n"
        ),
        expect_exit=1,
        expect_in_output="below the 200 the budget requires",
    )

    check(
        "a profile that does not name the Application fails",
        committed=profile(include_application=False),
        expect_exit=1,
        expect_in_output="does not name Lcom/kojo/boilerplate/BoilerplateApp;",
    )

    check(
        "a profile that does not name the launched Activity fails",
        committed=profile(include_activity=False),
        expect_exit=1,
        expect_in_output="does not name Lcom/kojo/boilerplate/MainActivity;",
    )

    check(
        "a profile with too few rules for the app's own package fails",
        committed=profile(app_rules=0, compose_rules=200),
        expect_exit=1,
        expect_in_output="rule(s) under Lcom/kojo/boilerplate/, below the required 20",
    )

    check(
        "a profile with too few Compose rules fails",
        committed=profile(compose_rules=0, app_rules=120),
        expect_exit=1,
        expect_in_output="rule(s) under Landroidx/compose/, below the required 50",
    )

    # --- staleness -------------------------------------------------------------------------

    check(
        "a committed profile missing a class the fresh one records is stale and fails",
        committed=profile(include_activity=False, app_rules=120),
        generated=profile(),
        expect_exit=1,
        expect_in_output="is stale: commit the generated one",
    )

    check(
        "a committed profile with no rules for a package the fresh one covers is stale",
        committed=profile(compose_rules=0, app_rules=120),
        generated=profile(),
        expect_exit=1,
        expect_in_output="has none. The committed profile is stale",
    )

    check(
        "a fresh profile that is narrower than the committed one is not a staleness failure",
        committed=profile(app_rules=60),
        generated=profile(app_rules=25),
        expect_exit=0,
    )

    print()
    if FAILURES:
        print(f"FAIL: {len(FAILURES)} case(s) did not behave as expected:")
        for failure in FAILURES:
            print(f"  - {failure}")
        return 1
    print("OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
