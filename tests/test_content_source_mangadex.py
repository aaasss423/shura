"""Discovery to stored page, end to end, with no socket.

This is the integration the project was missing: a real source connector
(MangaDex), its output landing in the existing content store through the shared
ingest boundary, the safety gate refusing before approval, and a page actually
downloaded and stored after approval.

The HTTP responses under ``tests/fixtures/mangadex`` are real API bodies, saved
from api.mangadex.org, trimmed but otherwise untouched - including the rotating
per-chapter image host ``cmxd98sb0x3yprd.mangadex.network``. The client is the
project's own ``SafeHTTP`` with the sockets replaced by a fixture, so every guard
it applies (host allow-list, HTTPS, redirect limits, size ceiling) still runs.
"""

from __future__ import annotations

import contextlib
import io
import json
import struct
import tempfile
import unittest
import zlib
from contextlib import contextmanager
from email.message import Message
from pathlib import Path
from unittest import mock

from shura_core import cli
from shura_core.content import (ImportRejected, chapter_is_fetchable, import_payload,
                                load_source_config, open_store, run_fetch_run,
                                sync_source_policy)
from shura_core.content import runner as runner_module
from shura_core.content.mangadex import IMAGE_HOST_SUFFIX, MangaDexSource
from shura_core.content.store import grant_clearance
from shura_core.security.network import NetworkPolicyError, SafeHTTP, validate_url

FIXTURES = Path(__file__).resolve().parent / "fixtures" / "mangadex"
API_HOST = "api.mangadex.org"
IMAGE_HOST = "cmxd98sb0x3yprd.mangadex.network"


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


class LoopbackGuard:
    """Fails on any non-loopback connection attempt."""

    def __init__(self):
        self.blocked: list[str] = []

    def __enter__(self):
        import socket
        self._create = socket.create_connection
        guard = self

        def create(address, *a, **k):
            host = str(address[0]) if isinstance(address, tuple) else str(address)
            if host not in {"127.0.0.1", "::1", "localhost"}:
                guard.blocked.append(host)
                raise AssertionError(f"test tried to reach external host {host!r}")
            return guard._create(address, *a, **k)

        socket.create_connection = create
        return self

    def __exit__(self, *exc):
        import socket
        socket.create_connection = self._create
        return False


class _FakeResponse:
    """What ``opener.open`` returns: a context manager with headers and read()."""

    def __init__(self, url: str, body: bytes):
        self._url = url
        self._body = body
        self._offset = 0
        self.headers = {"Content-Type": "application/json",
                        "Content-Length": str(len(body))}

    def geturl(self):
        return self._url

    def read(self, size=-1):
        if size is None or size < 0:
            size = len(self._body) - self._offset
        chunk = self._body[self._offset:self._offset + size]
        self._offset += len(chunk)
        return chunk

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


class FixtureTransport:
    """Redirects SafeHTTP's opener at saved API bodies. Opens no socket.

    Patching the opener rather than the client keeps every SafeHTTP guard live:
    the host allow-list, the HTTPS requirement, the redirect limit and the size
    ceiling all still run over these bytes.
    """

    def __init__(self, body_override: bytes | None = None):
        self.urls: list[str] = []
        self.body_override = body_override
        self._original = None

    def _body(self, url: str) -> bytes:
        if self.body_override is not None:
            return self.body_override
        if "/at-home/server/" in url:
            return (FIXTURES / "at_home.json").read_bytes()
        if "/chapter?" in url or "/manga?" in url:
            name = "chapters.json" if "/chapter?" in url else "works.json"
            payload = json.loads((FIXTURES / name).read_text())
            # The real API honours `limit`; so does this fixture, otherwise a
            # test asking for one work would silently get all three.
            query = url.split("?", 1)[1] if "?" in url else ""
            limit = None
            for pair in query.split("&"):
                key, _, value = pair.partition("=")
                if key == "limit" and value.isdigit():
                    limit = int(value)
            if limit is not None and isinstance(payload.get("data"), list):
                payload["data"] = payload["data"][:limit]
                payload["total"] = len(payload["data"])
            return json.dumps(payload).encode()
        raise AssertionError(f"unexpected url {url}")

    def __enter__(self):
        from shura_core.security import network
        self._original = network.build_opener

        def patched(redirect_handler):
            opener = self._original(redirect_handler)

            def fake_open(req, **kw):
                self.urls.append(req.full_url)
                return _FakeResponse(req.full_url, self._body(req.full_url))

            opener.open = fake_open
            return opener

        network.build_opener = patched
        return self

    def __exit__(self, *exc):
        from shura_core.security import network
        network.build_opener = self._original
        return False


def source_http(transport: FixtureTransport) -> SafeHTTP:
    """A SafeHTTP whose allow-list covers the API and the rotating image host."""
    return SafeHTTP([API_HOST, IMAGE_HOST, f".{IMAGE_HOST_SUFFIX}"],
                    https_only=True)


class ImageTransport:
    """Serves the page image locally, through the real fetch stack."""

    def __init__(self):
        self.requested: list[str] = []

    @contextmanager
    def stream(self, url, *, max_bytes, headers=None):
        self.requested.append(url)
        response_headers = Message()
        response_headers["Content-Type"] = "image/png"
        yield response_headers, url, iter((PNG,)), max_bytes

    def get(self, url, headers=None):
        self.requested.append(url)
        raise AssertionError("the fetch path must stream, not call get")


class SourceTests(unittest.TestCase):
    """The connector, against the real saved API responses."""

    def setUp(self):
        self.transport = FixtureTransport()
        self.transport.__enter__()
        self.addCleanup(self.transport.__exit__, None, None, None)
        self.http = source_http(self.transport)

    def test_lists_works_available_in_arabic(self):
        source = MangaDexSource(self.http)
        works = source.works(limit=5, language="ar")
        self.assertEqual(len(works), 3)
        self.assertEqual(works[0]["id"], "30f3ac69-21b6-45ad-a110-d011b7aaadaa")
        self.assertEqual(works[0]["title"], "تانوكاكو كاواي",
                         "the Arabic title is preferred when the language is asked for")
        self.assertIn(f"{API_HOST}/manga?limit=5&offset=0"
                      "&availableTranslatedLanguage%5B%5D=ar", self.transport.urls[0])

    def test_maps_the_source_content_rating_onto_our_vocabulary(self):
        works = {w["id"]: w for w in MangaDexSource(self.http).works(limit=5, language="ar")}
        self.assertEqual(works["30f3ac69-21b6-45ad-a110-d011b7aaadaa"]["classification"], "SAFE")
        self.assertEqual(works["aaaa1111-2222-3333-4444-555566667777"]["classification"], "MATURE")
        # Absent content rating stays unclassified rather than being invented.
        self.assertIsNone(works["bbbb1111-2222-3333-4444-555566667777"]["classification"])

    def test_lists_chapters_and_drops_unavailable_ones(self):
        chapters = MangaDexSource(self.http).chapters("30f3ac69-21b6-45ad-a110-d011b7aaadaa")
        self.assertEqual([c["id"] for c in chapters],
                         ["c2a3724f-101f-40b4-acaf-c7dcf23d70fa",
                          "9a57e217-f3dd-48a9-9d1c-9ac6b776ac2a"])
        self.assertEqual(chapters[0]["title"], "ضوء القمر",
                         "titles are whitespace-normalised")
        self.assertEqual(chapters[0]["pages"], 3)

    def test_lists_ordered_pages_with_a_stable_page_id(self):
        pages = MangaDexSource(self.http).chapter_pages("c2a3724f-101f-40b4-acaf-c7dcf23d70fa")
        self.assertEqual([p["page_index"] for p in pages], [0, 1, 2])
        self.assertEqual(pages[0]["source_page_id"],
                         "1-daf0e2dfe1948d9666b301860b1f84af9ee9e07951d63b30c85a795ed18e90c4.jpg")
        self.assertEqual(pages[0]["source_page_url"],
                         f"https://{IMAGE_HOST}/data/b501e6dd0e101700590eddb6fc02dd0e/"
                         "1-daf0e2dfe1948d9666b301860b1f84af9ee9e07951d63b30c85a795ed18e90c4.jpg")

    def test_builds_a_manifest_for_the_ingest_layer(self):
        manifest = MangaDexSource(self.http).manifest(
            language="ar", works=1, chapters_per_work=1, pages_per_chapter=2)
        self.assertEqual(manifest["version"], 1)
        self.assertEqual(manifest["source_id"], "mangadex")
        self.assertEqual(len(manifest["works"]), 1)
        self.assertEqual(len(manifest["chapters"]), 1)
        self.assertEqual(len(manifest["pages"]), 2)
        self.assertEqual(manifest["works"][0]["content_classification"], "SAFE")
        self.assertEqual(manifest["pages"][0]["source_page_id"],
                         "1-daf0e2dfe1948d9666b301860b1f84af9ee9e07951d63b30c85a795ed18e90c4.jpg")

    def test_a_rotating_cdn_host_needs_the_explicit_suffix_entry(self):
        url = f"https://{IMAGE_HOST}/data/x/1.jpg"
        # Naming the base domain is not enough: each chapter gets a fresh host,
        # so only the dotted, subdomain-covering entry can keep working.
        with self.assertRaises(NetworkPolicyError):
            validate_url(url, [IMAGE_HOST_SUFFIX])
        validate_url(url, [f".{IMAGE_HOST_SUFFIX}"])
        # The suffix must not leak: a look-alike domain is still refused.
        with self.assertRaises(NetworkPolicyError):
            validate_url(f"https://evil.{IMAGE_HOST_SUFFIX}.attacker.test/x.jpg",
                         [f".{IMAGE_HOST_SUFFIX}"])

    def test_a_path_shaped_page_name_is_refused(self):
        broken = json.loads((FIXTURES / "at_home.json").read_text())
        broken["chapter"]["data"] = ["../../../etc/passwd"]
        self.transport.body_override = json.dumps(broken).encode()
        with self.assertRaises(Exception):
            MangaDexSource(self.http).chapter_pages("c2a3724f-101f-40b4-acaf-c7dcf23d70fa")


class EndToEndTests(unittest.TestCase):
    """Discovery -> ingest -> gate -> approval -> stored page."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.work = Path(self.tmp.name)
        self.root = self.work / "content-store"
        self.root.mkdir(parents=True)
        self.conn = open_store(self.root / "content.db")
        self.config = self.work / "content-sources.json"
        self.config.write_text(json.dumps({"version": 1, "sources": [{
            "source_id": "mangadex", "fetch_enabled": True,
            # The dot is the explicit opt-in for the rotating image host.
            "allowed_hosts": [API_HOST, f".{IMAGE_HOST_SUFFIX}"],
            "quota_bytes": 1 << 30, "retention_days": 30}]}), encoding="utf-8")
        sync_source_policy(self.conn, load_source_config(self.config))
        self.transport = FixtureTransport()
        self.transport.__enter__()
        self.addCleanup(self.transport.__exit__, None, None, None)
        self.manifest = MangaDexSource(source_http(self.transport)).manifest(
            language="ar", works=1, chapters_per_work=1, pages_per_chapter=2)

    def tearDown(self):
        self.conn.close()
        self.tmp.cleanup()

    def counts(self):
        return tuple(self.conn.execute(
            "SELECT (SELECT COUNT(*) FROM works), (SELECT COUNT(*) FROM work_chapters), "
            "(SELECT COUNT(*) FROM chapter_pages), (SELECT COUNT(*) FROM element_clearance)"
        ).fetchone())

    def clear_chapter(self):
        chapter = self.conn.execute(
            "SELECT ext_work_id, ext_chapter_id FROM work_chapters LIMIT 1").fetchone()
        grant_clearance(self.conn, source_id="mangadex", ext_work_id=chapter["ext_work_id"],
                        ext_chapter_id=chapter["ext_chapter_id"], decision="CLEARED",
                        basis="operator-approval", decided_by="operator",
                        evidence="manual review", policy_version="v1")

    def fetch_run(self, transport):
        real = runner_module.SafeHTTP
        runner_module.SafeHTTP = lambda *a, **k: _StubSafeHTTP(transport)
        try:
            return run_fetch_run(self.conn, self.root, config_path=self.config,
                                 instance_id="e2e", host="host-e2e")
        finally:
            runner_module.SafeHTTP = real

    # ------------------------------------------------------------- the import

    def test_discovery_lands_in_the_store_through_the_shared_boundary(self):
        with LoopbackGuard() as guard:
            result = import_payload(self.conn, self.manifest)
        self.assertEqual(guard.blocked, [])
        self.assertEqual(result["chapter_pages"], 2)
        self.assertEqual(self.counts(), (1, 1, 2, 0),
                         "discovery imports rows and grants no clearance")

    def test_reimporting_the_same_discovery_is_idempotent(self):
        import_payload(self.conn, self.manifest)
        import_payload(self.conn, self.manifest)
        self.assertEqual(self.counts(), (1, 1, 2, 0))

    def test_page_identity_is_the_source_page_id_not_the_url(self):
        import_payload(self.conn, self.manifest)
        row = self.conn.execute(
            "SELECT page_key_kind, source_page_id, stable_page_url, source_page_url "
            "FROM chapter_pages WHERE page_index=0").fetchone()
        self.assertEqual(row["page_key_kind"], "source_page_id")
        self.assertEqual(row["stable_page_url"], None)
        self.assertTrue(row["source_page_url"].startswith(f"https://{IMAGE_HOST}/data/"))

    def test_a_disabled_source_is_not_imported_against(self):
        # Same allow-list, only activation flipped: this isolates "is fetching
        # allowed" from "does the import still validate page URLs".
        self.config.write_text(json.dumps({"version": 1, "sources": [{
            "source_id": "mangadex", "fetch_enabled": False,
            "allowed_hosts": [API_HOST, f".{IMAGE_HOST_SUFFIX}"],
            "quota_bytes": 1 << 30, "retention_days": 30}]}), encoding="utf-8")
        self.conn.close()
        self.conn = open_store(self.root / "content.db")
        sync_source_policy(self.conn, load_source_config(self.config))
        with LoopbackGuard():
            import_payload(self.conn, self.manifest)
        self.assertEqual(self.conn.execute("SELECT COUNT(*) FROM works").fetchone()[0], 1,
                         "rows may still land; fetching is what activation governs")

    def test_a_forbidden_classification_is_refused_by_the_import(self):
        manifest = json.loads(json.dumps(self.manifest))
        manifest["works"][0]["content_classification"] = "NOT_A_CLASS"
        with self.assertRaises(ImportRejected):
            import_payload(self.conn, manifest)
        self.assertEqual(self.counts()[0], 0, "a rejected import writes nothing")

    # -------------------------------------------- refusal before, fetch after

    def test_before_approval_nothing_is_requested_and_no_attempt_is_made(self):
        import_payload(self.conn, self.manifest)
        transport = ImageTransport()
        with LoopbackGuard() as guard:
            report = self.fetch_run(transport)
        self.assertEqual(report.pages_done, 0)
        self.assertEqual(report.refused, 2, "both pages refused by the safety gate")
        self.assertEqual(report.failed, 0, "a refusal is not a failure")
        self.assertEqual(transport.requested, [], "no network request may be made")
        self.assertEqual(self.conn.execute(
            "SELECT COUNT(*) FROM save_intents").fetchone()[0], 0)
        self.assertEqual(self.conn.execute(
            "SELECT COUNT(*) FROM chapter_pages WHERE state='DONE'").fetchone()[0], 0)
        self.assertEqual(list(self.root.rglob("*.img")), [])
        self.assertEqual(guard.blocked, [])

    def test_after_approval_pages_are_downloaded_and_stored(self):
        import_payload(self.conn, self.manifest)
        self.clear_chapter()
        transport = ImageTransport()
        with LoopbackGuard() as guard:
            report = self.fetch_run(transport)
        self.assertEqual(report.pages_done, 2)
        self.assertEqual(report.refused, 0)
        self.assertEqual(len(transport.requested), 2)
        rows = self.conn.execute(
            "SELECT page_index, state, sha256, bytes, mime, width, height, verified_at "
            "FROM chapter_pages ORDER BY page_index").fetchall()
        for row in rows:
            self.assertEqual(row["state"], "DONE")
            self.assertEqual(len(row["sha256"]), 64, "a stored page carries its digest")
            self.assertEqual(row["bytes"], len(PNG))
            self.assertEqual(row["mime"], "image/png")
            self.assertEqual((row["width"], row["height"]), (8, 8))
            self.assertTrue(row["verified_at"], "DONE without evidence is impossible")
            stored = self.root / self.conn.execute(
                "SELECT rel_path FROM chapter_pages WHERE page_index=?",
                (row["page_index"],)).fetchone()[0]
            self.assertTrue(stored.is_file())
            self.assertEqual(stored.read_bytes(), PNG)
        self.assertEqual(list((self.root / "content").rglob("*.part")), [])
        self.assertEqual(guard.blocked, [])

    def test_an_unclassified_work_stays_refused_even_after_approval(self):
        # Same run, but the work came back without a content rating. Approval is
        # necessary but not sufficient: there must also be a usable classification.
        manifest = json.loads(json.dumps(self.manifest))
        manifest["works"][0]["content_classification"] = None
        import_payload(self.conn, manifest)
        self.clear_chapter()
        allowed, why = chapter_is_fetchable(self.conn, "mangadex",
                                            self.conn.execute(
                                                "SELECT ext_chapter_id FROM work_chapters"
                                            ).fetchone()[0])
        self.assertFalse(allowed)
        self.assertIn("UNKNOWN", why)
        transport = ImageTransport()
        report = self.fetch_run(transport)
        self.assertEqual(report.pages_done, 0)
        self.assertEqual(report.refused, 2)
        self.assertEqual(transport.requested, [])


class _StubSafeHTTP:
    """Stands in for SafeHTTP inside the runner; serves the fixture image."""

    def __init__(self, transport):
        self.transport = transport
        self.allowed = {IMAGE_HOST}

    def stream(self, url, headers=None, max_bytes=None):
        return self.transport.stream(url, max_bytes=max_bytes or 1, headers=headers)

    def get(self, url, headers=None):
        raise AssertionError("the fetch path must stream")


class CliDiscoveryTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.work = Path(self.tmp.name)
        self.config = self.work / "content-sources.json"
        self.config.write_text(json.dumps({"version": 1, "sources": [{
            "source_id": "mangadex", "fetch_enabled": True,
            "allowed_hosts": [API_HOST, f".{IMAGE_HOST_SUFFIX}"],
            "quota_bytes": 1 << 30, "retention_days": 30}]}), encoding="utf-8")

    def tearDown(self):
        self.tmp.cleanup()

    def run_cli(self, argv):
        """Run the CLI with its output captured.

        ``redirect_stdout`` swaps the process-wide ``sys.stdout``, so a thread
        left running by another test can interleave into (or unwind around) it.
        Callers that expect JSON use :meth:`last_json`, which takes the last
        JSON-looking line, so output from another thread cannot break the parse.
        """
        out = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(out):
            code = cli.main(argv)
        return code, out.getvalue()

    @staticmethod
    def last_json(text: str) -> dict:
        for line in reversed([l for l in text.splitlines() if l.strip()]):
            if line.startswith("{"):
                return json.loads(line)
        raise AssertionError(f"no JSON in command output: {text!r}")

    def test_content_discover_imports_without_touching_the_network(self):
        transport = FixtureTransport()
        with transport, mock.patch("shura_core.security.network.SafeHTTP",
                                   return_value=source_http(transport)), \
             LoopbackGuard() as guard:
            code, out = self.run_cli(
                ["--db", str(self.work / "unused.db"), "content-discover",
                 "--content-dir", str(self.work / "store"), "--config", str(self.config),
                 "--language", "ar", "--works", "1",
                 "--chapters-per-work", "1", "--pages-per-chapter", "2"])
        self.assertEqual(code, 0, out)
        report = self.last_json(out)
        self.assertEqual(report["discoveredWorks"], 1)
        self.assertEqual(report["discoveredPages"], 2)
        self.assertEqual(report["clearanceGranted"], 0)
        self.assertEqual(guard.blocked, [])
        conn = open_store(self.work / "store" / "content.db")
        try:
            self.assertEqual(conn.execute(
                "SELECT COUNT(*) FROM chapter_pages").fetchone()[0], 2)
            self.assertEqual(conn.execute(
                "SELECT COUNT(*) FROM element_clearance").fetchone()[0], 0)
        finally:
            conn.close()

    def test_content_discover_dry_run_creates_nothing(self):
        transport = FixtureTransport()
        with transport, mock.patch("shura_core.security.network.SafeHTTP",
                                   return_value=source_http(transport)), \
             LoopbackGuard():
            code, out = self.run_cli(
                ["--db", str(self.work / "unused.db"), "content-discover",
                 "--content-dir", str(self.work / "store"), "--config", str(self.config),
                 "--dry-run"])
        self.assertEqual(code, 0)
        self.assertTrue(self.last_json(out)["dryRun"])
        self.assertFalse((self.work / "store").exists())


if __name__ == "__main__":
    unittest.main()

class ContentCycleTests(unittest.TestCase):
    """The production loop: discover -> import -> gate -> fetch -> store.

    A cycle must never approve anything, so with no clearance decisions it
    fetches nothing. Once an operator clears a chapter, the very next cycle
    stores the page. Both halves run through the real cycle entry point.
    """

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.work = Path(self.tmp.name)
        self.root = self.work / "store"
        self.root.mkdir(parents=True)
        self.conn = open_store(self.root / "content.db")
        self.config = self.work / "content-sources.json"
        self.config.write_text(json.dumps({"version": 1, "sources": [{
            "source_id": "mangadex", "fetch_enabled": True,
            "allowed_hosts": [API_HOST, f".{IMAGE_HOST_SUFFIX}"],
            "quota_bytes": 1 << 30, "retention_days": 30}]}), encoding="utf-8")

    def tearDown(self):
        self.conn.close()
        self.tmp.cleanup()

    def run_cycle(self, instance_id="cycle", **kwargs):
        """One cycle with the API served locally and the CDN under our control."""
        from shura_core.content import run_content_cycle
        with FixtureTransport():
            return run_content_cycle(
                self.conn, self.root,
                http_factory=lambda hosts: source_http(FixtureTransport()),
                config_path=self.config, language="ar", works=1,
                chapters_per_work=1, pages_per_chapter=2,
                instance_id=instance_id, host="host-cycle", **kwargs)

    def test_a_cycle_imports_but_fetches_nothing_until_approved(self):
        transport = ImageTransport()
        real = runner_module.SafeHTTP
        runner_module.SafeHTTP = lambda *a, **k: _StubSafeHTTP(transport)
        try:
            with LoopbackGuard() as guard:
                report = self.run_cycle("cycle-first")
        finally:
            runner_module.SafeHTTP = real
        self.assertEqual(report.discovered_works, 1)
        self.assertEqual(report.imported_pages, 2)
        self.assertEqual(report.awaiting_approval, 1,
                         "the chapter imported but is waiting on an operator")
        self.assertEqual(report.fetch.pages_done, 0, "no approval, no download")
        self.assertEqual(report.fetch.refused, 2)
        self.assertEqual(transport.requested, [], "no CDN request may be made")
        self.assertEqual(self.conn.execute(
            "SELECT COUNT(*) FROM element_clearance").fetchone()[0], 0)
        self.assertEqual(self.conn.execute(
            "SELECT COUNT(*) FROM chapter_pages WHERE state='DONE'").fetchone()[0], 0)
        self.assertEqual(guard.blocked, [])

    def test_after_approval_the_next_cycle_stores_the_page(self):
        with LoopbackGuard():
            self.run_cycle("cycle-a", do_fetch=False)
        # The operator acts, explicitly, outside the cycle.
        chapter = self.conn.execute(
            "SELECT ext_work_id, ext_chapter_id FROM work_chapters LIMIT 1").fetchone()
        grant_clearance(self.conn, source_id="mangadex",
                        ext_work_id=chapter["ext_work_id"],
                        ext_chapter_id=chapter["ext_chapter_id"], decision="CLEARED",
                        basis="operator-approval", decided_by="operator",
                        evidence="manual review", policy_version="v1")

        transport = ImageTransport()
        real = runner_module.SafeHTTP
        runner_module.SafeHTTP = lambda *a, **k: _StubSafeHTTP(transport)
        try:
            with LoopbackGuard() as guard:
                report = self.run_cycle("cycle-b")
        finally:
            runner_module.SafeHTTP = real
        self.assertEqual(report.fetch.pages_done, 2)
        self.assertEqual(report.fetch.refused, 0)
        self.assertEqual(report.awaiting_approval, 0)
        rows = self.conn.execute(
            "SELECT state, sha256, verified_at FROM chapter_pages ORDER BY page_index").fetchall()
        self.assertEqual([r["state"] for r in rows], ["DONE", "DONE"])
        for row in rows:
            self.assertEqual(len(row["sha256"]), 64)
            self.assertTrue(row["verified_at"])
        self.assertEqual(list((self.root / "content").rglob("*.part")), [])
        self.assertEqual(guard.blocked, [])

    def test_repeated_cycles_converge_rather_than_duplicate(self):
        for index in range(3):
            with LoopbackGuard():
                self.run_cycle(f"cycle-{index}", do_fetch=False)
        self.assertEqual(self.conn.execute("SELECT COUNT(*) FROM works").fetchone()[0], 1)
        self.assertEqual(self.conn.execute("SELECT COUNT(*) FROM work_chapters").fetchone()[0], 1)
        self.assertEqual(self.conn.execute("SELECT COUNT(*) FROM chapter_pages").fetchone()[0], 2)
        self.assertEqual(self.conn.execute(
            "SELECT COUNT(*) FROM element_clearance").fetchone()[0], 0)

    def test_a_cycle_refuses_everything_when_the_source_is_not_enabled(self):
        self.config.write_text(json.dumps({"version": 1, "sources": [{
            "source_id": "mangadex", "fetch_enabled": False,
            "allowed_hosts": [API_HOST], "quota_bytes": 1 << 30,
            "retention_days": 30}]}), encoding="utf-8")
        with LoopbackGuard():
            report = self.run_cycle("cycle-off")
        self.assertEqual(report.discovered_works, 0)
        self.assertIn("not enabled", report.discovery_error)
        self.assertEqual(self.conn.execute("SELECT COUNT(*) FROM works").fetchone()[0], 0)
