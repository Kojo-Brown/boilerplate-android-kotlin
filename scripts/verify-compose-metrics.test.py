#!/usr/bin/env python3
"""Tests for scripts/verify-compose-metrics.py.

The happy path is exercised by every CI run. The failure paths — the whole reason the script
exists — are not: a green build says nothing about whether a composable that stopped skipping
would actually be caught, or whether a module that quietly reported nothing would be noticed.
Each scenario here is a fixture repository written into a temp directory and the real script
run over it, so every check can be shown to fail when it should and only when it should.

It needs nothing but Python: no Gradle, no Android SDK, no network. That is deliberate — it
keeps the gate's logic verifiable in an environment where the Compose compiler cannot run at
all, which is the environment this repository's scheduled agent has.

Usage: scripts/verify-compose-metrics.test.py
"""

from __future__ import annotations

import json
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
VERIFY = HERE / "verify-compose-metrics.py"

SKIPPABLE = """\
restartable skippable scheme("[androidx.compose.ui.UiComposable]") fun HomeScreen(
  stable modifier: Modifier? = @static Companion
  stable state: HomeUiState
  stable onEvent: Function1<HomeEvent, Unit>
)
"""

UNSKIPPABLE = """\
restartable scheme("[androidx.compose.ui.UiComposable]") fun ProfileScreen(
  stable modifier: Modifier? = @static Companion
  unstable state: ProfileUiState
)
"""

READONLY_AND_INLINE = """\
readonly fun currentTheme(
)
inline fun WithSpacing(
  stable content: Function0<Unit>
)
"""

# The three declaration shapes that are not `flags fun Name(`: no parameters at all, a
# generic composable, and the anonymous function a composable lambda is reported as. Each one
# would be dropped silently by a stricter parser, which is the way an audit passes having
# looked at nothing.
AWKWARD_DECLARATIONS = """\
restartable fun EmptySignature()
restartable fun <T> GenericRow(
  unstable item: T
)
restartable fun <anonymous>(
  unstable rows: List<Row>
)
"""

# The same two composables with the scheme clause before the flags rather than after them.
# Which order the compiler emits is not something this script should depend on, and a regex
# that fixed one order would silently read nothing at all under the other — which is the
# leading suspect for the 45-of-164 the first version managed.
SCHEME_FIRST = """\
scheme("[androidx.compose.ui.UiComposable]") restartable skippable fun HomeScreen(
  stable state: HomeUiState
)
scheme("[0, [0]]") restartable fun HomeContent(
  unstable rows: List<Row>
)
"""

CLASSES = """\
unstable class ProfileUiState {
  stable val name: String
  unstable val badges: List<Badge>
  <runtime stability> = Unstable
}
stable class HomeUiState {
  stable val title: String
  <runtime stability> = Stable
}
"""

# The `-module.json` half of each fixture, matching the `-composables.txt` half above it.
# They are stated rather than counted from the text: the point of `assert_parse_is_complete`
# is that the two files are independent descriptions of one compilation, and a fixture that
# derived one from the other would test nothing.
METRICS = {
    "SKIPPABLE": {"totalComposables": 1, "restartableComposables": 1, "skippableComposables": 1},
    "UNSKIPPABLE": {
        "totalComposables": 1,
        "restartableComposables": 1,
        "skippableComposables": 0,
    },
    "READONLY_AND_INLINE": {
        "totalComposables": 2,
        "restartableComposables": 0,
        "skippableComposables": 0,
    },
    "AWKWARD_DECLARATIONS": {
        "totalComposables": 3,
        "restartableComposables": 3,
        "skippableComposables": 0,
    },
    "SCHEME_FIRST": {
        "totalComposables": 2,
        "restartableComposables": 2,
        "skippableComposables": 1,
    },
}


class Repo:
    """A fixture repository: settings, module build files, and whatever reports are wanted."""

    def __init__(self, root: Path) -> None:
        self.root = root
        self.modules: list[str] = []

    def module(self, path: str, *, compose: bool = True) -> "Repo":
        self.modules.append(path)
        directory = self.root.joinpath(*path.strip(":").split(":"))
        directory.mkdir(parents=True, exist_ok=True)
        plugin = (
            "boilerplate.android.library.compose" if compose else "boilerplate.android.library"
        )
        (directory / "build.gradle.kts").write_text(
            f'plugins {{\n    id("{plugin}")\n}}\n', encoding="utf-8"
        )
        self._write_settings()
        return self

    def reports(
        self,
        path: str,
        *,
        composables: str,
        metrics: dict[str, int],
        classes: str = CLASSES,
    ) -> "Repo":
        directory = self.root.joinpath(*path.strip(":").split(":"), "build")
        name = path.strip(":").replace(":", "_")
        (directory / "compose-reports").mkdir(parents=True, exist_ok=True)
        (directory / "compose-metrics").mkdir(parents=True, exist_ok=True)
        (directory / "compose-reports" / f"{name}_debug-composables.txt").write_text(
            composables, encoding="utf-8"
        )
        (directory / "compose-reports" / f"{name}_debug-classes.txt").write_text(
            classes, encoding="utf-8"
        )
        (directory / "compose-metrics" / f"{name}_debug-module.json").write_text(
            json.dumps({**metrics, "inferredUnstableClasses": 1, "totalClasses": 2}),
            encoding="utf-8",
        )
        return self

    def allowlist(self, contents: str) -> "Repo":
        path = self.root / "config" / "compose-metrics" / "unskippable-allowlist.txt"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(contents, encoding="utf-8")
        return self

    def _write_settings(self) -> None:
        includes = "\n".join(f'include("{module}")' for module in self.modules)
        (self.root / "settings.gradle.kts").write_text(
            f'rootProject.name = "Fixture"\n{includes}\n', encoding="utf-8"
        )


def fixture(name: str) -> dict:
    """The two halves of a fixture — the report text and the compiler counts that match it."""
    return {"composables": globals()[name], "metrics": METRICS[name]}


def run(repo: Repo) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(VERIFY), "--root", str(repo.root)],
        capture_output=True,
        text=True,
        check=False,
    )


# --- scenarios ---------------------------------------------------------------------------

failures: list[str] = []
run_count = 0


def check(name: str, repo_builder, *, expect_exit: int, expect_in_output: str = "") -> None:
    global run_count
    run_count += 1
    with tempfile.TemporaryDirectory() as work:
        repo = Repo(Path(work))
        repo_builder(repo)
        result = run(repo)
        output = result.stdout + result.stderr
        if result.returncode != expect_exit:
            failures.append(
                f"{name}: expected exit {expect_exit}, got {result.returncode}\n{output}"
            )
        elif expect_in_output and expect_in_output not in output:
            failures.append(f"{name}: expected {expect_in_output!r} in output\n{output}")
        else:
            print(f"ok   {name}")
            return
    print(f"FAIL {name}")


check(
    "a module whose composables all skip passes",
    lambda repo: repo.module(":feature:home").reports(":feature:home", **fixture("SKIPPABLE")),
    expect_exit=0,
)

check(
    "a restartable composable that cannot skip fails",
    lambda repo: repo.module(":feature:profile").reports(
        ":feature:profile", **fixture("UNSKIPPABLE")
    ),
    expect_exit=1,
    expect_in_output=":feature:profile ProfileScreen",
)

check(
    "the failure names the unstable parameter",
    lambda repo: repo.module(":feature:profile").reports(
        ":feature:profile", **fixture("UNSKIPPABLE")
    ),
    expect_exit=1,
    expect_in_output="unstable parameter: unstable state: ProfileUiState",
)

check(
    "the failure names the member that made the parameter unstable",
    lambda repo: repo.module(":feature:profile").reports(
        ":feature:profile", **fixture("UNSKIPPABLE")
    ),
    expect_exit=1,
    expect_in_output="ProfileUiState.val badges: List<Badge>",
)

check(
    "an allowed composable with a reason passes",
    lambda repo: repo.module(":feature:profile")
    .reports(":feature:profile", **fixture("UNSKIPPABLE"))
    .allowlist(":feature:profile ProfileScreen  # the badge list is server-ordered\n"),
    expect_exit=0,
)

check(
    "an allowlist entry without a reason fails",
    lambda repo: repo.module(":feature:profile")
    .reports(":feature:profile", **fixture("UNSKIPPABLE"))
    .allowlist(":feature:profile ProfileScreen\n"),
    expect_exit=1,
    expect_in_output="has no `# reason`",
)

check(
    "a malformed allowlist line fails",
    lambda repo: repo.module(":feature:profile")
    .reports(":feature:profile", **fixture("UNSKIPPABLE"))
    .allowlist("ProfileScreen  # missing the module\n"),
    expect_exit=1,
    expect_in_output="expected `:module ComposableName",
)

check(
    "an allowlist entry that matches nothing fails",
    lambda repo: repo.module(":feature:home")
    .reports(":feature:home", **fixture("SKIPPABLE"))
    .allowlist(":feature:home HomeScreen  # it skips now, so this line should be gone\n"),
    expect_exit=1,
    expect_in_output="no longer match anything",
)

check(
    "a Compose module that reported nothing fails",
    lambda repo: repo.module(":feature:home")
    .module(":feature:profile")
    .reports(":feature:home", **fixture("SKIPPABLE")),
    expect_exit=1,
    expect_in_output=":feature:profile",
)

check(
    "a module without the Compose plugin is not expected to report",
    lambda repo: repo.module(":feature:home")
    .module(":core:domain", compose=False)
    .reports(":feature:home", **fixture("SKIPPABLE")),
    expect_exit=0,
)

check(
    "readonly and inline composables are not restartable and are not flagged",
    lambda repo: repo.module(":core:ui").reports(":core:ui", **fixture("READONLY_AND_INLINE")),
    expect_exit=0,
)

check(
    "a repository with no Compose module at all fails rather than passing vacuously",
    lambda repo: repo.module(":core:domain", compose=False),
    expect_exit=1,
    expect_in_output="No module applies a Compose convention plugin",
)

check(
    "a parameterless, a generic and an anonymous composable are all seen",
    lambda repo: repo.module(":core:ui").reports(":core:ui", **fixture("AWKWARD_DECLARATIONS"))
    .allowlist(
        ":core:ui EmptySignature  # no parameters, so nothing to make it skippable\n"
        ":core:ui GenericRow  # the type parameter is unstable at every call site\n"
    ),
    expect_exit=1,
    expect_in_output=":core:ui <anonymous>",
)

# The regression tests for the bug this gate shipped with. On its first CI run the compiler
# reported 164 composables of which 47 did not skip; the script read 45 of them, found no
# violation, and passed. Both halves of that are checked here against the compiler's own
# counts, because both halves fail *green* and nothing else in this file would notice.
check(
    "a report the parser under-reads fails against the compiler's count",
    lambda repo: repo.module(":feature:home").reports(
        ":feature:home",
        composables=SKIPPABLE,
        metrics={
            "totalComposables": 5,
            "restartableComposables": 5,
            "skippableComposables": 5,
        },
    ),
    expect_exit=1,
    expect_in_output="reported 5 composable(s)",
)

check(
    "the right number of composables with the wrong flags read still fails",
    lambda repo: repo.module(":feature:home").reports(
        ":feature:home",
        composables=SKIPPABLE,
        metrics={
            "totalComposables": 1,
            "restartableComposables": 1,
            "skippableComposables": 0,
        },
    ),
    expect_exit=1,
    expect_in_output="this script read 1 and found 0",
)

check(
    "a parse mismatch quotes the report it could not read",
    lambda repo: repo.module(":feature:home").reports(
        ":feature:home",
        composables=SKIPPABLE,
        metrics={
            "totalComposables": 5,
            "restartableComposables": 5,
            "skippableComposables": 5,
        },
    ),
    expect_exit=1,
    expect_in_output="the head of the report this was read from",
)

check(
    "an unparseable declaration line is named in the failure",
    lambda repo: repo.module(":feature:home").reports(
        ":feature:home",
        composables="composable-in-some-future-format HomeScreen\n",
        metrics={
            "totalComposables": 1,
            "restartableComposables": 1,
            "skippableComposables": 1,
        },
    ),
    expect_exit=1,
    expect_in_output="composable-in-some-future-format HomeScreen",
)

check(
    "the scheme clause is read on either side of the flags",
    lambda repo: repo.module(":feature:home").reports(":feature:home", **fixture("SCHEME_FIRST")),
    expect_exit=1,
    expect_in_output=":feature:home HomeContent",
)

check(
    "comments and blank lines in the allowlist are ignored",
    lambda repo: repo.module(":feature:profile")
    .reports(":feature:profile", **fixture("UNSKIPPABLE"))
    .allowlist(
        "# a header\n\n   \n:feature:profile ProfileScreen  # the badge list is server-ordered\n"
    ),
    expect_exit=0,
)

print(f"\n{run_count - len(failures)}/{run_count} passed")
for failure in failures:
    print(f"\n--- {failure}", file=sys.stderr)
sys.exit(1 if failures else 0)
