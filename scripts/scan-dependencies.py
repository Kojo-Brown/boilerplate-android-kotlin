#!/usr/bin/env python3
"""Gate on known vulnerabilities in the dependencies that actually ship.

SPEC.md Phase 11, item 6 — the dependency half. The licence half is Licensee, configured in
`app/build.gradle.kts`; this script reads the report Licensee writes and asks a different
question of the same list: does any artifact in it have a published vulnerability?

The two halves share one resolution on purpose. `gradle/libs.versions.toml` declares about
seventy coordinates and the APK ships several hundred, because most of what is in an Android app
arrived transitively — Compose through a BOM, Guava's annotations through protobuf, the Play
services basement through ML Kit. A scanner fed the catalog would check the tenth of the graph
that somebody typed and report "no vulnerabilities" about the rest. Licensee resolves
`:app`'s debug runtime classpath and writes every external module in it to `artifacts.json`, so
that file is the shipped set, and it is the only input here.

## What is checked

1. **The report exists, parses, and is not empty.** A missing, truncated or empty
   `artifacts.json` fails the run. This is the check that matters most: every other failure mode
   of a scanner is loud, and "scanned nothing successfully" is the one that looks exactly like
   success.
2. **Every coordinate is complete.** A group, an artifact and a version. An artifact with no
   version cannot be matched against a vulnerability range, and silently skipping it would mean
   a gate whose coverage depends on what a POM happened to omit.
3. **Every coordinate is queried against OSV.dev**, in batches, by Maven ecosystem coordinate.
   Anything the database reports as affecting the exact resolved version fails the run unless it
   is named in the allowlist.
4. **The allowlist is checked in both directions.** An entry naming a vulnerability the scan did
   not find fails the run, for the same reason `config/r8/shrunk-away-allowlist.txt` is checked
   that way: an exception that outlives the fact it records is how a suppression becomes
   permanent. Entries also carry an expiry date and fail once it passes.
5. **An unreachable database fails.** A scanner that passes when it could not ask is worse than
   no scanner, because it produces a green check that means nothing.

## What it does not check

Build-time and test-only dependencies. Nothing in `testImplementation`, nothing on the Gradle
plugin classpath, nothing KSP runs: none of it is in the APK, and a CVE in a test double is not
a vulnerability in the app. They are not unexamined — every version is pinned in the catalog and
`dependency-resolution.yml` resolves all of them — but they are out of this gate's scope.

Severity is reported, never thresholded. A gate with a severity floor is a gate that argues
about CVSS scores instead of about whether to ship; anything the database knows about fails and
the allowlist is where a decision to ship anyway is written down, with a reason and a date.

Usage:
    scripts/scan-dependencies.py [--artifacts FILE ...] [--allowlist FILE]
                                 [--osv-base-url URL] [--timeout SECONDS] [--today YYYY-MM-DD]

Producing what it reads:

    ./gradlew :app:licenseeAndroidDebug
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from datetime import date, datetime
from pathlib import Path
from typing import Any

# `androidDebug` and not `debug`: for an Android project Licensee names both the task and the
# report directory after `"android" + variantName.capitalized()`.
DEFAULT_ARTIFACTS = Path("app/build/reports/licensee/androidDebug/artifacts.json")
DEFAULT_ALLOWLIST = Path("config/supply-chain/vulnerability-allowlist.txt")
DEFAULT_OSV_BASE_URL = "https://api.osv.dev"

# OSV's querybatch endpoint takes an arbitrary number of queries; a hundred at a time keeps each
# request small enough to retry cheaply and still means two or three round trips for a graph this
# size rather than several hundred.
QUERY_BATCH_SIZE = 100

# Three attempts with a widening pause. OSV answers 429 under load and the occasional 5xx, and
# both are worth retrying; a failure that survives all three is reported rather than swallowed.
HTTP_ATTEMPTS = 3
HTTP_BACKOFF_SECONDS = (2, 5)


@dataclass(frozen=True, order=True)
class Coordinate:
    """One external Maven module on the shipped runtime classpath."""

    group: str
    artifact: str
    version: str

    @property
    def package_name(self) -> str:
        """The name OSV uses for a Maven package."""
        return f"{self.group}:{self.artifact}"

    def __str__(self) -> str:
        return f"{self.group}:{self.artifact}:{self.version}"


@dataclass(frozen=True)
class AllowEntry:
    """A vulnerability this project has decided to ship with, until a date."""

    vulnerability_id: str
    package_name: str
    expires: date
    reason: str
    line_number: int


@dataclass(frozen=True)
class Finding:
    """A vulnerability OSV reports against a coordinate that is in the APK."""

    coordinate: Coordinate
    vulnerability_id: str
    summary: str
    severity: str
    aliases: tuple[str, ...]


class ScanError(Exception):
    """A condition that makes the scan's answer meaningless rather than merely negative."""


# --------------------------------------------------------------------------------------------
# Reading the inputs
# --------------------------------------------------------------------------------------------


def read_coordinates(paths: list[Path]) -> list[Coordinate]:
    """Read Licensee's `artifacts.json` reports into a deduplicated, sorted coordinate list."""
    coordinates: set[Coordinate] = set()

    for path in paths:
        if not path.is_file():
            raise ScanError(
                f"{path} does not exist. It is written by `./gradlew :app:licenseeAndroidDebug`; "
                "without it this scan has no dependency list and cannot report anything.",
            )

        try:
            report = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as error:
            raise ScanError(f"{path} is not valid JSON: {error}") from error

        if not isinstance(report, list):
            raise ScanError(
                f"{path} is {type(report).__name__}, not the JSON array of artifacts Licensee "
                "writes. Either the report is from a different tool or its format has changed.",
            )

        if not report:
            raise ScanError(
                f"{path} is an empty array: Licensee resolved no external dependencies. That is "
                "not a classpath this app can have, so the report is wrong rather than clean.",
            )

        for index, entry in enumerate(report):
            coordinates.add(_coordinate_from(entry, path, index))

    return sorted(coordinates)


def _coordinate_from(entry: Any, path: Path, index: int) -> Coordinate:
    where = f"{path} entry {index}"

    if not isinstance(entry, dict):
        raise ScanError(f"{where} is {type(entry).__name__}, not an object")

    fields = {}
    for key in ("groupId", "artifactId", "version"):
        value = entry.get(key)
        if not isinstance(value, str) or not value.strip():
            raise ScanError(
                f"{where} has no usable {key} ({value!r}). A coordinate without one cannot be "
                "matched against a vulnerability range, and skipping it would make this gate's "
                "coverage depend on what a POM left out.",
            )
        fields[key] = value.strip()

    return Coordinate(fields["groupId"], fields["artifactId"], fields["version"])


def read_licences(paths: list[Path]) -> tuple[dict[str, int], list[tuple[str, list[str]]]]:
    """Summarise the licences Licensee recorded. Never a failure condition here.

    Returns the per-licence counts and, separately, the artifacts that declare more than one
    licence. The second half exists because the first is otherwise quietly confusing: the counts
    are per *licence*, so they sum to more than the number of modules scanned whenever a POM
    carries two, and an unexplained 222-over-221 reads as a bug in this script. It also says
    something worth seeing — Licensee accepts an artifact when any one of its licences is allowed,
    so a dual-licensed module can pass on one that this project allows while declaring another it
    does not.

    The licence *decision* stays Licensee's — `violationAction(FAIL)` in app/build.gradle.kts —
    because duplicating it would mean two policies to keep in step. This is the same data
    summarised in the same log, so one run of the gate says what shipped and under what.
    """
    counts: dict[str, int] = {}
    multiple: list[tuple[str, list[str]]] = []

    for path in paths:
        if not path.is_file():
            continue
        try:
            report = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            return {}, []
        if not isinstance(report, list):
            return {}, []

        for entry in report:
            if not isinstance(entry, dict):
                continue
            names = [
                identifier
                for licence in entry.get("spdxLicenses") or []
                if isinstance(licence, dict)
                for identifier in [licence.get("identifier")]
                if isinstance(identifier, str)
            ]
            names += [
                f"non-SPDX: {url}"
                for licence in entry.get("unknownLicenses") or []
                if isinstance(licence, dict)
                for url in [licence.get("url") or licence.get("name") or "unnamed"]
                if isinstance(url, str)
            ]
            for name in names or ["none declared"]:
                counts[name] = counts.get(name, 0) + 1

            if len(names) > 1:
                coordinate = ":".join(
                    str(entry.get(key, "?")) for key in ("groupId", "artifactId", "version")
                )
                multiple.append((coordinate, names))

    return counts, sorted(multiple)


def read_allowlist(path: Path) -> list[AllowEntry]:
    """Parse the allowlist.

    Format, one entry per line, whitespace separated:

        <OSV or CVE id>  <group:artifact>  <expires YYYY-MM-DD>  <reason, to end of line>

    Every field is required, the reason included. An exception with no reason is indistinguishable
    from one nobody has looked at since.
    """
    if not path.is_file():
        raise ScanError(
            f"{path} does not exist. An empty allowlist is a file with no entries, not a missing "
            "file: a typo in the path would otherwise read as 'nothing is suppressed'.",
        )

    entries: list[AllowEntry] = []
    seen: dict[tuple[str, str], int] = {}

    for line_number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue

        fields = line.split(maxsplit=3)
        if len(fields) < 4:
            raise ScanError(
                f"{path}:{line_number}: expected "
                "`<vulnerability-id> <group:artifact> <expires YYYY-MM-DD> <reason>`, got: "
                f"{line!r}",
            )

        vulnerability_id, package_name, expires_text, reason = fields

        if package_name.count(":") != 1 or not all(package_name.split(":")):
            raise ScanError(
                f"{path}:{line_number}: {package_name!r} is not a `group:artifact` pair. The "
                "version is deliberately not part of an entry: a suppression that pinned one "
                "would silently stop applying the next time the dependency moved.",
            )

        try:
            expires = datetime.strptime(expires_text, "%Y-%m-%d").date()
        except ValueError as error:
            raise ScanError(
                f"{path}:{line_number}: {expires_text!r} is not a YYYY-MM-DD date: {error}",
            ) from error

        if not reason.strip():
            raise ScanError(f"{path}:{line_number}: the reason is empty")

        key = (vulnerability_id.upper(), package_name)
        if key in seen:
            raise ScanError(
                f"{path}:{line_number}: {vulnerability_id} for {package_name} is already "
                f"allowed on line {seen[key]}",
            )
        seen[key] = line_number

        entries.append(
            AllowEntry(
                vulnerability_id=vulnerability_id,
                package_name=package_name,
                expires=expires,
                reason=reason.strip(),
                line_number=line_number,
            ),
        )

    return entries


# --------------------------------------------------------------------------------------------
# Asking OSV
# --------------------------------------------------------------------------------------------


def _post_json(url: str, payload: dict[str, Any], timeout: float) -> dict[str, Any]:
    body = json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=body,
        headers={"Content-Type": "application/json", "Accept": "application/json"},
        method="POST",
    )
    return _send(request, url, timeout)


def _get_json(url: str, timeout: float) -> dict[str, Any]:
    request = urllib.request.Request(
        url,
        headers={"Accept": "application/json"},
        method="GET",
    )
    return _send(request, url, timeout)


def _send(request: urllib.request.Request, url: str, timeout: float) -> dict[str, Any]:
    last_error: Exception | None = None

    for attempt in range(HTTP_ATTEMPTS):
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                decoded = json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as error:
            # 4xx other than 429 will not change on a retry; a 429 or a 5xx will.
            last_error = error
            if error.code != 429 and error.code < 500:
                break
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, OSError) as error:
            last_error = error
        else:
            if not isinstance(decoded, dict):
                raise ScanError(f"{url} answered {type(decoded).__name__}, not a JSON object")
            return decoded

        if attempt < len(HTTP_BACKOFF_SECONDS):
            time.sleep(HTTP_BACKOFF_SECONDS[attempt])

    raise ScanError(
        f"could not reach {url} after {HTTP_ATTEMPTS} attempts: {last_error}. This is a failure "
        "and not a pass: the scan has no answer, so reporting one would be a green check that "
        "means nothing.",
    )


def query_vulnerabilities(
    coordinates: list[Coordinate],
    base_url: str,
    timeout: float,
) -> dict[Coordinate, list[str]]:
    """Return the vulnerability ids OSV reports for each coordinate that has any."""
    hits: dict[Coordinate, list[str]] = {}
    endpoint = f"{base_url.rstrip('/')}/v1/querybatch"

    for start in range(0, len(coordinates), QUERY_BATCH_SIZE):
        batch = coordinates[start : start + QUERY_BATCH_SIZE]
        payload = {
            "queries": [
                {
                    "package": {"ecosystem": "Maven", "name": coordinate.package_name},
                    "version": coordinate.version,
                }
                for coordinate in batch
            ],
        }

        response = _post_json(endpoint, payload, timeout)
        results = response.get("results")

        # The results array is positional: result n answers query n. A response of a different
        # length cannot be lined up with the questions, and guessing an alignment is how a
        # vulnerability gets attributed to the wrong artifact.
        if not isinstance(results, list) or len(results) != len(batch):
            raise ScanError(
                f"{endpoint} answered {len(results) if isinstance(results, list) else 'no'} "
                f"results for {len(batch)} queries; they cannot be matched up.",
            )

        for coordinate, result in zip(batch, results):
            vulns = (result or {}).get("vulns") or []
            ids = [
                vuln["id"]
                for vuln in vulns
                if isinstance(vuln, dict) and isinstance(vuln.get("id"), str)
            ]
            if ids:
                hits[coordinate] = sorted(set(ids))

    return hits


def describe_vulnerability(
    vulnerability_id: str,
    base_url: str,
    timeout: float,
) -> dict[str, Any]:
    """Fetch one vulnerability's record. `querybatch` returns ids only."""
    return _get_json(f"{base_url.rstrip('/')}/v1/vulns/{vulnerability_id}", timeout)


def _severity_of(record: dict[str, Any]) -> str:
    """A human label for a severity, from whichever of OSV's two places carries one.

    GHSA records put a word in `database_specific.severity`; everything else carries a CVSS
    vector in `severity[]`, which is not worth turning into a number here — the gate does not
    threshold on it, so the vector itself is the most useful thing to print.
    """
    database_specific = record.get("database_specific")
    if isinstance(database_specific, dict):
        label = database_specific.get("severity")
        if isinstance(label, str) and label.strip():
            return label.strip().upper()

    severities = record.get("severity")
    if isinstance(severities, list):
        for severity in severities:
            if isinstance(severity, dict) and isinstance(severity.get("score"), str):
                return severity["score"]

    return "UNRATED"


def collect_findings(
    hits: dict[Coordinate, list[str]],
    base_url: str,
    timeout: float,
) -> list[Finding]:
    """Turn ids into findings, dropping the ones OSV has withdrawn."""
    records: dict[str, dict[str, Any]] = {}
    findings: list[Finding] = []

    for coordinate in sorted(hits):
        for vulnerability_id in hits[coordinate]:
            if vulnerability_id not in records:
                records[vulnerability_id] = describe_vulnerability(
                    vulnerability_id,
                    base_url,
                    timeout,
                )
            record = records[vulnerability_id]

            # A withdrawn record is one the database has retracted. Failing on it would mean a
            # build that goes red for an advisory its own source no longer stands behind.
            if record.get("withdrawn"):
                continue

            summary = record.get("summary") or record.get("details") or "no summary published"
            findings.append(
                Finding(
                    coordinate=coordinate,
                    vulnerability_id=vulnerability_id,
                    summary=" ".join(str(summary).split())[:200],
                    severity=_severity_of(record),
                    aliases=tuple(
                        alias for alias in record.get("aliases") or [] if isinstance(alias, str)
                    ),
                ),
            )

    return findings


# --------------------------------------------------------------------------------------------
# Deciding
# --------------------------------------------------------------------------------------------


def _matches(entry: AllowEntry, finding: Finding) -> bool:
    identifiers = {finding.vulnerability_id.upper()} | {
        alias.upper() for alias in finding.aliases
    }
    return (
        entry.vulnerability_id.upper() in identifiers
        and entry.package_name == finding.coordinate.package_name
    )


def evaluate(
    findings: list[Finding],
    allowlist: list[AllowEntry],
    today: date,
    allowlist_path: Path = DEFAULT_ALLOWLIST,
) -> tuple[list[str], list[str]]:
    """Return (violations, notes). A non-empty violations list fails the run."""
    violations: list[str] = []
    notes: list[str] = []

    used: set[AllowEntry] = set()

    for finding in sorted(findings, key=lambda f: (f.coordinate, f.vulnerability_id)):
        matching = [entry for entry in allowlist if _matches(entry, finding)]
        alias_text = f" (aka {', '.join(finding.aliases)})" if finding.aliases else ""
        headline = (
            f"{finding.coordinate}: {finding.vulnerability_id}{alias_text} "
            f"[{finding.severity}] {finding.summary}"
        )

        if not matching:
            violations.append(headline)
            continue

        used.update(matching)
        expired = [entry for entry in matching if entry.expires < today]
        if expired:
            entry = expired[0]
            violations.append(
                f"{headline}\n      allowed on {allowlist_path}:{entry.line_number} but that "
                f"entry expired on {entry.expires.isoformat()}. Re-check whether the dependency "
                "has a fixed release; if it still has not, move the date deliberately and say "
                "why.",
            )
        else:
            entry = matching[0]
            notes.append(
                f"{headline}\n      allowed until {entry.expires.isoformat()}: {entry.reason}",
            )

    for entry in allowlist:
        if entry not in used:
            violations.append(
                f"{entry.vulnerability_id} for {entry.package_name} is allowed on line "
                f"{entry.line_number} but the scan did not find it. Remove the entry — a "
                "suppression that outlives the thing it suppressed is how one becomes permanent.",
            )

    return violations, notes


# --------------------------------------------------------------------------------------------
# Entry point
# --------------------------------------------------------------------------------------------


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Fail if a dependency that ships in the APK has a published vulnerability.",
    )
    parser.add_argument(
        "--artifacts",
        type=Path,
        nargs="+",
        default=[DEFAULT_ARTIFACTS],
        help=f"Licensee artifact report(s) to scan (default: {DEFAULT_ARTIFACTS})",
    )
    parser.add_argument(
        "--allowlist",
        type=Path,
        default=DEFAULT_ALLOWLIST,
        help=f"vulnerabilities this project ships with anyway (default: {DEFAULT_ALLOWLIST})",
    )
    parser.add_argument(
        "--osv-base-url",
        default=DEFAULT_OSV_BASE_URL,
        help=f"OSV API root (default: {DEFAULT_OSV_BASE_URL})",
    )
    parser.add_argument(
        "--timeout",
        type=float,
        default=30.0,
        help="per-request timeout in seconds (default: 30)",
    )
    parser.add_argument(
        "--today",
        default=None,
        help="override today's date (YYYY-MM-DD) when checking allowlist expiry",
    )
    arguments = parser.parse_args()

    try:
        today = (
            datetime.strptime(arguments.today, "%Y-%m-%d").date()
            if arguments.today
            else date.today()
        )
    except ValueError as error:
        print(f"ERROR: --today {arguments.today!r} is not a YYYY-MM-DD date: {error}")
        return 1

    try:
        coordinates = read_coordinates(arguments.artifacts)
        allowlist = read_allowlist(arguments.allowlist)

        print(
            f"Scanning {len(coordinates)} external modules from "
            f"{', '.join(str(path) for path in arguments.artifacts)}",
        )

        licences, multiple = read_licences(arguments.artifacts)
        if licences:
            print("Licences Licensee recorded for them:")
            for name, count in sorted(licences.items(), key=lambda item: (-item[1], item[0])):
                print(f"  {count:4d}  {name}")
            # Why the counts above can sum to more than the module count. Without this the
            # difference looks like an arithmetic bug in this script rather than a POM with two
            # licences in it.
            if multiple:
                print(
                    f"{len(multiple)} of them declare more than one licence, which is why the "
                    "counts above sum to more than the module count:",
                )
                for coordinate, names in multiple:
                    print(f"  {coordinate}: {', '.join(names)}")

        hits = query_vulnerabilities(coordinates, arguments.osv_base_url, arguments.timeout)
        findings = collect_findings(hits, arguments.osv_base_url, arguments.timeout)
    except ScanError as error:
        print(f"ERROR: {error}")
        return 1

    violations, notes = evaluate(findings, allowlist, today, arguments.allowlist)

    print()
    if notes:
        print(f"{len(notes)} known vulnerability(ies) allowed by {arguments.allowlist}:")
        for note in notes:
            print(f"  - {note}")
        print()

    if violations:
        print(f"FAIL: {len(violations)} unaccepted finding(s):")
        for violation in violations:
            print(f"  - {violation}")
        print()
        print(
            "Fix forward: raise the dependency to a fixed release if one exists. If none does "
            f"and shipping anyway is the deliberate choice, add it to {arguments.allowlist} "
            "with an expiry and a reason.",
        )
        return 1

    print(
        f"OK: {len(coordinates)} modules scanned against OSV, "
        f"{len(notes)} allowed, 0 unaccepted findings",
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
