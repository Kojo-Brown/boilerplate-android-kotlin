#!/usr/bin/env python3
"""Gate on what R8 actually did, not on what `app/proguard-rules.pro` says.

SPEC.md Phase 11, item 4. R8 runs in full mode (`android.enableR8.fullMode` in
gradle.properties), which strips more than the default: generic signatures and annotations go
from classes that are not kept, default constructors are not kept for classes only ever
instantiated by reflection, and an interface with no visible implementation is assumed to have
none. What holds the app together across that is a handful of keep rules — and a keep rule is
the one kind of configuration that cannot fail. R8 does not warn about a `-keep` whose pattern
names no class, so a package that was renamed, a member that never existed, or a rule that a
dependency bump made necessary and nobody added, all leave a build that is green and an app that
crashes the first time something reflects. This repository has had two of those three at once:
`app/proguard-rules.pro` kept `com.kojo.boilerplate.navigation.**`, a package that has never
existed here, and `CREATOR` fields on `**$$serializer` classes, which have none.

So this reads R8's own report of the shrunk output and checks the rules by their effect.

## What is checked

1. **The mapping file exists and parses.** Anything in class or member position this script
   cannot classify fails the run — an audit whose parser quietly skipped half the file reports
   "nothing to report", which is indistinguishable from success.
2. **Obfuscation actually happened.** At least one of the app's own classes must have been
   renamed. `isMinifyEnabled = false` on the build type, or a stray `-dontobfuscate`, otherwise
   produces a complete mapping file of identity entries and every check below passes.
3. **Every `@Serializable` type the app declares survived**, and so did every message class the
   `.proto` schema generates. The expected sets are read out of the Kotlin and `.proto` sources
   rather than listed here, because a list is what stops covering the type somebody adds next
   month — which is the exact failure the deleted navigation rule was. A type that is genuinely
   unreachable, and so genuinely right to remove, is named in
   `config/r8/shrunk-away-allowlist.txt` with the reason; that file is checked in both
   directions, so an entry cannot outlive the fact it records.
4. **Nothing that has to keep its name was renamed.** `INSTANCE` and `serializer` on a
   serializable declaration, `Companion`, and protobuf's trailing-underscore fields. This
   direction is always decidable from the mapping file: R8 has to record a rename, or `retrace`
   would produce wrong answers.
5. **R8 resolved every reference it was given.** A `missing_rules.txt` with anything in it means
   R8 could not find a class something referenced and guessed.

## What it cannot check

Member *removal*, unless R8 wrote a `seeds.txt` or a `usage.txt` next to the mapping — the
mapping file lists what came out, so a field that was stripped is simply absent, and absent is
also what an unlisted identity mapping looks like. When either file is there it is used and the
check is exact. The summary says which of the two the run had, so the reach of this gate is in
the log rather than assumed. Class removal (check 3) needs neither file.

Usage:  scripts/verify-r8-mapping.py [--root DIR] [--mapping-dir DIR] [--allowlist FILE]

Producing what it reads:

    ./gradlew :app:assembleMinified

`minified` rather than `release`: R8 only runs on a shrunk variant, and `release` cannot be
built in this repository while the certificate pins and the Play Integrity project are
deliberately empty. app/build.gradle.kts has the reasoning next to the build type.
"""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

DEFAULT_MAPPING_DIR = Path("app/build/outputs/mapping/minified")

# Classes the checks below would require to survive, and that R8 is right to remove. Every entry
# is dead code in the shipped artifact, with the reason it is dead written next to it.
DEFAULT_ALLOWLIST = Path("config/r8/shrunk-away-allowlist.txt")

# Where the app's package prefix is declared. Read rather than repeated: this script decides
# which classes count as "the app's own" from it, and a second spelling would diverge silently.
CONVENTIONS = Path(
    "build-logic/convention/src/main/kotlin/com/kojo/boilerplate/buildlogic/Conventions.kt"
)

# Members whose *names* the keep rules exist to preserve. kotlinx.serialization looks
# `serializer` up on the declaration and reads `INSTANCE` for a serializable object;
# `Companion` is how it reaches the serializer of a serializable class.
NAME_CRITICAL_MEMBERS = ("INSTANCE", "serializer", "Companion")

PROTOBUF_MESSAGE_BASE = "com.google.protobuf.GeneratedMessageLite"


# --- The mapping file ---------------------------------------------------------------------------


@dataclass
class ClassMapping:
    """One `original -> obfuscated:` entry and the member lines indented under it."""

    original: str
    obfuscated: str
    fields: dict[str, str] = field(default_factory=dict)
    methods: dict[str, str] = field(default_factory=dict)

    @property
    def renamed(self) -> bool:
        return self.original != self.obfuscated


# `com.example.Foo -> a.b.c:`
#
# Deliberately `\S+` on both sides rather than a character class. A JVM name can hold more than
# anyone enumerates from memory, and the first run of this script against a real mapping proved
# it: Kotlin mangles a lambda that captures an inline-class value into
# `ClickableKt$clickable-O2vRcR0$$inlined$…`, whose hyphen a `[\w.$]` class rejects. The cost of
# getting a class line wrong is not one line — `current` goes to `None` and every member indented
# under it is unattributed too, which is how 20 unreadable class lines became 2354 complaints.
# An unindented line ending in a colon with ` -> ` in the middle is a class mapping; nothing else
# in the format looks like that.
CLASS_LINE = re.compile(r"^(?P<original>\S+) -> (?P<obfuscated>\S+):$")

# `    1:7:void onCreate(android.os.Bundle):41:47 -> a`  /  `    int count -> b`
#
# The leading `<line>:<line>:` pair and the trailing `:<line>[:<line>]` are present on methods
# only, and only when line numbers survived, so both are optional. Names are `[^\s(]+` and types
# `\S+` for the reason above; a member line holds no spaces except the two around the arrow and
# the one after the type, so this stays unambiguous. The name may contain dots, because an
# inlined frame names its *original* holder there — `1:1:void other.Klass.method():7:7 -> a`.
MEMBER_LINE = re.compile(
    r"^\s+(?:\d+:\d+:)?"
    r"(?P<type>\S+) "
    r"(?P<name>[^\s(]+)"
    r"(?P<arguments>\([^)]*\))?"
    r"(?::\d+(?::\d+)?)? -> (?P<obfuscated>\S+)$"
)


def parse_mapping(text: str) -> tuple[dict[str, ClassMapping], list[str]]:
    """Read a mapping file into class entries, reporting every line it could not classify."""
    classes: dict[str, ClassMapping] = {}
    unparsed: list[str] = []
    current: ClassMapping | None = None

    for number, raw in enumerate(text.splitlines(), start=1):
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue

        if not raw[0].isspace():
            match = CLASS_LINE.match(raw.strip())
            if match is None:
                unparsed.append(f"{number}: {raw.strip()}")
                current = None
                continue
            current = ClassMapping(match["original"], match["obfuscated"])
            # R8 emits one entry per class. A duplicate would mean this parser has mistaken a
            # member line for a class line, so it is reported rather than merged.
            if current.original in classes:
                unparsed.append(f"{number}: duplicate class entry {current.original}")
            classes[current.original] = current
            continue

        match = MEMBER_LINE.match(raw)
        if match is None or current is None:
            unparsed.append(f"{number}: {raw.strip()}")
            continue
        if "." in match["name"]:
            # An inlined frame: the line describes a method of *another* class that R8 inlined
            # into this one, so it says nothing about this class's own members and recording it
            # here would attribute the other class's name to this one.
            continue
        target = current.methods if match["arguments"] is not None else current.fields
        target[match["name"]] = match["obfuscated"]

    return classes, unparsed


# --- What the sources say has to survive -------------------------------------------------------


@dataclass
class Declaration:
    """A `@Serializable` declaration, by the JVM name R8 would print for it."""

    jvm_name: str
    kind: str
    source: Path


DECLARATION = re.compile(
    r"^\s*(?:(?:data|value|sealed|abstract|open|inner|private|internal|public|expect|actual)\s+)*"
    r"(?P<kind>class|object|interface)\s+(?P<name>\w+)"
)
PACKAGE = re.compile(r"^package\s+(?P<package>[\w.]+)")


def serializable_declarations(root: Path) -> tuple[list[Declaration], list[str]]:
    """Every `@Serializable` declaration in `src/main`, with the nesting R8 will print.

    Nesting is tracked by brace depth, which is what makes `AppDestination.SignIn` come out as
    `AppDestination$SignIn` rather than as a top-level name that appears in no mapping file.
    Every `@Serializable` in a file is accounted for or the file is reported: an annotation this
    cannot attribute to a declaration is a type this gate would silently stop covering.
    """
    declarations: list[Declaration] = []
    problems: list[str] = []

    for source in sorted(root.glob("*/src/main/kotlin/**/*.kt")) + sorted(
        root.glob("*/*/src/main/kotlin/**/*.kt")
    ):
        text = source.read_text(encoding="utf-8")
        if "@Serializable" not in text:
            continue

        package_match = PACKAGE.search(text)
        if package_match is None:
            problems.append(f"{source}: no package declaration")
            continue
        package = package_match["package"]

        stack: list[str | None] = []
        pending_annotation = False
        pending_type: str | None = None
        found = 0

        for raw in text.splitlines():
            line = raw.split("//", 1)[0]
            stripped = line.strip()

            if stripped.startswith("@Serializable"):
                pending_annotation = True
                # `@Serializable data class Foo(…)` on one line is legal Kotlin, so drop the
                # annotation and keep reading the same line rather than skipping it.
                stripped = re.sub(r"^@Serializable(\([^)]*\))?\s*", "", stripped)

            match = DECLARATION.match(stripped)
            if match is not None:
                name = match["name"]
                enclosing = [part for part in stack if part is not None]
                if pending_annotation:
                    declarations.append(
                        Declaration(
                            jvm_name=f"{package}." + "$".join([*enclosing, name]),
                            kind=match["kind"],
                            source=source,
                        )
                    )
                    found += 1
                    pending_annotation = False
                pending_type = name

            for character in line:
                if character == "{":
                    stack.append(pending_type)
                    pending_type = None
                elif character == "}":
                    if stack:
                        stack.pop()

        expected = len(re.findall(r"^\s*@Serializable\b", text, flags=re.MULTILINE))
        if found != expected:
            problems.append(
                f"{source}: {expected} @Serializable annotation(s) but {found} declaration(s) "
                "attributed. The parser in serializable_declarations did not understand this "
                "file, so the types in it would go unchecked."
            )

    return declarations, problems


PROTO_PACKAGE = re.compile(r'^option\s+java_package\s*=\s*"(?P<package>[\w.]+)"\s*;')
PROTO_MULTIPLE_FILES = re.compile(r"^option\s+java_multiple_files\s*=\s*(?P<value>true|false)\s*;")
PROTO_OUTER = re.compile(r'^option\s+java_outer_classname\s*=\s*"(?P<name>\w+)"\s*;')
PROTO_MESSAGE = re.compile(r"^message\s+(?P<name>\w+)\s*\{")


def proto_message_classes(root: Path) -> tuple[list[str], list[str]]:
    """The Java class names protoc will generate for every `message` in the schema.

    These are the `GeneratedMessageLite` subclasses whose fields the protobuf keep rule holds.
    Derived from the `.proto` rather than listed, for the same reason as the declarations above.
    """
    names: list[str] = []
    problems: list[str] = []

    for source in sorted(root.glob("*/*/src/main/proto/*.proto")) + sorted(
        root.glob("*/src/main/proto/*.proto")
    ):
        text = source.read_text(encoding="utf-8")
        lines = [raw.split("//", 1)[0].strip() for raw in text.splitlines()]

        package = next(
            (m["package"] for m in (PROTO_PACKAGE.match(line) for line in lines) if m), None
        )
        if package is None:
            problems.append(f"{source}: no `option java_package`, so the generated names are a "
                            "guess. Declare it.")
            continue

        multiple = next(
            (m["value"] for m in (PROTO_MULTIPLE_FILES.match(line) for line in lines) if m), None
        )
        outer = next((m["name"] for m in (PROTO_OUTER.match(line) for line in lines) if m), None)
        messages = [m["name"] for m in (PROTO_MESSAGE.match(line) for line in lines) if m]

        if not messages:
            problems.append(f"{source}: no `message` declarations found — did the parser miss "
                            "them?")
            continue

        if multiple == "true":
            names += [f"{package}.{name}" for name in messages]
        elif outer is not None:
            names += [f"{package}.{outer}${name}" for name in messages]
        else:
            problems.append(
                f"{source}: `java_multiple_files` is not true and there is no "
                "`java_outer_classname`, so protoc's class names cannot be derived here."
            )

    return names, problems


def read_allowlist(path: Path) -> tuple[dict[str, str], list[str]]:
    """Parse the allowlist, and reject an entry that does not say why it is there.

    A bare name is how an allowlist becomes permanent: nobody can tell later whether the
    exemption was a considered fact about the app or the quickest way past a red build.
    """
    entries: dict[str, str] = {}
    errors: list[str] = []
    if not path.is_file():
        return entries, errors

    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        parts = raw.split("#", 1)
        body, reason = parts[0].strip(), (parts[1].strip() if len(parts) > 1 else "")
        if not body:
            continue
        if len(body.split()) != 1:
            errors.append(
                f"{path}:{number}: expected `fully.qualified.ClassName  # reason`, got {raw!r}"
            )
            continue
        if not reason:
            errors.append(f"{path}:{number}: {body} has no `# reason`")
            continue
        entries[body] = reason
    return entries, errors


def check_allowlist_is_current(
    classes: dict[str, ClassMapping],
    allowed: dict[str, str],
    required: list[str],
    path: Path,
) -> list[str]:
    """Both directions, so the allowlist cannot quietly stop describing the app.

    An entry whose class is in the shrunk output is a waiver for something that no longer needs
    one; an entry the checks never ask about is a waiver for something that no longer exists.
    """
    failures: list[str] = []
    for name in sorted(allowed):
        if name in classes:
            failures.append(
                f"    {name} is in the shrunk output, so its entry in {path} is stale. Delete "
                "the line — the check it waived now applies."
            )
        elif name not in required:
            failures.append(
                f"    {name} is not a type this gate requires to survive, so its entry in {path} "
                "covers nothing. Delete the line, or fix the name if the class was renamed."
            )
    return failures


def app_package_prefix(root: Path) -> tuple[str, list[str]]:
    path = root / CONVENTIONS
    if not path.is_file():
        return "", [f"{CONVENTIONS} is missing, so the app's package prefix cannot be read."]
    match = re.search(r'NAMESPACE_PREFIX\s*=\s*"(?P<prefix>[\w.]+)"', path.read_text())
    if match is None:
        return "", [f"{CONVENTIONS} no longer declares NAMESPACE_PREFIX."]
    return match["prefix"], []


# --- The optional reports ----------------------------------------------------------------------


def removed_members(mapping_dir: Path) -> tuple[dict[str, set[str]] | None, str]:
    """What R8 reports as removed, per class, from whichever report it wrote.

    `usage.txt` lists what was shrunk away; `seeds.txt` lists what the keep rules matched. Either
    answers "was this member removed?"; neither is guaranteed to be there. Returns `None` when
    the question cannot be answered, and always the name of the source used.
    """
    usage = mapping_dir / "usage.txt"
    if usage.is_file():
        removed: dict[str, set[str]] = {}
        current = ""
        for raw in usage.read_text(encoding="utf-8").splitlines():
            if not raw.strip():
                continue
            if not raw[0].isspace():
                current = raw.strip().rstrip(":")
                removed.setdefault(current, set())
            elif current:
                # `    public static com.example.Foo INSTANCE` — the member's name is the last
                # token before any argument list.
                head = raw.strip().split("(", 1)[0]
                removed[current].add(head.split()[-1] if head.split() else "")
        return removed, "usage.txt"
    return None, "neither usage.txt nor seeds.txt"


def kept_seeds(mapping_dir: Path) -> set[str] | None:
    """The classes `seeds.txt` says a keep rule matched, when R8 wrote one.

    A `seeds.txt` line is either a class on its own — matched by a `-keep` — or
    `<class>: <member>`, matched by a `-keepclassmembers`. Only the owning class is collected:
    the question this answers is "did any rule match here at all", which is the failure mode a
    keep rule has. Which member it matched is the mapping file's department.
    """
    seeds = mapping_dir / "seeds.txt"
    if not seeds.is_file():
        return None
    return {
        line.strip().split(":", 1)[0].strip()
        for line in seeds.read_text(encoding="utf-8").splitlines()
        if line.strip()
    }


# --- The checks --------------------------------------------------------------------------------


def check_missing_rules(mapping_dir: Path) -> list[str]:
    path = mapping_dir / "missing_rules.txt"
    if not path.is_file():
        return []
    rules = [
        line.strip()
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.strip().startswith("#")
    ]
    if not rules:
        return []
    return [
        f"{path} names {len(rules)} reference(s) R8 could not resolve. Every one of them is a "
        "class something in the app or a dependency mentions and R8 could not find, which it "
        "then has to guess about. Add the rule, or the dependency it is missing from:",
        *[f"    {rule}" for rule in rules],
    ]


def check_obfuscated(classes: dict[str, ClassMapping], prefix: str) -> list[str]:
    app_classes = [entry for entry in classes.values() if entry.original.startswith(prefix)]
    if not app_classes:
        return [
            f"the mapping file holds no class under {prefix}, so either the wrong variant was "
            "built or the app's own code was shrunk away entirely."
        ]
    if not any(entry.renamed for entry in app_classes):
        return [
            f"not one of the {len(app_classes)} classes under {prefix} was renamed. The build "
            "type is not minifying, or a -dontobfuscate reached the configuration: every check "
            "below would pass against an unshrunk mapping."
        ]
    return []


def check_survived(
    classes: dict[str, ClassMapping],
    required: list[str],
    what: str,
    allowed: dict[str, str],
    path: Path,
) -> list[str]:
    missing = [name for name in required if name not in classes and name not in allowed]
    if not missing:
        return []
    return [
        f"{len(missing)} {what} did not survive shrinking. A keep rule that stopped matching "
        f"looks exactly like this. If the class is genuinely unreachable, say so in {path}:",
        *[f"    {name}" for name in sorted(missing)],
    ]


def check_names_kept(
    classes: dict[str, ClassMapping],
    required: list[str],
    names: tuple[str, ...],
) -> list[str]:
    failures: list[str] = []
    for name in required:
        entry = classes.get(name)
        if entry is None:
            continue
        for member, obfuscated in {**entry.fields, **entry.methods}.items():
            if member in names and obfuscated != member:
                failures.append(
                    f"    {name}.{member} was renamed to {obfuscated}; it is looked up by name "
                    "at runtime"
                )
    if not failures:
        return []
    return ["a member that has to keep its name did not:", *failures]


def check_protobuf_fields(classes: dict[str, ClassMapping], required: list[str]) -> list[str]:
    failures: list[str] = []
    for name in required:
        entry = classes.get(name)
        if entry is None:
            continue
        for member, obfuscated in entry.fields.items():
            if member.endswith("_") and obfuscated != member:
                failures.append(f"    {name}.{member} was renamed to {obfuscated}")
    if not failures:
        return []
    return [
        "a generated protobuf field was renamed. `MessageSchema` resolves these names with "
        "getDeclaredField the first time a message is read or written, so this throws on the "
        "first preference read:",
        *failures,
    ]


def check_not_removed(
    removed: dict[str, set[str]] | None,
    seeds: set[str] | None,
    required: list[str],
    names: tuple[str, ...],
    allowed: dict[str, str],
) -> list[str]:
    if removed is None and seeds is None:
        return []
    failures: list[str] = []
    for name in (entry for entry in required if entry not in allowed):
        if removed is not None:
            for member in sorted(removed.get(name, set()) & set(names)):
                failures.append(f"    {name}.{member} was shrunk away (usage.txt)")
            if name in removed and not removed[name]:
                failures.append(f"    {name} was shrunk away entirely (usage.txt)")
        if seeds is not None and name not in seeds:
            failures.append(f"    {name} is matched by no keep rule (seeds.txt)")
    if not failures:
        return []
    return ["a declaration the app reflects over is not being kept:", *failures]


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path.cwd(), help="repository root")
    parser.add_argument(
        "--mapping-dir",
        type=Path,
        default=None,
        help=f"R8's output directory (default: {DEFAULT_MAPPING_DIR})",
    )
    parser.add_argument(
        "--allowlist",
        type=Path,
        default=None,
        help=f"classes that are allowed not to survive (default: {DEFAULT_ALLOWLIST})",
    )
    arguments = parser.parse_args(argv)

    root: Path = arguments.root
    mapping_dir: Path = arguments.mapping_dir or root / DEFAULT_MAPPING_DIR
    allowlist_path: Path = arguments.allowlist or root / DEFAULT_ALLOWLIST

    mapping_file = mapping_dir / "mapping.txt"
    if not mapping_file.is_file():
        print(f"FAIL: {mapping_file} does not exist.", file=sys.stderr)
        print(
            "      Build the shrunk variant first: ./gradlew :app:assembleMinified",
            file=sys.stderr,
        )
        return 1

    classes, unparsed = parse_mapping(mapping_file.read_text(encoding="utf-8"))
    failures: list[str] = []

    if unparsed:
        failures += [
            f"{len(unparsed)} line(s) of {mapping_file} were not understood. Every one of them "
            "is a class or member this gate did not look at:",
            *[f"    {line}" for line in unparsed[:20]],
        ]
    if not classes:
        failures.append(f"{mapping_file} holds no class entries at all.")

    prefix, problems = app_package_prefix(root)
    failures += problems

    declarations, problems = serializable_declarations(root)
    failures += problems
    if not declarations and not problems:
        failures.append(
            "no @Serializable declaration was found in any module's src/main. The app has "
            "several, so this gate is reading the wrong tree or the parser has stopped working."
        )

    proto_classes, problems = proto_message_classes(root)
    failures += problems

    allowed, problems = read_allowlist(allowlist_path)
    failures += problems

    serializable_names = [declaration.jvm_name for declaration in declarations]
    objects = [d.jvm_name for d in declarations if d.kind == "object"]
    required = serializable_names + proto_classes

    if prefix:
        failures += check_obfuscated(classes, prefix)
    failures += check_missing_rules(mapping_dir)
    failures += check_survived(
        classes, serializable_names, "@Serializable type(s)", allowed, allowlist_path
    )
    failures += check_survived(
        classes, proto_classes, "generated protobuf message(s)", allowed, allowlist_path
    )
    failures += check_names_kept(classes, serializable_names, NAME_CRITICAL_MEMBERS)
    failures += check_protobuf_fields(classes, proto_classes)

    stale = check_allowlist_is_current(classes, allowed, required, allowlist_path)
    if stale:
        failures += [f"{len(stale)} allowlist entry/entries no longer describe the app:", *stale]

    removed, evidence = removed_members(mapping_dir)
    seeds = kept_seeds(mapping_dir)
    if seeds is not None:
        evidence = "seeds.txt" if removed is None else "usage.txt + seeds.txt"
    failures += check_not_removed(
        removed, seeds, objects + proto_classes, NAME_CRITICAL_MEMBERS, allowed
    )

    renamed = sum(1 for entry in classes.values() if entry.renamed)
    print(f"Read {mapping_file}")
    print(f"  {len(classes)} classes in the shrunk output, {renamed} renamed")
    print(f"  {len(serializable_names)} @Serializable type(s) required to survive "
          f"({len(objects)} of them objects)")
    print(f"  {len(proto_classes)} generated protobuf message(s) required to survive")
    print(f"  {len(allowed)} allowed not to, per {allowlist_path}")
    print(f"  member removal checked against: {evidence}")
    if removed is None and seeds is None:
        print("  (R8 wrote neither report, so removal of a *member* is not covered by this run; "
              "class survival and every rename above are.)")

    if failures:
        print(file=sys.stderr)
        print(f"FAIL: {len(failures)} problem(s) with the shrunk output:", file=sys.stderr)
        for line in failures:
            print(f"  {line}" if not line.startswith("    ") else line, file=sys.stderr)
        print(file=sys.stderr)
        print("docs/r8.md explains each rule and what it is holding up.", file=sys.stderr)
        return 1

    print("OK: every rule in app/proguard-rules.pro still matches what it was written for.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
