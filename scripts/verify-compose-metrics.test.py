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
# The real reports put it after, which is what the first version of the parser assumed; a
# regex pinned to one order reads *nothing at all* under the other, so the order is a thing
# this script deliberately does not depend on.
SCHEME_FIRST = """\
scheme("[androidx.compose.ui.UiComposable]") restartable skippable fun HomeScreen(
  stable state: HomeUiState
)
scheme("[0, [0]]") restartable fun HomeContent(
  unstable rows: List<Row>
)
"""

# Copied from :app's real report. The default value of `navController` is the compiler's own
# lowered IR, and the `)` that closes `rememberNavController(` sits at column zero — read by
# indentation it ends the composable three lines early and `startDestination`, an unstable
# parameter and exactly what this gate exists to find, is attributed to nothing and dropped.
MULTILINE_DEFAULT = """\
restartable skippable scheme("[androidx.compose.ui.UiComposable]") fun AppNavHost(
  unstable appEvents: Flow<AppEvent>
  stable modifier: Modifier? = @static Companion
  unstable navController: NavHostController? = @dynamic rememberNavController(
  $composer   =   $composer  ,\x20
  $changed   =   0
)
  unstable startDestination: AppDestination? = @dynamic SignIn
)
restartable scheme("[androidx.compose.ui.UiComposable, [androidx.compose.ui.UiComposable]]") fun MainNavScaffold(
  unstable navController: NavHostController
  stable content: Function2<Composer, Int, Unit>
)
"""

# The other shape the same problem takes, also from the real reports: a `<block>{ … }` default
# that runs for a dozen lines and ends with a line beginning `}, $composer, …` in declaration
# position. Ends with the `): ReturnType` a composable that returns something closes on.
BLOCK_DEFAULT = """\
restartable skippable scheme("[androidx.compose.ui.UiComposable]") fun ProfileDetailPane(
  stable userId: String
  unstable viewModel: ProfileDetailPaneViewModel? = @dynamic hiltViewModel(null, userId, <block>{
  $composer  .  startReplaceGroup  (  -461555555  )
  sourceInformation  (  $composer  ,   "CC(remember):ProfileDetailPane.kt#9igjgp"  )
  val   tmp0_group   =   $composer  .  cache  (  $dirty   and   0b1110   ==   0b0100  )     {
    {         factory    :     Factory     ->
      factory      .      create      (      userId      )
    }

  }

  $composer  .  endReplaceGroup  (  )
  tmp0_group  @  com.kojo.boilerplate.feature.profile.ProfileDetailPane
}, $composer, 0b01110000 and $dirty shl 0b0011, 0b0001)
)
restartable skippable fun rememberEventSink(
  stable onEvent: Function1<E, Unit>
): Function1<E, Unit>
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

# The `-module.json` half of each fixture. Stated rather than counted from the text above it:
# the two files describe the same compilation independently, and a fixture that derived one
# from the other could not test a reader of both. The counts are deliberately not equal to the
# number of entries — the compiler counts lambdas, the report names only top-level functions.
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
    # The compiler's counts include lambdas, so they run well ahead of the two and two named
    # composables in these reports. That gap is real and is why nothing asserts equality.
    "MULTILINE_DEFAULT": {
        "totalComposables": 13,
        "restartableComposables": 13,
        "skippableComposables": 8,
    },
    "BLOCK_DEFAULT": {
        "totalComposables": 32,
        "restartableComposables": 32,
        "skippableComposables": 20,
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

# The regression tests for what this gate shipped with. Its first CI run was green while the
# parser was reading a composable's parameters only until the first column-zero line inside a
# default value. Everything below fails *green* without a check on the reader itself, which is
# the only kind of failure that matters for an audit whose whole output is "nothing to report".
check(
    "a parameter after a multi-line default is still attributed to its composable",
    lambda repo: repo.module(":app").reports(":app", **fixture("MULTILINE_DEFAULT")),
    expect_exit=1,
    expect_in_output="unstable parameter: unstable navController: NavHostController",
)

check(
    "the composable after a multi-line default is still read",
    lambda repo: repo.module(":app").reports(":app", **fixture("MULTILINE_DEFAULT")),
    expect_exit=1,
    expect_in_output=":app MainNavScaffold",
)

check(
    "a block default and a return type do not leave unparsed declarations",
    lambda repo: repo.module(":feature:profile").reports(
        ":feature:profile", **fixture("BLOCK_DEFAULT")
    ),
    expect_exit=0,
)

check(
    "a report nothing could be read from fails",
    lambda repo: repo.module(":feature:home").reports(
        ":feature:home",
        composables="\n",
        metrics={
            "totalComposables": 9,
            "restartableComposables": 9,
            "skippableComposables": 9,
        },
    ),
    expect_exit=1,
    expect_in_output="no composable was read out of the report at all",
)

check(
    "reading more composables than the compiler counted fails",
    lambda repo: repo.module(":feature:home").reports(
        ":feature:home",
        composables=SKIPPABLE,
        metrics={
            "totalComposables": 0,
            "restartableComposables": 0,
            "skippableComposables": 0,
        },
    ),
    expect_exit=1,
    expect_in_output="more than the 0 the compiler counted",
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
