"""End-to-end: a local manifest into the store, and from there to a stored page.

The sequence under test is the whole point of the ingest interface:

1. import a work, a chapter and a page from a local file
2. before any approval: zero network requests, zero download attempts
3. after an explicit clearance: ``content-fetch`` downloads and stores the page
4. re-importing converges and grants nothing
5. a rejected import leaves nothing behind
6. a disabled or absent source is not enabled by importing

Nothing here reaches a host. The fetch is driven through ``run_fetch_run`` with
``SafeHTTP`` replaced by a stub that serves a local PNG, so the real chain
``runner -> SafeHTTPTransport -> SafeHTTP.stream -> PageFetcher -> write_bounded
-> place_page -> commit_done`` is exercised end to end while the socket is not.
"""

from __future__ import annotations

import json
import struct
import tempfile
import unittest
import zlib
from contextlib import contextmanager
from email.message import Message
from pathlib import Path

from shura_core.content import (ImportRejected, import_manifest, open_store,
                                run_fetch_run)
from shura_core.content import runner as runner_module
from shura_core.content.store import grant_clearance
from shura_core.security.network import NetworkPolicyError


def png(width: int = 8, height: int = 8) -> bytes:
    def chunk(kind: bytes, data: bytes) -> bytes:
        body = kind + data
        return (struct.pack(">I", len(data)) + body
                + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF))

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    raw = b"".join(bytes([0]) + b"\x00" * (width * 3) for _ in range(height))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


PNG = png()


class StubHTTP:
    """Stands in for SafeHTTP. Counts requests; serves a local PNG; no socket."""

    requests: list[str] = []

    def __init__(self, *args, **kwargs):
        self.args = args
        self.kwargs = kwargs

    @contextmanager
    def stream(self, url, *, max_bytes, headers=None):
        StubHTTP.requests.append(url)
        response_headers = Message()
        response_headers["Content-Type"] = "image/png"
        yield response_headers, url, iter((PNG,)), max_bytes

    def get(self, url, headers=None):
        StubHTTP.requests.append(url)
        return PNG, response_headers_stub(), url


def response_headers_stub():
    headers = Message()
    headers["Content-Type"] = "image/png"
    return headers


def manifest(**overrides) -> dict:
    base = {
        "version": 1,
        "source_id": "s1",
        "works": [{"ext_work_id": "w1", "source_work_id": "src", "title": "Work",
                   "source_url": "https://example.org/w/1",
                   "content_classification": "SAFE"}],
        "chapters": [{"ext_chapter_id": "c1", "ext_work_id": "w1",
                      "source_chapter_id": "sc1", "chapter_number": 1.0,
                      "ordinal": 0, "title": "Chapter 1",
                      "source_url": "https://example.org/w/1/c/1", "page_count": 1}],
        "pages": [{"ext_chapter_id": "c1", "page_index": 0, "ordinal": 0,
                   "source_page_url": "https://example.org/w/1/c/1/p1.png"}],
    }
    base.update(overrides)
    return base


class IngestCase(unittest.TestCase):
    def setUp(self):
        StubHTTP.requests = []
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.conn = open_store(self.root / "content.db")
        self.conn.execute(
            "INSERT INTO source_policy(source_id,kind,allowed_hosts,fetch_enabled,quota_bytes,"
            "retention_days,synced_at) VALUES('s1','index','[\"example.org\"]',1,?,'30','now')",
            (1 << 40,))
        self.manifest_path = self.root / "manifest.json"
        self.config_path = self.root / "content-sources.json"
        self.write_config([{"source_id": "s1", "fetch_enabled": True,
                            "allowed_hosts": ["example.org"], "quota_bytes": 1 << 30,
                            "retention_days": 30}])

    def tearDown(self):
        self.conn.close()
        self.tmp.cleanup()

    def write_config(self, entries):
        self.config_path.write_text(json.dumps({"version": 1, "sources": entries}),
                                    encoding="utf-8")

    def write_manifest(self, data):
        self.manifest_path.write_text(json.dumps(data), encoding="utf-8")
        return self.manifest_path

    def fetch_run(self):
        """Drive the real runner with the network stubbed out."""
        real = runner_module.SafeHTTP
        runner_module.SafeHTTP = StubHTTP
        try:
            return run_fetch_run(self.conn, self.root, config_path=self.config_path,
                                 instance_id="ingest-test", host="host-test")
        finally:
            runner_module.SafeHTTP = real

    def counts(self):
        return tuple(self.conn.execute(
            f"SELECT (SELECT COUNT(*) FROM works), (SELECT COUNT(*) FROM work_chapters), "
            f"(SELECT COUNT(*) FROM chapter_pages), "
            f"(SELECT COUNT(*) FROM element_clearance)").fetchone())

    def attempts(self):
        return self.conn.execute("SELECT COUNT(*) FROM save_intents").fetchone()[0]

    def clear(self):
        grant_clearance(self.conn, source_id="s1", ext_work_id="w1", ext_chapter_id="c1",
                        decision="CLEARED", basis="operator-approval", decided_by="operator",
                        evidence="manual review", policy_version="v1")


class ImportTests(IngestCase):
    def test_a_manifest_lands_all_three_levels(self):
        result = import_manifest(self.conn, self.write_manifest(manifest()))
        self.assertEqual(result, {"source_id": "s1", "works": 1, "work_chapters": 1,
                                  "chapter_pages": 1})
        self.assertEqual(self.counts(), (1, 1, 1, 0))
        page = self.conn.execute(
            "SELECT source_page_url, page_key_kind, source_page_id, stable_page_url "
            "FROM chapter_pages WHERE page_index=0").fetchone()
        self.assertEqual(page[0], "https://example.org/w/1/c/1/p1.png")
        # A URL that merely appeared is not an identity.
        self.assertEqual(page[1], "none")
        self.assertIsNone(page[2])
        self.assertIsNone(page[3])

    def test_a_stable_page_id_becomes_the_page_identity(self):
        data = manifest()
        data["pages"][0]["source_page_id"] = "page-1"
        import_manifest(self.conn, self.write_manifest(data))
        row = self.conn.execute(
            "SELECT page_key_kind, source_page_id FROM chapter_pages").fetchone()
        self.assertEqual(tuple(row), ("source_page_id", "page-1"))

    def test_a_stable_url_becomes_the_page_identity(self):
        data = manifest()
        data["pages"][0]["stable_page_url"] = "https://example.org/w/1/c/1/p1"
        import_manifest(self.conn, self.write_manifest(data))
        row = self.conn.execute(
            "SELECT page_key_kind, stable_page_url FROM chapter_pages").fetchone()
        self.assertEqual(tuple(row), ("stable_url", "https://example.org/w/1/c/1/p1"))

    def test_import_never_grants_clearance(self):
        import_manifest(self.conn, self.write_manifest(manifest()))
        self.assertEqual(self.counts()[3], 0)

    def test_reimporting_converges_and_grants_nothing(self):
        import_manifest(self.conn, self.write_manifest(manifest()))
        self.clear()
        import_manifest(self.conn, self.write_manifest(manifest()))
        self.assertEqual(self.counts(), (1, 1, 1, 1), "re-import must not duplicate or clear")

    def test_reimport_does_not_disturb_stored_evidence(self):
        import_manifest(self.conn, self.write_manifest(manifest()))
        self.clear()
        self.fetch_run()
        state, sha = self.conn.execute(
            "SELECT state, sha256 FROM chapter_pages WHERE page_index=0").fetchone()
        self.assertEqual(state, "DONE")
        import_manifest(self.conn, self.write_manifest(manifest()))
        after = self.conn.execute(
            "SELECT state, sha256 FROM chapter_pages WHERE page_index=0").fetchone()
        self.assertEqual(tuple(after), (state, sha))

    def test_a_missing_classification_is_stored_as_null_not_invented(self):
        data = manifest()
        data["works"][0].pop("content_classification")
        import_manifest(self.conn, self.write_manifest(data))
        value = self.conn.execute("SELECT content_classification FROM works").fetchone()[0]
        self.assertIsNone(value)


class RejectionTests(IngestCase):
    def assertNothingImported(self):
        self.assertEqual(self.counts(), (0, 0, 0, 0))

    def test_an_unknown_source_is_refused(self):
        data = manifest(source_id="not-a-source")
        with self.assertRaises(ImportRejected) as caught:
            import_manifest(self.conn, self.write_manifest(data))
        self.assertIn("source_policy", str(caught.exception))
        self.assertNothingImported()

    def test_a_page_url_off_the_source_policy_is_refused(self):
        data = manifest()
        data["pages"][0]["source_page_url"] = "https://elsewhere.example/p1.png"
        with self.assertRaises(ImportRejected):
            import_manifest(self.conn, self.write_manifest(data))
        self.assertNothingImported()

    def test_a_plain_http_page_url_is_refused(self):
        data = manifest()
        data["pages"][0]["source_page_url"] = "http://example.org/p1.png"
        with self.assertRaises(ImportRejected):
            import_manifest(self.conn, self.write_manifest(data))
        self.assertNothingImported()

    def test_a_private_host_page_url_is_refused(self):
        data = manifest()
        data["pages"][0]["source_page_url"] = "https://127.0.0.1/p1.png"
        with self.assertRaises(ImportRejected):
            import_manifest(self.conn, self.write_manifest(data))
        self.assertNothingImported()

    def test_a_bad_version_is_refused(self):
        with self.assertRaises(ImportRejected):
            import_manifest(self.conn, self.write_manifest(manifest(version=2)))
        self.assertNothingImported()

    def test_a_chapter_naming_an_absent_work_is_refused(self):
        data = manifest()
        data["works"] = []
        with self.assertRaises(ImportRejected):
            import_manifest(self.conn, self.write_manifest(data))
        self.assertNothingImported()

    def test_a_page_naming_an_absent_chapter_is_refused(self):
        data = manifest()
        data["chapters"] = []
        with self.assertRaises(ImportRejected):
            import_manifest(self.conn, self.write_manifest(data))
        self.assertNothingImported()

    def test_two_identities_on_one_page_are_refused(self):
        data = manifest()
        data["pages"][0]["source_page_id"] = "p"
        data["pages"][0]["stable_page_url"] = "https://example.org/p"
        with self.assertRaises(ImportRejected):
            import_manifest(self.conn, self.write_manifest(data))
        self.assertNothingImported()

    def test_a_late_failure_leaves_nothing_behind(self):
        # Valid works and chapters, one bad page: the whole import must roll back.
        data = manifest()
        data["pages"].append({"ext_chapter_id": "c1", "page_index": 1, "ordinal": 1,
                              "source_page_url": "https://elsewhere.example/p2.png"})
        with self.assertRaises(ImportRejected):
            import_manifest(self.conn, self.write_manifest(data))
        self.assertNothingImported()

    def test_a_malformed_file_is_refused(self):
        self.manifest_path.write_text("{ not json", encoding="utf-8")
        with self.assertRaises(ImportRejected):
            import_manifest(self.conn, self.manifest_path)
        self.assertNothingImported()


class FetchAfterClearanceTests(IngestCase):
    """The integration: import -> nothing happens -> approve -> a stored page."""

    def test_before_approval_nothing_is_requested_and_no_attempt_is_made(self):
        import_manifest(self.conn, self.write_manifest(manifest()))
        report = self.fetch_run()
        self.assertEqual(StubHTTP.requests, [],
                         "an unapproved chapter must produce zero network requests")
        self.assertEqual(report.pages_done, 0)
        self.assertEqual(self.attempts(), 0, "no download attempt may be created")
        state, sha = self.conn.execute(
            "SELECT state, sha256 FROM chapter_pages WHERE page_index=0").fetchone()
        self.assertEqual(state, "PENDING")
        self.assertIsNone(sha)
        self.assertEqual(list(self.root.rglob("*.img")), [])

    def test_after_explicit_approval_the_page_is_downloaded_and_stored(self):
        import_manifest(self.conn, self.write_manifest(manifest()))
        self.clear()
        report = self.fetch_run()
        self.assertEqual(StubHTTP.requests, ["https://example.org/w/1/c/1/p1.png"])
        self.assertEqual(report.pages_done, 1)
        state, sha, bytes_ = self.conn.execute(
            "SELECT state, sha256, bytes FROM chapter_pages WHERE page_index=0").fetchone()
        self.assertEqual(state, "DONE")
        self.assertEqual(bytes_, len(PNG))
        self.assertEqual(len(sha), 64)
        stored = self.conn.execute(
            "SELECT rel_path FROM chapter_pages WHERE page_index=0").fetchone()[0]
        on_disk = self.root / stored
        self.assertTrue(on_disk.is_file(), "a page recorded DONE must exist on disk")
        self.assertEqual(on_disk.read_bytes(), PNG)
        self.assertEqual(list((self.root / "content").rglob("*.part")), [],
                         "no partial file may survive a completed fetch")

    def test_withholding_keeps_it_out_again(self):
        import_manifest(self.conn, self.write_manifest(manifest()))
        self.clear()
        self.assertEqual(self.fetch_run().pages_done, 1)
        grant_clearance(self.conn, source_id="s1", ext_work_id="w1", ext_chapter_id="c1",
                        decision="WITHHELD", basis="operator-approval", decided_by="operator",
                        evidence="re-reviewed", policy_version="v2")
        StubHTTP.requests = []
        self.fetch_run()
        self.assertEqual(StubHTTP.requests, [],
                         "withdrawing clearance must stop further fetching")


class SourceActivationTests(IngestCase):
    def test_importing_does_not_enable_a_source_that_is_off_in_the_config(self):
        self.write_config([{"source_id": "s1", "fetch_enabled": False,
                            "allowed_hosts": ["example.org"], "quota_bytes": 1 << 30,
                            "retention_days": 30}])
        import_manifest(self.conn, self.write_manifest(manifest()))
        self.clear()
        report = self.fetch_run()
        self.assertEqual(report.sources_fetched, [])
        self.assertEqual(StubHTTP.requests, [])

    def test_importing_does_not_enable_a_source_absent_from_the_config(self):
        self.write_config([])
        import_manifest(self.conn, self.write_manifest(manifest()))
        self.clear()
        report = self.fetch_run()
        self.assertEqual(report.sources_fetched, [])
        self.assertEqual(StubHTTP.requests, [])

    def test_the_row_lands_even_while_the_source_is_off(self):
        self.write_config([])
        import_manifest(self.conn, self.write_manifest(manifest()))
        self.assertEqual(self.counts()[:3], (1, 1, 1),
                         "import writes rows; whether fetching may happen is separate")


if __name__ == "__main__":
    unittest.main()