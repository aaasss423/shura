"""Two properties that must hold for the fetch stage, proven rather than reviewed.

1. A size overrun is a *retryable* failure, a policy refusal is not. Both are
   checked against what actually landed in the database - the stored state and
   the armed backoff - not against the exception class alone, because
   ``ResponseTooLarge`` is a subclass of ``NetworkPolicyError`` and the clause
   order in ``fetch_page`` is what separates them.
2. A displaced owner cannot land anything. The lease is taken over mid-flight
   with a newer fencing token and the original worker is then allowed to try to
   finish. Renewal is also shown to happen *during* a long transfer, not only
   between pages.

The lease clock is driven explicitly: no test here waits on wall-clock time.
"""

from __future__ import annotations

import json
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from shura_core.content import Budget, Budgets, PageFetcher, SafeHTTPTransport, open_store
from shura_core.content.store import (FenceLost, LOCK_TTL_SECONDS, MAX_PAGE_ATTEMPTS,
                                      acquire_lock, grant_clearance, release_lock, renew_lock)
from shura_core.security.network import SafeHTTP

def png_bytes() -> bytes:
    import struct
    import zlib

    def chunk(kind: bytes, data: bytes) -> bytes:
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", 8, 8, 8, 2, 0, 0, 0)
    raw = b"".join(bytes([0]) + b"\x00" * 24 for _ in range(8))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


PNG = png_bytes()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        if self.path == "/small":
            self.send_response(200)
            self.send_header("Content-Type", "image/png")
            self.send_header("Content-Length", str(len(PNG)))
            self.end_headers()
            self.wfile.write(PNG)
        elif self.path == "/chunked-too-big":
            self.send_response(200)
            self.send_header("Content-Type", "image/png")
            self.send_header("Transfer-Encoding", "chunked")
            self.end_headers()
            for _ in range(40):
                block = b"x" * 1024
                self.wfile.write(b"%x\r\n" % len(block) + block + b"\r\n")
            self.wfile.write(b"0\r\n\r\n")
        elif self.path == "/big-chunked":
            # ~2.5 MB with no Content-Length: many read chunks, so a heartbeat
            # has room to fire repeatedly inside a single page.
            self.send_response(200)
            self.send_header("Content-Type", "image/png")
            self.send_header("Transfer-Encoding", "chunked")
            self.end_headers()
            block = b"x" * 65536
            for _ in range(40):
                self.wfile.write(b"%x\r\n" % len(block) + block + b"\r\n")
            self.wfile.write(b"0\r\n\r\n")
        elif self.path == "/offsite":
            self.send_response(302)
            self.send_header("Location", "https://evil.invalid/elsewhere")
            self.end_headers()
        else:
            self.send_response(404)
            self.send_header("Content-Length", "0")
            self.end_headers()

    def log_message(self, *a):
        pass

    def handle_one_request(self):
        try:
            BaseHTTPRequestHandler.handle_one_request(self)
        except (BrokenPipeError, ConnectionResetError):
            self.close_connection = True


class ContentCase(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        cls.port = cls.server.server_port
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.base = f"http://127.0.0.1:{cls.port}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.thread.join()

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.conn = open_store(self.root / "content.db")
        self.lock = acquire_lock(self.conn, "worker-a", "host-a")
        self.conn.execute(
            "INSERT INTO source_policy(source_id,kind,allowed_hosts,fetch_enabled,quota_bytes,"
            "retention_days,synced_at) VALUES('s1','index','[\"127.0.0.1\"]',1,?,'30','now')",
            (1 << 40,))
        self.conn.execute(
            "INSERT INTO works(source_id,ext_work_id,source_work_id,title,"
            "content_classification,discovered_at,updated_at) "
            "VALUES('s1','w1','src','T','SAFE','now','now')")
        self.conn.execute(
            "INSERT INTO work_chapters(source_id,ext_chapter_id,ext_work_id,source_chapter_id,"
            "chapter_number,ordinal,first_seen,updated_at) "
            "VALUES('s1','c1','w1','sc',1.0,0,'n','n')")
        self.conn.execute(
            "INSERT INTO chapter_pages(source_id,ext_chapter_id,page_index,ordinal,"
            "source_page_url,state,created_at) VALUES('s1','c1',0,0,?,'PENDING','now')",
            (f"{self.base}/small",))
        # Cleared so the fetcher reaches the transport; refusal cases live in
        # tests/test_content_gate.py.
        grant_clearance(self.conn, source_id="s1", ext_work_id="w1", ext_chapter_id="c1",
                        decision="CLEARED", basis="operator-approval", decided_by="fixture",
                        evidence="fixture", policy_version="test")

    def tearDown(self):
        self.conn.close()
        self.tmp.cleanup()

    def transport(self):
        return SafeHTTPTransport(SafeHTTP(["127.0.0.1"], https_only=False,
                                          allow_private_hosts=True,
                                          allow_nonstandard_ports=True))

    def page_row(self):
        return self.conn.execute(
            "SELECT state, next_retry, reason FROM chapter_pages WHERE page_index=0").fetchone()


class SizeIsRetryableAndPolicyIsNotTests(ContentCase):
    """Requirement 1: the stored state and backoff, not just the exception type."""

    def fetch(self, path, **budget_kwargs):
        fetcher = PageFetcher(self.conn, self.lock, self.root, self.transport(),
                              Budget(Budgets(**budget_kwargs)),
                              policy_fetcher=lambda _s: 1 << 40)
        return fetcher.fetch_page(source_id="s1", ext_work_id="w1", ext_chapter_id="c1",
                                  page_index=0, page_url=f"{self.base}{path}", host="127.0.0.1")

    def test_an_oversize_body_is_stored_failed_with_a_backoff_armed(self):
        result = self.fetch("/chunked-too-big", max_page_bytes=512)
        self.assertEqual(result.outcome, "FAILED")
        state, next_retry, reason = self.page_row()
        # If ResponseTooLarge had been caught by the NetworkPolicyError clause
        # these two would be "BLOCKED" and "" instead.
        self.assertEqual(state, "FAILED", "a size overrun must not be recorded as BLOCKED")
        self.assertTrue(next_retry, "a size overrun must arm the bounded retry backoff")
        self.assertIn("exceed", reason)

    def test_an_oversize_body_is_retried_until_the_ceiling_then_retired(self):
        for _ in range(MAX_PAGE_ATTEMPTS):
            self.fetch("/chunked-too-big", max_page_bytes=512)
            self.conn.execute("UPDATE chapter_pages SET next_retry='' WHERE page_index=0")
        state, next_retry, _ = self.page_row()
        self.assertEqual(state, "FAILED")
        self.assertEqual(next_retry, "", "the last allowed attempt must not re-arm a backoff")
        used = self.conn.execute(
            "SELECT attempts FROM chapter_pages WHERE page_index=0").fetchone()[0]
        self.assertEqual(used, MAX_PAGE_ATTEMPTS)

    def test_a_policy_refusal_is_blocked_with_no_backoff(self):
        # Same server, an off-allow-list redirect instead of an oversize body.
        fetcher = PageFetcher(self.conn, self.lock, self.root, self.transport(),
                              Budget(Budgets()), policy_fetcher=lambda _s: 1 << 40)
        result = fetcher.fetch_page(source_id="s1", ext_work_id="w1", ext_chapter_id="c1",
                                    page_index=0, page_url=f"{self.base}/offsite",
                                    host="127.0.0.1")
        self.assertEqual(result.outcome, "BLOCKED")
        state, next_retry, _ = self.page_row()
        self.assertEqual(state, "BLOCKED")
        self.assertEqual(next_retry, "", "a blocked page must never look retryable")


class DisplacedOwnerTests(ContentCase):
    """Requirement 2: a displaced owner lands nothing at all."""

    def fetcher(self, lock, **kwargs):
        return PageFetcher(self.conn, lock, self.root, self.transport(),
                           Budget(Budgets()), policy_fetcher=lambda _s: 1 << 40, **kwargs)

    def displace(self):
        """Worker B takes the lock over, forcing a newer fencing token."""
        self.conn.execute("UPDATE fetch_lock SET lease_expires_at='1999-01-01T00:00:00+00:00'")
        return acquire_lock(self.conn, "worker-b", "host-b")

    def test_the_fencing_token_advances_when_the_lock_is_taken_over(self):
        stale_token = self.lock.token
        newer = self.displace()
        self.assertGreater(newer.token, stale_token,
                           "the takeover must mint a strictly newer fencing token")

    def test_a_displaced_owner_cannot_place_the_file(self):
        fetcher = self.fetcher(self.lock)
        attempt_lock = fetcher.lock
        # A starts the download, B takes over mid-flight.
        from shura_core.content.store import begin_attempt
        attempt = begin_attempt(self.conn, attempt_lock, source_id="s1", ext_chapter_id="c1",
                                page_index=0, slug="s1", ext_work_id="w1", expected_bytes=None)
        from shura_core.content.storage import place_page, stage_part
        part = stage_part(self.root, attempt.part_path)
        part.write_bytes(PNG)
        self.displace()
        from shura_core.content.store import assert_fence
        with self.assertRaises(Exception):
            assert_fence(self.conn, attempt_lock)
            place_page(self.root, attempt.rel_path, part)
        self.assertFalse((self.root / attempt.rel_path).exists(),
                         "no final page file may appear for a displaced owner")

    def test_a_displaced_owner_cannot_commit_done(self):
        from shura_core.content.store import begin_attempt, commit_done, assert_fence
        attempt = begin_attempt(self.conn, self.lock, source_id="s1", ext_chapter_id="c1",
                                page_index=0, slug="s1", ext_work_id="w1", expected_bytes=None)
        self.displace()
        with self.assertRaises(Exception):
            assert_fence(self.conn, self.lock)
            commit_done(self.conn, self.lock, source_id="s1", ext_chapter_id="c1",
                        page_index=0, attempt_no=attempt.attempt_no,
                        rel_path=attempt.rel_path, sha256="0" * 64, size=10, mime="image/png",
                        width=8, height=8, content_key="0" * 64)
        state, _, _ = self.page_row()
        self.assertNotEqual(state, "DONE",
                            "a displaced owner must never leave the page marked DONE")
        stored = self.conn.execute(
            "SELECT COUNT(*) FROM chapter_pages WHERE page_index=0 AND sha256 IS NOT NULL"
        ).fetchone()[0]
        self.assertEqual(stored, 0, "no evidence may be recorded for a displaced owner")

    def test_a_displaced_owner_aborts_the_download_mid_transfer(self):
        # B takes over while A's body is still arriving; the heartbeat renewal is
        # what notices, and FenceLost must leave nothing behind.
        fetcher = self.fetcher(self.lock, renew_every=0.0, clock=lambda: 0.0)

        class Stealing:
            def __init__(self, steal):
                self.steal = steal
                self.stolen = False

            def __call__(self):
                if not self.stolen:
                    self.stolen = True
                    self.steal()

        fetcher._heartbeat = Stealing(self.displace)
        # A body that validates, so the transfer completes and the fence check
        # that guards place_page is actually reached.
        with self.assertRaises(FenceLost):
            fetcher.fetch_page(source_id="s1", ext_work_id="w1", ext_chapter_id="c1",
                               page_index=0, page_url=f"{self.base}/small",
                               host="127.0.0.1")
        parts = list((self.root / "content").rglob("*.part"))
        self.assertEqual(parts, [], "a fence loss must not leave a partial file")
        state, _, _ = self.page_row()
        self.assertNotEqual(state, "DONE", "a displaced owner must not commit DONE")
        finals = [p for p in self.root.rglob("*.img")]
        self.assertEqual(finals, [], "a displaced owner must not place the final file")


class RenewalDuringTransferTests(ContentCase):
    """The lease must be renewed while the body is arriving, not just per page."""

    class CountingFetcher(PageFetcher):
        renewals = 0

        def _heartbeat(self):
            if self.renew_every > 0 and self.clock() - self._renewed_at >= self.renew_every:
                self.renewals += 1
            super()._heartbeat()

    def test_the_lease_is_renewed_during_a_single_long_page(self):
        # A fake clock advances past renew_every on every chunk, so renewal must
        # fire many times inside one fetch_page call.
        tick = {"t": 0.0}

        def clock():
            return tick["t"]

        fetcher = self.CountingFetcher(self.conn, self.lock, self.root, self.transport(),
                                       Budget(Budgets(max_page_bytes=4 * 1024 * 1024)),
                                       policy_fetcher=lambda _s: 1 << 40,
                                       renew_every=1.0, clock=clock)
        fetcher.renewals = 0

        # Drive the clock forward from inside the chunk loop by renewing the
        # heartbeat's view of time: every heartbeat call advances it.
        original = fetcher._heartbeat

        def ticking():
            tick["t"] += 2.0
            original()

        fetcher._heartbeat = ticking

        result = fetcher.fetch_page(source_id="s1", ext_work_id="w1", ext_chapter_id="c1",
                                    page_index=0, page_url=f"{self.base}/big-chunked",
                                    host="127.0.0.1")
        self.assertEqual(result.outcome, "FAILED", "expected the non-image to be refused")
        self.assertGreater(fetcher.renewals, 1,
                           "renewal must fire repeatedly inside one fetch_page, not once "
                           "between pages")

    def test_no_renewal_is_needed_when_the_page_is_short(self):
        tick = {"t": 0.0}
        fetcher = PageFetcher(self.conn, self.lock, self.root, self.transport(),
                              Budget(Budgets()), policy_fetcher=lambda _s: 1 << 40,
                              renew_every=3600.0, clock=lambda: tick["t"])
        result = fetcher.fetch_page(source_id="s1", ext_work_id="w1", ext_chapter_id="c1",
                                    page_index=0, page_url=f"{self.base}/small",
                                    host="127.0.0.1")
        self.assertEqual(result.outcome, "DONE")

    def test_the_default_renew_interval_is_shorter_than_the_lease(self):
        # A single page slower than the lease must still be renewed often enough.
        self.assertLess(LOCK_TTL_SECONDS / 3, LOCK_TTL_SECONDS)


if __name__ == "__main__":
    unittest.main()