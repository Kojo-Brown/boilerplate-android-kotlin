#!/usr/bin/env python3
"""Tests for scripts/verify-r8-mapping.py.

The happy path runs on every CI build. The failure paths — the only reason that script exists —
do not: a green build says nothing about whether a keep rule that stopped matching would actually
be noticed, and "noticed" is the entire claim. Each case here writes a fixture repository and a
fabricated mapping file into a temp directory, runs the real script over it, and checks that it
fails when it should and only when it should.

The mapping fixtures are the shapes R8 really emits, including the two that a naive parser gets
wrong: an inlined frame, which names another class's method indented under this one, and a method
whose line ranges are present on both sides of the arrow.

It needs nothing but Python: no Gradle, no Android SDK, no network, no APK. That is deliberate.
R8 cannot run in the environment this repository's scheduled agent has — Google Maven is
unreachable there, so neither AGP nor the Android SDK can be fetched — and this keeps the gate's
own logic checkable anyway.

Usage: scripts/verify-r8-mapping.test.py
"""

from __future__ import annotations

import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
VERIFY = HERE / "verify-r8-mapping.py"

MAPPING_DIR = Path("app/build/outputs/mapping/minified")

CONVENTIONS = """\
package com.kojo.boilerplate.buildlogic

object BoilerplateBuild {
    const val NAMESPACE_PREFIX = "com.kojo.boilerplate"
}
"""

DESTINATIONS = """\
package com.kojo.boilerplate.core.navigation

import kotlinx.serialization.Serializable

sealed interface AppDestination {
    @Serializable
    data object Home : AppDestination

    @Serializable
    data class Profile(val userId: String) : AppDestination
}
"""

DTO = """\
package com.kojo.boilerplate.core.network.model

import kotlinx.serialization.Serializable

@Serializable data class UserDto(val id: String, val email: String)
"""

PROTO = """\
syntax = "proto3";

package com.kojo.boilerplate.core.datastore.proto;

option java_package = "com.kojo.boilerplate.core.datastore.proto";
option java_multiple_files = true;
option java_outer_classname = "UserPreferencesSchema";

message UserPreferencesProto {
  bool onboarding_complete = 1;
}
"""

# A shrunk output that satisfies every rule: the app's classes are renamed, the members that are
# looked up by name are not, and the protobuf fields keep their names.
GOOD_MAPPING = """\
# compiler: R8
# compiler_version: 8.5.35
# common_typos_disable
com.kojo.boilerplate.MainActivity -> a.a.a:
    1:6:void onCreate(android.os.Bundle):28:33 -> onCreate
    7:7:void androidx.activity.ComponentActivity.setContent():41:41 -> onCreate
com.kojo.boilerplate.core.navigation.AppDestination -> a.a.b:
com.kojo.boilerplate.core.navigation.AppDestination$Home -> a.a.c:
    com.kojo.boilerplate.core.navigation.AppDestination$Home INSTANCE -> INSTANCE
    1:1:kotlinx.serialization.KSerializer serializer():0:0 -> serializer
com.kojo.boilerplate.core.navigation.AppDestination$Profile -> a.a.d:
    java.lang.String userId -> a
    com.kojo.boilerplate.core.navigation.AppDestination$Profile$Companion Companion -> Companion
com.kojo.boilerplate.core.network.model.UserDto -> a.b.a:
    java.lang.String id -> a
    java.lang.String email -> b
    com.kojo.boilerplate.core.network.model.UserDto$Companion Companion -> Companion
com.kojo.boilerplate.core.datastore.proto.UserPreferencesProto -> a.c.a:
    boolean onboardingComplete_ -> onboardingComplete_
    1:3:void <init>():14:16 -> <init>
"""


def write(root: Path, mapping: str, **extra: str) -> Path:
    """Lay out a fixture repository and return its root."""
    files = {
        "build-logic/convention/src/main/kotlin/com/kojo/boilerplate/buildlogic/"
        "Conventions.kt": CONVENTIONS,
        "core/navigation/src/main/kotlin/com/kojo/boilerplate/core/navigation/"
        "AppDestination.kt": DESTINATIONS,
        "data/src/main/kotlin/com/kojo/boilerplate/core/network/model/UserDto.kt": DTO,
        "core/datastore-proto/src/main/proto/user_preferences.proto": PROTO,
    }
    if mapping:
        files[str(MAPPING_DIR / "mapping.txt")] = mapping
    for name, contents in extra.items():
        files[str(MAPPING_DIR / name.replace("__", "."))] = contents

    for relative, contents in files.items():
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(contents, encoding="utf-8")
    return root


def run(root: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(VERIFY), "--root", str(root)],
        capture_output=True,
        text=True,
        check=False,
    )


FAILURES: list[str] = []


def check(name: str, *, expect_exit: int, expect_in_output: str = "", **fixture: str) -> None:
    with tempfile.TemporaryDirectory() as directory:
        root = write(Path(directory), fixture.pop("mapping", GOOD_MAPPING), **fixture)
        result = run(root)

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


def main() -> int:
    print("verify-r8-mapping.py")

    check(
        "a shrunk output that satisfies every rule passes",
        expect_exit=0,
        expect_in_output="still matches what it was written for",
    )

    check(
        "no mapping file at all is a failure, not an empty audit",
        mapping="",
        expect_exit=1,
        expect_in_output="does not exist",
    )

    check(
        "a mapping with no class entries fails",
        mapping="# compiler: R8\n",
        expect_exit=1,
        expect_in_output="holds no class entries",
    )

    check(
        "nothing renamed means the build type is not minifying",
        mapping=GOOD_MAPPING.replace("-> a.a.a:", "-> com.kojo.boilerplate.MainActivity:")
        .replace("-> a.a.b:", "-> com.kojo.boilerplate.core.navigation.AppDestination:")
        .replace("-> a.a.c:", "-> com.kojo.boilerplate.core.navigation.AppDestination$Home:")
        .replace("-> a.a.d:", "-> com.kojo.boilerplate.core.navigation.AppDestination$Profile:")
        .replace("-> a.b.a:", "-> com.kojo.boilerplate.core.network.model.UserDto:")
        .replace(
            "-> a.c.a:",
            "-> com.kojo.boilerplate.core.datastore.proto.UserPreferencesProto:",
        ),
        expect_exit=1,
        expect_in_output="The build type is not minifying",
    )

    check(
        "a @Serializable object shrunk away is caught",
        mapping="\n".join(
            line
            for line in GOOD_MAPPING.splitlines()
            if "AppDestination$Home" not in line and "INSTANCE" not in line
            and "serializer()" not in line
        )
        + "\n",
        expect_exit=1,
        expect_in_output="@Serializable type(s) did not survive",
    )

    check(
        "a generated protobuf message shrunk away is caught",
        mapping="\n".join(
            line
            for line in GOOD_MAPPING.splitlines()
            if "UserPreferencesProto" not in line and "onboardingComplete_" not in line
            and "<init>" not in line
        )
        + "\n",
        expect_exit=1,
        expect_in_output="protobuf message(s) did not survive",
    )

    check(
        "INSTANCE renamed is caught",
        mapping=GOOD_MAPPING.replace("INSTANCE -> INSTANCE", "INSTANCE -> a"),
        expect_exit=1,
        expect_in_output="looked up by name",
    )

    check(
        "serializer() renamed is caught",
        mapping=GOOD_MAPPING.replace("serializer():0:0 -> serializer", "serializer():0:0 -> b"),
        expect_exit=1,
        expect_in_output="looked up by name",
    )

    check(
        "Companion renamed is caught",
        mapping=GOOD_MAPPING.replace("Companion -> Companion", "Companion -> c", 1),
        expect_exit=1,
        expect_in_output="looked up by name",
    )

    check(
        "a renamed protobuf field is caught",
        mapping=GOOD_MAPPING.replace(
            "onboardingComplete_ -> onboardingComplete_", "onboardingComplete_ -> a"
        ),
        expect_exit=1,
        expect_in_output="getDeclaredField",
    )

    check(
        "a line in class or member position that is not understood fails the run",
        mapping=GOOD_MAPPING + "this is not a mapping line\n",
        expect_exit=1,
        expect_in_output="were not understood",
    )

    check(
        "missing_rules.txt with a rule in it fails the run",
        missing_rules__txt="# Please add these rules\n-dontwarn com.example.Absent\n",
        expect_exit=1,
        expect_in_output="could not resolve",
    )

    check(
        "missing_rules.txt of comments only is not a failure",
        missing_rules__txt="# nothing to add\n",
        expect_exit=0,
    )

    check(
        "usage.txt naming a removed INSTANCE is caught",
        usage__txt=(
            "com.kojo.boilerplate.core.navigation.AppDestination$Home:\n"
            "    public static com.kojo.boilerplate.core.navigation.AppDestination$Home INSTANCE\n"
        ),
        expect_exit=1,
        expect_in_output="shrunk away",
    )

    check(
        "usage.txt that removes nothing relevant passes, and is reported as the evidence",
        usage__txt="com.kojo.boilerplate.Unused:\n    void gone()\n",
        expect_exit=0,
        expect_in_output="checked against: usage.txt",
    )

    check(
        "seeds.txt that no keep rule matched for a serializable object is caught",
        seeds__txt="com.kojo.boilerplate.core.datastore.proto.UserPreferencesProto: "
        "boolean onboardingComplete_\n",
        expect_exit=1,
        expect_in_output="matched by no keep rule",
    )

    check(
        "seeds.txt covering every required class passes",
        seeds__txt=(
            "com.kojo.boilerplate.core.navigation.AppDestination$Home: "
            "com.kojo.boilerplate.core.navigation.AppDestination$Home INSTANCE\n"
            "com.kojo.boilerplate.core.datastore.proto.UserPreferencesProto: "
            "boolean onboardingComplete_\n"
        ),
        expect_exit=0,
        expect_in_output="checked against: seeds.txt",
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
