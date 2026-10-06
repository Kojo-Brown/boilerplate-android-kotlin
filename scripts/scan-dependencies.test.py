#!/usr/bin/env python3
"""Tests for scripts/scan-dependencies.py.

The happy path runs on every CI build and proves almost nothing: a graph with no vulnerabilities
in it exercises the one branch where the scanner has nothing to say. What that script exists for
is the other branches — a finding that must fail the build, an allowlist entry that must stop
failing it, an expiry that must start failing it again, and the two ways a scanner can report
success without having scanned anything. Each case below writes a fixture report, serves OSV's
API from a local HTTP server with canned answers, runs the real script against it, and checks the
exit code and the message.

Nothing here touches the network or the real OSV database. That is deliberate twice over: a test
that queried api.osv.dev would change its answer whenever an advisory was published, and the
scheduled agent's environment cannot reach it at all — CONNECT to api.osv.dev is refused there,
as it is to dl.google.com, which is why none of the Gradle gates in this repository can run
locally either.

Usage: scripts/scan-dependencies.test.py
"""

from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCAN = HERE / "scan-dependencies.py"

ARTIFACTS_PATH = Path("app/build/reports/licensee/debug/artifacts.json")
ALLOWLIST_PATH = Path("config/supply-chain/vulnerability-allowlist.txt")

# A plausible slice of what Licensee writes for :app's debug runtime classpath: the fields this
# scanner reads, plus the licence fields it only summarises.
CLEAN_ARTIFACTS = [
    {
        "groupId": "androidx.core",
        "artifactId": "core-ktx",
        "version": "1.15.0",
        "name": "Core Kotlin Extensions",
        "spdxLicenses": [
            {"identifier": "Apache-2.0", "name": "Apache License 2.0", "url": "https://x/a"},
        ],
    },
    {
        "groupId": "com.squareup.okhttp3",
        "artifactId": "okhttp",
        "version": "4.12.0",
        "name": "OkHttp",
        "spdxLicenses": [
            {"identifier": "Apache-2.0", "name": "Apache License 2.0", "url": "https://x/a"},
        ],
    },
    {
        "groupId": "com.google.mlkit",
        "artifactId": "barcode-scanning",
        "version": "17.3.0",
        "name": "ML Kit Barcode Scanning",
        "spdxLicenses": [],
        "unknownLicenses": [
            {
                "name": "Android Software Development Kit License",
                "url": "https://developer.android.com/studio/terms.html",
            },
        ],
    },
]

# A real-shaped OSV record. GHSA records carry the word in database_specific.severity; the CVSS
# vector in severity[] is what everything else has, and _severity_of prefers the word.
OKHTTP_ADVISORY = {
    "id": "GHSA-w33c-445m-f8w7",
    "aliases": ["CVE-2023-0833"],
    "summary": "OkHttp improperly handles a crafted header",
    "severity": [{"type": "CVSS_V3", "score": "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:L/I:N/A:N"}],
    "database_specific": {"severity": "MODERATE"},
}

WITHDRAWN_ADVISORY = {
    "id": "GHSA-0000-0000-0000",
    "summary": "Retracted after review",
    "withdrawn": "2026-01-02T00:00:00Z",
}

FAILURES: list[str] = []


class OsvHandler(BaseHTTPRequestHandler):
    """A stand-in for api.osv.dev that answers from `server.vulns`.

    `server.vulns` maps "group:artifact@version" to a list of advisory records. `querybatch`
    answers ids positionally, as the real API does, and `/v1/vulns/<id>` answers the record.
    """

    protocol_version = "HTTP/1.1"

    def log_message(self, *_args: object) -> None:  # keep the test output readable
        pass

    def _reply(self, status: int, payload: object) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler's spelling
        if not self.path.endswith("/v1/querybatch"):
            self._reply(404, {"message": "not found"})
            return

        length = int(self.headers.get("Content-Length") or 0)
        request = json.loads(self.rfile.read(length).decode("utf-8"))

        if getattr(self.server, "truncate_results", False):
            self._reply(200, {"results": []})
            return

        results = []
        for query in request["queries"]:
            key = f"{query['package']['name']}@{query['version']}"
            records = self.server.vulns.get(key, [])
            results.append({"vulns": [{"id": record["id"]} for record in records]})
        self._reply(200, {"results": results})

    def do_GET(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler's spelling
        marker = "/v1/vulns/"
        if marker not in self.path:
            self._reply(404, {"message": "not found"})
            return

        wanted = self.path.split(marker, 1)[1]
        for records in self.server.vulns.values():
            for record in records:
                if record["id"] == wanted:
                    self._reply(200, record)
                    return
        self._reply(404, {"message": "not found"})


def start_server(vulns: dict[str, list[dict]], *, truncate: bool = False) -> ThreadingHTTPServer:
    server = ThreadingHTTPServer(("127.0.0.1", 0), OsvHandler)
    server.vulns = vulns
    server.truncate_results = truncate
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


def run_scan(
    root: Path,
    *,
    base_url: str,
    artifacts: str | None = None,
    allowlist: str | None = None,
    today: str = "2026-10-06",
) -> subprocess.CompletedProcess[str]:
    command = [
        sys.executable,
        str(SCAN),
        "--osv-base-url",
        base_url,
        "--today",
        today,
        # Short, because two of the cases below deliberately point at nothing listening and the
        # script retries three times before giving up.
        "--timeout",
        "3",
        "--artifacts",
        artifacts or str(ARTIFACTS_PATH),
        "--allowlist",
        allowlist or str(ALLOWLIST_PATH),
    ]
    return subprocess.run(command, cwd=root, capture_output=True, text=True, check=False)


def check(
    name: str,
    *,
    expect_exit: int,
    expect_in_output: str,
    artifacts: object = CLEAN_ARTIFACTS,
    allowlist: str = "",
    vulns: dict[str, list[dict]] | None = None,
    write_artifacts: bool = True,
    write_allowlist: bool = True,
    base_url: str | None = None,
    truncate: bool = False,
    today: str = "2026-10-06",
) -> None:
    """Run one case in its own fixture tree and record the result."""
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)

        if write_artifacts:
            report = root / ARTIFACTS_PATH
            report.parent.mkdir(parents=True, exist_ok=True)
            report.write_text(
                artifacts if isinstance(artifacts, str) else json.dumps(artifacts),
                encoding="utf-8",
            )

        if write_allowlist:
            allowed = root / ALLOWLIST_PATH
            allowed.parent.mkdir(parents=True, exist_ok=True)
            allowed.write_text(allowlist, encoding="utf-8")

        server = start_server(vulns or {}, truncate=truncate)
        try:
            url = base_url or f"http://127.0.0.1:{server.server_address[1]}"
            result = run_scan(root, base_url=url, today=today)
        finally:
            server.shutdown()
            server.server_close()

        output = result.stdout + result.stderr
        problems = []
        if result.returncode != expect_exit:
            problems.append(f"exit {result.returncode}, expected {expect_exit}")
        if expect_in_output not in output:
            problems.append(f"output did not contain {expect_in_output!r}")

        if problems:
            FAILURES.append(f"{name}: {'; '.join(problems)}")
            print(f"  FAIL  {name}")
            print("        " + "\n        ".join(output.strip().splitlines()[-25:]))
        else:
            print(f"  ok    {name}")


def main() -> int:
    print("scan-dependencies.py")

    # ---------------------------------------------------------------------------------------
    # The happy path, and the two ways it can be a lie
    # ---------------------------------------------------------------------------------------

    check(
        "a graph OSV knows nothing about passes",
        expect_exit=0,
        expect_in_output="3 modules scanned against OSV",
    )

    check(
        "the licence summary names the non-SPDX licence as well as the SPDX ones",
        expect_exit=0,
        expect_in_output="non-SPDX: https://developer.android.com/studio/terms.html",
    )

    check(
        "a missing artifacts.json fails rather than scanning nothing",
        write_artifacts=False,
        expect_exit=1,
        expect_in_output="licenseeDebug",
    )

    check(
        "an empty artifacts.json fails rather than reporting a clean graph",
        artifacts=[],
        expect_exit=1,
        expect_in_output="empty array",
    )

    check(
        "artifacts.json that is not JSON fails",
        artifacts="{not json",
        expect_exit=1,
        expect_in_output="not valid JSON",
    )

    check(
        "artifacts.json that is an object rather than an array fails",
        artifacts={"artifacts": []},
        expect_exit=1,
        expect_in_output="not the JSON array of artifacts Licensee",
    )

    check(
        "an artifact with no version fails rather than being skipped",
        artifacts=[
            {"groupId": "androidx.core", "artifactId": "core-ktx", "version": ""},
        ],
        expect_exit=1,
        expect_in_output="has no usable version",
    )

    check(
        "a missing allowlist file fails rather than reading as 'nothing suppressed'",
        write_allowlist=False,
        expect_exit=1,
        expect_in_output="An empty allowlist is a file with no entries",
    )

    # ---------------------------------------------------------------------------------------
    # A finding, and the allowlist around it
    # ---------------------------------------------------------------------------------------

    vulnerable = {"com.squareup.okhttp3:okhttp@4.12.0": [OKHTTP_ADVISORY]}

    check(
        "a vulnerability in a shipped dependency fails the run",
        vulns=vulnerable,
        expect_exit=1,
        expect_in_output="GHSA-w33c-445m-f8w7",
    )

    check(
        "the failure names the coordinate, the severity and the alias",
        vulns=vulnerable,
        expect_exit=1,
        expect_in_output="com.squareup.okhttp3:okhttp:4.12.0: GHSA-w33c-445m-f8w7 "
        "(aka CVE-2023-0833) [MODERATE]",
    )

    check(
        "an allowlist entry inside its expiry passes it",
        vulns=vulnerable,
        allowlist=(
            "GHSA-w33c-445m-f8w7 com.squareup.okhttp3:okhttp 2027-01-01 "
            "Header parsing is never reached: every request is built by Retrofit.\n"
        ),
        expect_exit=0,
        expect_in_output="1 known vulnerability(ies) allowed by",
    )

    check(
        "an alias is as good as the id in an allowlist entry",
        vulns=vulnerable,
        allowlist=(
            "CVE-2023-0833 com.squareup.okhttp3:okhttp 2027-01-01 "
            "Written against the CVE, which OSV records as an alias.\n"
        ),
        expect_exit=0,
        expect_in_output="0 unaccepted findings",
    )

    check(
        "an expired allowlist entry fails again",
        vulns=vulnerable,
        allowlist=(
            "GHSA-w33c-445m-f8w7 com.squareup.okhttp3:okhttp 2026-09-30 "
            "Accepted for one release only.\n"
        ),
        expect_exit=1,
        expect_in_output="expired on 2026-09-30",
    )

    check(
        "an allowlist entry for the right id on the wrong artifact does not apply",
        vulns=vulnerable,
        allowlist=(
            "GHSA-w33c-445m-f8w7 com.squareup.okio:okio 2027-01-01 Wrong coordinate.\n"
        ),
        expect_exit=1,
        expect_in_output="the scan did not find it",
    )

    check(
        "an allowlist entry matching nothing fails even when the graph is clean",
        allowlist=(
            "GHSA-w33c-445m-f8w7 com.squareup.okhttp3:okhttp 2027-01-01 Fixed three bumps ago.\n"
        ),
        expect_exit=1,
        expect_in_output="suppression that outlives the thing it suppressed",
    )

    check(
        "a withdrawn advisory is not a finding",
        vulns={"com.squareup.okhttp3:okhttp@4.12.0": [WITHDRAWN_ADVISORY]},
        expect_exit=0,
        expect_in_output="0 unaccepted findings",
    )

    # ---------------------------------------------------------------------------------------
    # Malformed allowlist entries
    # ---------------------------------------------------------------------------------------

    check(
        "an allowlist line missing its reason fails",
        allowlist="GHSA-w33c-445m-f8w7 com.squareup.okhttp3:okhttp 2027-01-01\n",
        expect_exit=1,
        expect_in_output="expected `<vulnerability-id> <group:artifact>",
    )

    check(
        "an allowlist coordinate carrying a version fails",
        allowlist="GHSA-x com.squareup.okhttp3:okhttp:4.12.0 2027-01-01 Pinned by mistake.\n",
        expect_exit=1,
        expect_in_output="is not a `group:artifact` pair",
    )

    check(
        "an unparseable expiry date fails",
        allowlist="GHSA-x com.squareup.okhttp3:okhttp soon Someday.\n",
        expect_exit=1,
        expect_in_output="is not a YYYY-MM-DD date",
    )

    check(
        "the same id twice for one artifact fails",
        allowlist=(
            "GHSA-x com.squareup.okhttp3:okhttp 2027-01-01 First.\n"
            "GHSA-x com.squareup.okhttp3:okhttp 2027-06-01 Second, quietly wins.\n"
        ),
        expect_exit=1,
        expect_in_output="is already allowed on line 1",
    )

    check(
        "comments and blank lines are ignored",
        allowlist="# a comment\n\n   # another, indented\n",
        expect_exit=0,
        expect_in_output="0 unaccepted findings",
    )

    # ---------------------------------------------------------------------------------------
    # The database itself
    # ---------------------------------------------------------------------------------------

    check(
        "an unreachable database fails rather than passing",
        # Port 1 is reserved and nothing listens on it.
        base_url="http://127.0.0.1:1",
        expect_exit=1,
        expect_in_output="could not reach",
    )

    check(
        "a querybatch answer that cannot be lined up with the queries fails",
        truncate=True,
        expect_exit=1,
        expect_in_output="cannot be matched up",
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
