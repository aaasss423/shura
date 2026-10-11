"""Tests for the production HTTP path: bounded streaming, error separation, and
activation from the local content-sources.json.

A real loopback HTTP server is used so ``SafeHTTP.stream`` is exercised over a
socket - chunked, undeclared-size bodies included - rather than against a stub
that could not catch a lifetime or an early-close bug. Nothing here reaches the
internet: the server binds 127.0.0.1 and the client is constructed with
``allow_private_hosts=True`` purely so the guard permits the loopback fixture.
"""

from __future__ import annotations

import json
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from shura_core.content import Budget, Budgets, PageFetcher, SafeHTTPTransport, open_store
from shura_core.content.runner import run_fetch_run
from shura_core.content.store import acquire_lock, grant_clearance, release_lock
from shura_core.content.storage import write_bounded
from shura_core.security.network import (NetworkPolicyError, ResponseTooLarge, SafeHTTP,
                                         validate_url)

def _png(width: int = 8, height: int = 8) -> bytes:
    import struct
    import zlib

    def chunk(kind: bytes, data: bytes) -> bytes:
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    raw = b"".join(bytes([0]) + b"\x00" * (width * 3) for _ in range(height))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


PNG = _png()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        if self.path == "/small":
            body, kind = PNG, "image/png"
            self.send_response(200)
            self.send_header("Content-Type", kind)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif self.path == "/declared-too-big":
            # Declared oversize: must be refused before a single byte is read.
            self.send_response(200)
            self.send_header("Content-Type", "image/png")
            self.send_header("Content-Length", "100000")
            self.end_headers()
            self.wfile.write(b"x" * 100000)
        elif self.path == "/chunked-too-big":
            # No Content-Length at all, sent in chunks well past the ceiling.
            self.send_response(200)
            self.send_header("Content-Type", "image/png")
            self.send_header("Transfer-Encoding", "chunked")
            self.end_headers()
            for _ in range(40):
                block = b"x" * 1024
                self.wfile.write(b"%x\r\n" % len(block) + block + b"\r\n")
            self.wfile.write(b"0\r\n\r\n")
        elif self.path == "/offsite":
            self.send_response(302)
            self.send_header("Location", "https://evil.invalid/elsewhere")
            self.end_headers()
        elif self.path == "/loop":
            self.send_response(302)
            self.send_header("Location", self.path)
            self.end_headers()
        else:
            self.send_response(404)
            self.send_header("Content-Length", "0")
            self.end_headers()

    def log_message(self, *a):
        pass

    def handle_one_request(self):
        # A test deliberately closes the connection mid-body; the server must
        # not print a traceback for it.
        try:
            BaseHTTPRequestHandler.handle_one_request(self)
        except (BrokenPipeError, ConnectionResetError):
            self.close_connection = True


class StreamTests(unittest.TestCase):
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

    def http(self, **kwargs):
        # https_only=False and allow_private_hosts are needed only so the guard
        # permits this loopback, plaintext test server. The defaults stay strict
        # in SafeHTTP itself.
        return SafeHTTP(["127.0.0.1"], https_only=False, allow_private_hosts=True,
                        allow_nonstandard_ports=True, **kwargs)

    def test_a_declared_oversize_is_refused_before_reading(self):
        with self.assertRaises(ResponseTooLarge):
            with self.http(max_bytes=1000).stream(f"{self.base}/declared-too-big",
                                                   max_bytes=1000) as _:
                pass

    def test_an_undeclared_oversize_is_stopped_while_streaming(self):
        pulled = 0
        with self.assertRaises(NetworkPolicyError):
            with self.http().stream(f"{self.base}/chunked-too-big", max_bytes=4096) as (_h, _u,
                                                                                        chunks, _l):
                for block in chunks:
                    pulled += len(block)
        self.assertLessEqual(pulled, 4097,
                             "the transfer must stop at the ceiling, not run to the end")

    def test_a_declared_oversize_is_not_a_policy_refusal(self):
        # The size case must stay distinguishable from a host/redirect refusal.
        self.assertTrue(issubclass(ResponseTooLarge, NetworkPolicyError))
        try:
            with self.http(max_bytes=1000).stream(f"{self.base}/declared-too-big",
                                                   max_bytes=1000):
                pass
        except NetworkPolicyError as exc:
            self.assertIsInstance(exc, ResponseTooLarge)

    def test_the_stream_cannot_be_used_after_the_context_exits(self):
        # The response must not outlive the with-block. If it did, iterating
        # afterwards would keep reading a live socket instead of dying.
        cm = self.http().stream(f"{self.base}/chunked-too-big", max_bytes=8192)
        _headers, _final, chunks, _limit = cm.__enter__()
        first = next(chunks)
        self.assertGreater(len(first), 0)
        cm.__exit__(None, None, None)
        with self.assertRaises(StopIteration):
            next(chunks)

    def test_a_valid_body_streams_through_to_a_file(self):
        tmp = tempfile.TemporaryDirectory()
        try:
            target = Path(tmp.name) / "p.part"
            with self.http().stream(f"{self.base}/small", max_bytes=8192) as (_h, _u, chunks,
                                                                              _l):
                write_bounded(target, chunks, max_bytes=8192)
            self.assertEqual(target.read_bytes(), PNG)
        finally:
            tmp.cleanup()

    def test_redirect_guards_still_fire_on_the_streaming_path(self):
        for path in ("/offsite", "/loop"):
            with self.assertRaises(NetworkPolicyError):
                with self.http().stream(f"{self.base}{path}", max_bytes=8192):
                    pass


class BlockedVersusFailedTests(unittest.TestCase):
    """A policy refusal is BLOCKED; a size or content problem is FAILED."""

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
        self.lock = acquire_lock(self.conn, "instance-a", "host-a")
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
            "source_page_url,state,created_at) "
            "VALUES('s1','c1',0,0,?,'PENDING','now')", (f"{self.base}/small",))
        # Cleared so these cases reach the transport at all; the gate's refusal
        # behaviour is covered in tests/test_content_gate.py.
        grant_clearance(self.conn, source_id="s1", ext_work_id="w1", ext_chapter_id="c1",
                        decision="CLEARED", basis="operator-approval", decided_by="fixture",
                        evidence="fixture", policy_version="test")

    def tearDown(self):
        release_lock(self.conn, self.lock)
        self.conn.close()
        self.tmp.cleanup()

    def fetch(self, budget=None, path="/small"):
        http = SafeHTTP(["127.0.0.1"], https_only=False, allow_private_hosts=True,
                        allow_nonstandard_ports=True)
        fetcher = PageFetcher(self.conn, self.lock, self.root, SafeHTTPTransport(http),
                              budget or Budget(Budgets()),
                              policy_fetcher=lambda _s: 1 << 40)
        return fetcher.fetch_page(source_id="s1", ext_work_id="w1", ext_chapter_id="c1",
                                  page_index=0, page_url=f"{self.base}{path}",
                                  host="127.0.0.1")

    def state(self):
        return self.conn.execute(
            "SELECT state FROM chapter_pages WHERE page_index=0").fetchone()[0]

    def test_a_host_off_the_allow_list_is_blocked_not_failed(self):
        fetcher = PageFetcher(self.conn, self.lock, self.root,
                              SafeHTTPTransport(SafeHTTP(["example.org"])),
                              Budget(Budgets()), policy_fetcher=lambda _s: 1 << 40)
        result = fetcher.fetch_page(source_id="s1", ext_work_id="w1", ext_chapter_id="c1",
                                    page_index=0, page_url=f"{self.base}/small",
                                    host="127.0.0.1")
        self.assertEqual(result.outcome, "BLOCKED")
        self.assertEqual(self.state(), "BLOCKED")

    def test_a_blocked_page_gets_no_backoff_and_is_not_retried(self):
        fetcher = PageFetcher(self.conn, self.lock, self.root,
                              SafeHTTPTransport(SafeHTTP(["example.org"])),
                              Budget(Budgets()), policy_fetcher=lambda _s: 1 << 40)
        fetcher.fetch_page(source_id="s1", ext_work_id="w1", ext_chapter_id="c1",
                           page_index=0, page_url=f"{self.base}/small", host="127.0.0.1")
        row = self.conn.execute(
            "SELECT state, next_retry FROM chapter_pages WHERE page_index=0").fetchone()
        self.assertEqual(row[0], "BLOCKED")
        self.assertEqual(row[1], "", "a blocked page must never look retryable")
        again = fetcher.fetch_page(source_id="s1", ext_work_id="w1", ext_chapter_id="c1",
                                   page_index=0, page_url=f"{self.base}/small",
                                   host="127.0.0.1")
        self.assertEqual(again.outcome, "WAITING")
        self.assertIn("not startable", again.reason)

    def test_an_oversize_response_fails_without_being_blocked(self):
        # /chunked-too-big declares no length and streams far past the ceiling.
        result = self.fetch(Budget(Budgets(max_page_bytes=512)), path="/chunked-too-big")
        self.assertEqual(result.outcome, "FAILED")
        self.assertEqual(self.state(), "FAILED")

    def test_a_declared_oversize_response_fails_without_being_blocked(self):
        result = self.fetch(Budget(Budgets(max_page_bytes=512)), path="/declared-too-big")
        self.assertEqual(result.outcome, "FAILED")
        self.assertEqual(self.state(), "FAILED")

    def test_a_blocked_redirect_is_blocked_not_failed(self):
        result = self.fetch(path="/offsite")
        self.assertEqual(result.outcome, "BLOCKED")
        self.assertEqual(self.state(), "BLOCKED")

    def test_a_valid_page_still_completes(self):
        result = self.fetch()
        self.assertEqual(result.outcome, "DONE")
        self.assertEqual(self.state(), "DONE")


class ActivationTests(unittest.TestCase):
    """Activation comes from content-sources.json only, and is default-deny."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.root = self.dir / "content"
        self.root.mkdir(parents=True)
        self.conn = open_store(self.root / "content.db")

    def tearDown(self):
        self.conn.close()
        self.tmp.cleanup()

    def write_config(self, entries):
        path = self.dir / "content-sources.json"
        path.write_text(json.dumps({"version": 1, "sources": entries}), encoding="utf-8")
        return path

    def entry(self, **over):
        base = {"source_id": "s1", "fetch_enabled": False, "allowed_hosts": ["example.org"],
                "quota_bytes": 1 << 30, "retention_days": 30}
        base.update(over)
        return base

    def test_a_missing_config_fetches_nothing(self):
        report = run_fetch_run(self.conn, self.root,
                               config_path=self.dir / "absent.json")
        self.assertEqual(report.sources_considered, 0)
        self.assertEqual(report.sources_fetched, [])

    def test_a_source_that_is_not_enabled_is_not_fetched(self):
        path = self.write_config([self.entry(fetch_enabled=False)])
        report = run_fetch_run(self.conn, self.root, config_path=path)
        self.assertEqual(report.sources_considered, 1)
        self.assertEqual(report.sources_fetched, [],
                         "a disabled source must never be constructed")

    def test_a_source_absent_from_the_file_is_not_fetched(self):
        path = self.write_config([self.entry(source_id="other", fetch_enabled=True)])
        report = run_fetch_run(self.conn, self.root, config_path=path)
        self.assertNotIn("s1", report.sources_fetched)

    def test_an_invalid_config_fetches_nothing(self):
        path = self.dir / "bad.json"
        path.write_text("{ not json", encoding="utf-8")
        self.assertEqual(run_fetch_run(self.conn, self.root, config_path=path)
                         .sources_fetched, [])

    def test_activation_never_reads_the_source_policy_table(self):
        # A DB row says enabled; the file says nothing. Default-deny must win.
        self.conn.execute(
            "INSERT INTO source_policy(source_id,kind,allowed_hosts,fetch_enabled,quota_bytes,"
            "retention_days,synced_at) VALUES('s1','index','[\"example.org\"]',1,?,'30','now')",
            (1 << 40,))
        self.conn.commit()
        report = run_fetch_run(self.conn, self.root, config_path=self.dir / "absent.json")
        self.assertEqual(report.sources_fetched, [],
                         "the database must not be able to enable fetching")

    def test_the_lock_is_released_after_a_run(self):
        path = self.write_config([self.entry(fetch_enabled=True)])
        run_fetch_run(self.conn, self.root, config_path=path)
        row = self.conn.execute("SELECT owner_instance FROM fetch_lock WHERE lock_id=1").fetchone()
        self.assertIsNone(row[0], "the lock must be released in finally")

    def test_a_second_run_is_locked_out_while_the_first_holds_the_lock(self):
        acquire_lock(self.conn, "holder", "host-h")
        try:
            path = self.write_config([self.entry(fetch_enabled=True)])
            report = run_fetch_run(self.conn, self.root, config_path=path)
            self.assertTrue(report.locked_out)
        finally:
            self.conn.execute("UPDATE fetch_lock SET owner_instance=NULL WHERE lock_id=1")


class GuardPreservationTests(unittest.TestCase):
    """stream() must not be a way around any guard get() enforces."""

    def test_validate_url_still_refuses_everything_it_refused(self):
        for url in ("http://127.0.0.1/p.png", "https://10.1.2.3/p.png",
                    "https://user:pw@example.org/p.png", "https://example.org:8443/p.png"):
            with self.assertRaises(NetworkPolicyError):
                validate_url(url, ["127.0.0.1", "10.1.2.3", "example.org"])


if __name__ == "__main__":
    unittest.main()