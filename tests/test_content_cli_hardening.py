"""Operational hardening for the content commands.

Three things this file pins down, each because they were real findings:

* ``--dry-run`` must leave nothing behind - not a directory, not a database,
  not a row in ``works`` / ``work_chapters`` / ``chapter_pages`` /
  ``source_policy`` / ``element_clearance``.
* the CLI tests must not reach the internet by accident. A guard makes any
  non-loopback connection an immediate failure, and a control test proves the
  guard is not vacuous.
* ``content-clear`` says plainly, in its own help, that it has no
  authentication.

The commands are driven through ``cli.main`` in-process, under the network
guard, so no subprocess and no socket is involved.
"""

from __future__ import annotations

import contextlib
import io
import json
import socket
import tempfile
import unittest
from pathlib import Path

from shura_core import cli

#: Loopback only. Anything else is a test trying to leave the device.
LOOPBACK = {"127.0.0.1", "::1", "localhost"}


class NoExternalNetwork:
    """Fails loudly on any connection that is not loopback.

    Patched at the socket layer rather than by stubbing the project's own HTTP
    client, so it also catches a regression that routes around ``SafeHTTP``.
    """

    def __init__(self):
        self.blocked: list[str] = []
        self._connect = socket.socket.connect
        self._create = socket.create_connection

    @staticmethod
    def _host(address) -> str:
        if isinstance(address, tuple) and address:
            return str(address[0])
        return str(address)

    def __enter__(self):
        guard = self

        def connect(sock, address, *a, **k):
            host = guard._host(address)
            if host not in LOOPBACK:
                guard.blocked.append(host)
                raise AssertionError(f"test tried to reach external host {host!r}")
            return guard._connect(sock, address, *a, **k)

        def create(address, *a, **k):
            host = guard._host(address)
            if host not in LOOPBACK:
                guard.blocked.append(host)
                raise AssertionError(f"test tried to reach external host {host!r}")
            return guard._create(address, *a, **k)

        socket.socket.connect = connect
        socket.create_connection = create
        return self

    def __exit__(self, *exc):
        socket.socket.connect = self._connect
        socket.create_connection = self._create
        return False


def manifest() -> dict:
    return {
        "version": 1, "source_id": "s1",
        "works": [{"ext_work_id": "w1", "title": "W",
                   "source_url": "https://example.org/w/1",
                   "content_classification": "SAFE"}],
        "chapters": [{"ext_chapter_id": "c1", "ext_work_id": "w1",
                      "source_chapter_id": "sc1", "chapter_number": 1.0, "ordinal": 0,
                      "source_url": "https://example.org/w/1/c/1", "page_count": 1}],
        "pages": [{"ext_chapter_id": "c1", "page_index": 0, "ordinal": 0,
                   "source_page_url": "https://example.org/w/1/c/1/p1.png"}],
    }


def config(enabled: bool = True) -> dict:
    return {"version": 1, "sources": [{
        "source_id": "s1", "fetch_enabled": enabled,
        "allowed_hosts": ["example.org"], "quota_bytes": 1 << 30,
        "retention_days": 30}]}


class CliCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.work = Path(self.tmp.name)
        self.manifest_path = self.work / "manifest.json"
        self.config_path = self.work / "content-sources.json"
        self.content_dir = self.work / "content-store"
        self.manifest_path.write_text(json.dumps(manifest()), encoding="utf-8")
        self.config_path.write_text(json.dumps(config()), encoding="utf-8")

    def tearDown(self):
        self.tmp.cleanup()

    def run_cli(self, argv) -> tuple[int, str]:
        """Run the CLI and return its exit code plus its own last output line.

        ``redirect_stdout`` swaps the process-wide ``sys.stdout``, so output from
        a thread another test left running can land in the buffer. Callers that
        expect JSON use :meth:`last_json`, which takes the last JSON-looking line.
        """
        out = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(out):
            try:
                code = cli.main(argv)
            except SystemExit as exc:      # argparse --help and argparse errors
                code = int(exc.code or 0)
        return code, out.getvalue()

    @staticmethod
    def last_json(text: str) -> dict:
        """The command's own JSON line, ignoring anything another thread printed."""
        for line in reversed([l for l in text.splitlines() if l.strip()]):
            if line.startswith("{"):
                return json.loads(line)
        raise AssertionError(f"no JSON in command output: {text!r}")

    def import_args(self, *extra):
        return ["--db", str(self.work / "unused-state.db"), "content-import",
                str(self.manifest_path), "--content-dir", str(self.content_dir),
                "--config", str(self.config_path), *extra]

    def db(self):
        import sqlite3
        return sqlite3.connect(str(self.content_dir / "content.db"))

    def all_counts(self) -> tuple:
        conn = self.db()
        try:
            return conn.execute(
                "SELECT (SELECT COUNT(*) FROM works), (SELECT COUNT(*) FROM work_chapters), "
                "(SELECT COUNT(*) FROM chapter_pages), "
                "(SELECT COUNT(*) FROM source_policy), "
                "(SELECT COUNT(*) FROM element_clearance)").fetchone()
        finally:
            conn.close()


class DryRunTests(CliCase):
    def test_dry_run_creates_nothing_at_all(self):
        with NoExternalNetwork() as guard:
            code, out = self.run_cli(self.import_args("--dry-run"))
        self.assertEqual(code, 0)
        self.assertTrue(self.last_json(out)["dryRun"])
        self.assertEqual(guard.blocked, [])
        # No content directory, therefore no database, therefore no rows.
        self.assertFalse(self.content_dir.exists(),
                         "--dry-run must not even create the content directory")

    def test_dry_run_writes_no_state_database_in_the_working_directory(self):
        # cli.main used to open StateStore before dispatch, which created an
        # empty shura.db in the cwd for every command including --dry-run.
        import os
        previous = os.getcwd()
        os.chdir(self.work)
        try:
            with NoExternalNetwork():
                code, _ = self.run_cli(["content-import", str(self.manifest_path),
                                        "--content-dir", str(self.content_dir),
                                        "--config", str(self.config_path), "--dry-run"])
            self.assertEqual(code, 0)
            self.assertEqual(list(self.work.glob("*.db")), [],
                             "--dry-run must not create a state database")
        finally:
            os.chdir(previous)

    def test_the_real_import_does_create_the_rows_the_dry_run_skips(self):
        # Control: without --dry-run the same command writes everything, so the
        # test above is proving something rather than passing trivially.
        code, out = self.run_cli(self.import_args())
        self.assertEqual(code, 0)
        self.assertEqual(self.last_json(out)["chapter_pages"], 1)
        self.assertEqual(self.all_counts(), (1, 1, 1, 1, 0))
        self.assertTrue((self.content_dir / "content.db").is_file())

    def test_a_real_import_after_a_dry_run_is_unaffected(self):
        self.run_cli(self.import_args("--dry-run"))
        code, _ = self.run_cli(self.import_args())
        self.assertEqual(code, 0)
        self.assertEqual(self.all_counts(), (1, 1, 1, 1, 0))


class NetworkIsolationTests(CliCase):
    def test_the_guard_actually_blocks_an_external_host(self):
        # Control: proves the guard is not vacuous.
        with NoExternalNetwork() as guard:
            with self.assertRaises(AssertionError):
                socket.create_connection(("example.org", 443), timeout=1)
            self.assertEqual(guard.blocked, ["example.org"])

    def test_the_guard_still_allows_loopback(self):
        with NoExternalNetwork() as guard:
            try:
                socket.create_connection(("127.0.0.1", 1), timeout=1)
            except OSError:
                pass                      # nothing is listening; that is fine
            self.assertEqual(guard.blocked, [])

    def test_the_whole_cli_flow_makes_no_external_connection(self):
        from shura_core.content import runner as runner_module
        from tests.test_content_ingest import StubHTTP

        self.run_cli(self.import_args())
        clear = ["--db", str(self.work / "unused-state.db"), "content-clear",
                 "--content-dir", str(self.content_dir), "--source-id", "s1",
                 "--work-id", "w1", "--chapter-id", "c1", "--decision", "CLEARED",
                 "--decided-by", "local-operator", "--evidence", "review"]
        self.run_cli(clear)

        real = runner_module.SafeHTTP
        runner_module.SafeHTTP = StubHTTP
        StubHTTP.requests = []
        try:
            with NoExternalNetwork() as guard:
                code, out = self.run_cli(
                    ["--db", str(self.work / "unused-state.db"), "content-fetch",
                     "--content-dir", str(self.content_dir),
                     "--config", str(self.config_path)])
        finally:
            runner_module.SafeHTTP = real

        self.assertEqual(code, 0)
        self.assertEqual(self.last_json(out)["pagesDone"], 1)
        self.assertEqual(guard.blocked, [],
                         "no command in this flow may reach a non-loopback host")

    def test_fetch_before_clearance_stays_local_and_downloads_nothing(self):
        from shura_core.content import runner as runner_module
        from tests.test_content_ingest import StubHTTP

        self.run_cli(self.import_args())
        real = runner_module.SafeHTTP
        runner_module.SafeHTTP = StubHTTP
        StubHTTP.requests = []
        try:
            with NoExternalNetwork() as guard:
                code, out = self.run_cli(
                    ["--db", str(self.work / "unused-state.db"), "content-fetch",
                     "--content-dir", str(self.content_dir),
                     "--config", str(self.config_path)])
        finally:
            runner_module.SafeHTTP = real

        self.assertEqual(code, 0)
        report = self.last_json(out)
        self.assertEqual(report["pagesDone"], 0)
        self.assertEqual(report["pagesRefused"], 1)
        self.assertEqual(StubHTTP.requests, [],
                         "an unapproved chapter must not even reach the transport")
        self.assertEqual(guard.blocked, [])


class ClearanceHelpTests(CliCase):
    def test_the_help_says_there_is_no_authentication(self):
        code, out = self.run_cli(["content-clear", "--help"])
        # argparse exits the process on --help, so call the parser directly.
        self.assertIn("authentication", out.lower())
        self.assertIn("not a verified identity", out.lower())

    def test_the_record_makes_no_authentication_claim(self):
        self.run_cli(self.import_args())
        code, out = self.run_cli(
            ["--db", str(self.work / "unused-state.db"), "content-clear",
             "--content-dir", str(self.content_dir), "--source-id", "s1",
             "--work-id", "w1", "--chapter-id", "c1", "--decision", "CLEARED",
             "--decided-by", "local-operator", "--evidence", "review"])
        self.assertEqual(code, 0)
        payload = self.last_json(out)
        self.assertIn("none", payload["authentication"])
        self.assertIn("attributed label", payload["authentication"])
        self.assertEqual(payload["decidedBy"], "local-operator")

    def test_clearance_requires_an_explicit_decider(self):
        code, _ = self.run_cli(
            ["--db", str(self.work / "unused-state.db"), "content-clear",
             "--content-dir", str(self.content_dir), "--source-id", "s1",
             "--work-id", "w1", "--chapter-id", "c1", "--decision", "CLEARED"])
        self.assertNotEqual(code, 0, "--decided-by is required; a blank record is refused")


if __name__ == "__main__":
    unittest.main()