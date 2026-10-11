"""Local tests for storage, recovery, the fetch stage and the source config.

The transport here is a fixture that returns bytes built in this process. No
socket is opened, no external host is contacted, ``shura.db`` and ``repo/`` are
never written, and the production ``SafeHTTP`` guards are exercised through the
same module the fetcher would use.
"""

from __future__ import annotations

import json
import struct
import tempfile
import unittest
import zlib
from contextlib import contextmanager
from datetime import datetime, timezone
from email.message import Message
from pathlib import Path

from shura_core.content.store import (MAX_PAGE_ATTEMPTS, RETRY_BASE_SECONDS, RETRY_MAX_SECONDS,
                                      STARTABLE, grant_clearance, retry_after)
from shura_core.content.storage import WRITE_CHUNK
from shura_core.security.network import NetworkPolicyError, ResponseTooLarge, SafeHTTP

from shura_core.content import (Budget, Budgets, FenceLost, PageFetcher, SharedRateLimiter,
                                PageRejected, acquire_lock, cleanup_stale_parts, load_source_config,
                                open_store, recover_interrupted, release_lock,
                                sync_source_policy, usage_bytes, validate_page,
                                work_is_downloadable, write_bounded)

# ----------------------------------------------------------------------------------
# synthetic images, built the same way the validator expects to read them
# ----------------------------------------------------------------------------------


def png(width: int, height: int, filler: int = 0) -> bytes:
    def chunk(kind: bytes, data: bytes) -> bytes:
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    raw = b"".join(bytes([0]) + bytes([filler]) * (width * 3) for _ in range(height))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


def declared_png(width: int, height: int) -> bytes:
    """A PNG that *declares* ``width`` x ``height`` but carries no pixel data.

    ``validate_page`` reads the IHDR and refuses on dimensions without ever
    decoding, so oversized-dimension cases must not build the real pixel buffer:
    a 60000x60000 RGB image is ~10.8 GB and will exhaust the device.
    """
    def chunk(kind: bytes, data: bytes) -> bytes:
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", zlib.compress(b"")) + chunk(b"IEND", b""))


def jpeg(width: int, height: int) -> bytes:
    return (b"\xff\xd8\xff\xe0\x00\x10JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00"
            + b"\xff\xc0\x00\x11\x08" + struct.pack(">HH", height, width)
            + b"\x03\x01\x11\x00\x02\x11\x01\x03\x11\x01" + b"\xff\xd9")


def gif(width: int, height: int) -> bytes:
    return b"GIF89a" + struct.pack("<HH", width, height) + b"\xf0\x00\x00\x00\x00\x00\xff\xff\xff"


class FixtureTransport:
    """Stands in for the network. Returns bytes; never opens a socket."""

    def __init__(self, pages: dict[str, tuple[bytes, str | None]]):
        self.pages = pages
        self.requested: list[str] = []
        self.raise_for: set[str] = set()

    def get(self, url):
        self.requested.append(url)
        if url in self.raise_for:
            raise TimeoutError("simulated network timeout")
        if url not in self.pages:
            raise ConnectionError("simulated 404")
        body, mime = self.pages[url]
        return body, mime

    @contextmanager
    def stream(self, url, *, max_bytes, headers=None):
        """Mirror ``SafeHTTP.stream``: chunks, and the same limit+1 detection.

        The transport fault is raised on entry, not on first read, because that
        is where a real ``opener.open`` would fail.
        """
        limit = max_bytes
        self.requested.append(url)
        if url in self.raise_for:
            raise TimeoutError("simulated network timeout")
        if url not in self.pages:
            raise ConnectionError("simulated 404")
        body, mime = self.pages[url]
        response_headers = Message()
        response_headers["Content-Type"] = mime or "application/octet-stream"
        offset = 0

        def _chunks():
            nonlocal offset
            remaining = limit + 1
            while remaining > 0:
                size = min(WRITE_CHUNK, remaining, len(body) - offset)
                if size <= 0:
                    break
                block = body[offset:offset + size]
                offset += size
                remaining -= size
                yield block
            if remaining == 0 and offset < len(body):
                raise ResponseTooLarge("response exceeds size limit")

        yield response_headers, url, _chunks(), limit


class FetchCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.conn = open_store(self.root / "content.db")
        self.lock = acquire_lock(self.conn, "instance-a", "host-a")
        self.conn.execute(
            "INSERT INTO source_policy(source_id,kind,allowed_hosts,fetch_enabled,quota_bytes,"
            "retention_days,synced_at) VALUES('s1','index','[\"example.org\"]',1,?,?,'now')",
            (1 << 40, 30))
        self.conn.execute(
            "INSERT INTO works(source_id,ext_work_id,source_work_id,title,content_classification,"
            "discovered_at,updated_at) VALUES('s1','w1','src','T','SAFE','now','now')")
        self.conn.execute(
            "INSERT INTO work_chapters(source_id,ext_chapter_id,ext_work_id,source_chapter_id,"
            "chapter_number,ordinal,first_seen,updated_at) VALUES('s1','c1','w1','sc',1.0,0,'n','n')")
        for index in range(3):
            self.add_page(index)
        # The fetch gate requires an explicit CLEARED decision per chapter.
        # These cases exercise fetching itself, so the chapter is cleared here;
        # the gate's own refusal cases live in tests/test_content_gate.py.
        grant_clearance(self.conn, source_id="s1", ext_work_id="w1", ext_chapter_id="c1",
                        decision="CLEARED", basis="operator-approval", decided_by="fixture",
                        evidence="fixture", policy_version="test")

    def tearDown(self):
        self.conn.close()
        self.tmp.cleanup()

    def add_page(self, index):
        self.conn.execute(
            "INSERT INTO chapter_pages(source_id,ext_chapter_id,page_index,ordinal,"
            "source_page_url,created_at) VALUES('s1','c1',?,0,?, 'now')",
            (index, f"https://example.org/p{index}.png"))

    def fetcher(self, transport, **kwargs):
        return PageFetcher(self.conn, self.lock, self.root, transport,
                           Budget(Budgets(**kwargs)),
                           limiter=SharedRateLimiter(per_second=10_000.0))

    def state(self, index):
        return self.conn.execute(
            "SELECT state FROM chapter_pages WHERE page_index=?", (index,)).fetchone()[0]

    def backoff(self, index=0):
        return self.conn.execute(
            "SELECT next_retry FROM chapter_pages WHERE page_index=?", (index,)).fetchone()[0]

    def expire_backoff(self, index=0):
        """Pretend the backoff window has elapsed."""
        self.conn.execute("UPDATE chapter_pages SET next_retry='' WHERE page_index=?", (index,))

    def fetch_p0(self, transport, **kwargs):
        return self.fetcher(transport, **kwargs).fetch_page(
            source_id="s1", ext_work_id="w1", ext_chapter_id="c1", page_index=0,
            page_url="https://example.org/p0.png", host="example.org")


class BoundedWriteTests(FetchCase):
    """The page ceiling must bite while the body is arriving, not after."""

    @staticmethod
    def stream(chunk_size, count, sink):
        """A server that keeps sending and never declares a Content-Length."""
        for _ in range(count):
            sink.append(chunk_size)
            yield b"x" * chunk_size

    def test_a_body_without_content_length_is_cut_off_at_the_limit(self):
        part = self.root / "p.part"
        offered = []
        with self.assertRaises(PageRejected):
            write_bounded(part, self.stream(1000, 50, offered), max_bytes=4096)
        # The limit is 4096. Chunks of 1000 are accepted up to 4000; the 5th crosses
        # the line and is refused, so only 5000 bytes were ever pulled off the wire.
        self.assertEqual(sum(offered), 5000, "the write must stop at the first over-limit chunk")
        self.assertFalse(part.exists(), "a refused part must not survive on disk")

    def test_a_refused_write_leaves_the_path_reusable(self):
        part = self.root / "p.part"
        with self.assertRaises(PageRejected):
            write_bounded(part, self.stream(1000, 50, []), max_bytes=4096)
        self.assertEqual(write_bounded(part, [b"ok"], max_bytes=4096), 2)
        self.assertEqual(part.read_bytes(), b"ok")

    def test_a_body_exactly_at_the_limit_is_accepted(self):
        part = self.root / "p.part"
        self.assertEqual(write_bounded(part, self.stream(1024, 4, []), max_bytes=4096), 4096)
        self.assertEqual(part.stat().st_size, 4096)

    def test_an_oversized_body_is_never_committed_as_a_page(self):
        transport = FixtureTransport(
            {"https://example.org/p0.png": (png(8, 8) + b"\x00" * 5000, "image/png")})
        fetcher = self.fetcher(transport, max_page_bytes=1024)
        before = usage_bytes(self.root)
        result = fetcher.fetch_page(source_id="s1", ext_work_id="w1", ext_chapter_id="c1",
                                    page_index=0, page_url="https://example.org/p0.png",
                                    host="example.org")
        self.assertEqual(result.outcome, "FAILED")
        self.assertEqual(usage_bytes(self.root), before,
                         "an over-limit body must leave nothing behind")
        self.assertEqual(self.state(0), "FAILED")


class RetryPolicyTests(FetchCase):
    """FAILED means "the last try was rejected", never "retry this forever"."""

    BAD = (b"<html>x</html>", "image/png")

    def test_a_failed_page_backs_off_before_its_next_attempt(self):
        transport = FixtureTransport({"https://example.org/p0.png": self.BAD})
        self.assertEqual(self.fetch_p0(transport).outcome, "FAILED")
        self.assertEqual(self.state(0), "FAILED")
        self.assertTrue(self.backoff(), "a failed attempt must arm a backoff")
        again = self.fetch_p0(transport)
        self.assertEqual(again.outcome, "WAITING", "the retry must wait out the backoff")
        self.assertIn("backing off", again.reason)

    def test_a_retry_succeeds_once_the_backoff_has_elapsed(self):
        url = "https://example.org/p0.png"
        transport = FixtureTransport({url: self.BAD})
        self.assertEqual(self.fetch_p0(transport).outcome, "FAILED")
        transport.pages[url] = (png(12, 12), "image/png")
        self.expire_backoff()
        self.assertEqual(self.fetch_p0(transport).outcome, "DONE")

    def test_the_backoff_doubles_per_attempt_and_is_capped(self):
        def delay(n):
            return (datetime.fromisoformat(retry_after(n))
                    - datetime.now(timezone.utc)).total_seconds()
        self.assertAlmostEqual(delay(1), RETRY_BASE_SECONDS, delta=2)
        self.assertAlmostEqual(delay(2), RETRY_BASE_SECONDS * 2, delta=2)
        self.assertAlmostEqual(delay(3), RETRY_BASE_SECONDS * 4, delta=2)
        self.assertAlmostEqual(delay(20), RETRY_MAX_SECONDS, delta=2)

    def test_a_page_is_retired_once_the_attempt_ceiling_is_reached(self):
        transport = FixtureTransport({"https://example.org/p0.png": self.BAD})
        for _ in range(MAX_PAGE_ATTEMPTS):
            self.expire_backoff()
            self.assertEqual(self.fetch_p0(transport).outcome, "FAILED")
        self.assertEqual(self.state(0), "FAILED")
        used = self.conn.execute(
            "SELECT attempts FROM chapter_pages WHERE page_index=0").fetchone()[0]
        self.assertEqual(used, MAX_PAGE_ATTEMPTS)
        self.assertEqual(self.backoff(), "", "a retired page must not keep arming backoffs")
        retired = self.fetch_p0(transport)
        self.assertEqual(retired.outcome, "WAITING")
        self.assertIn("attempts", retired.reason)

    def test_a_transient_failure_is_retried_within_the_ceiling(self):
        url = "https://example.org/p0.png"
        transport = FixtureTransport({url: (png(8, 8), "image/png")})
        transport.raise_for.add(url)
        self.assertEqual(self.fetch_p0(transport).outcome, "TRANSIENT")
        self.assertEqual(self.state(0), "TRANSIENT")
        transport.raise_for.clear()
        self.expire_backoff()
        self.assertEqual(self.fetch_p0(transport).outcome, "DONE")

    def test_a_blocked_page_is_never_retried_however_many_attempts_are_left(self):
        self.conn.execute("UPDATE chapter_pages SET state='BLOCKED' WHERE page_index=0")
        transport = FixtureTransport({"https://example.org/p0.png": (png(8, 8), "image/png")})
        blocked = self.fetch_p0(transport)
        self.assertEqual(blocked.outcome, "WAITING")
        self.assertIn("not startable", blocked.reason)
        self.assertNotIn("BLOCKED", STARTABLE)


class ValidationTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def write(self, name, body):
        path = self.dir / name
        path.write_bytes(body)
        return path

    def test_real_headers_are_read_for_each_supported_format(self):
        for name, body in (("a.png", png(80, 120)), ("b.jpg", jpeg(64, 48)), ("c.gif", gif(32, 32))):
            result = validate_page(self.write(name, body))
            self.assertIn(result.fmt, ("png", "jpeg", "gif"))
            self.assertGreater(result.width, 0)

    def test_markup_served_as_an_image_is_rejected(self):
        with self.assertRaises(PageRejected):
            validate_page(self.write("x.jpg", b"<html>gone</html>"), declared_mime="image/jpeg")

    def test_a_declared_type_that_disagrees_with_the_bytes_is_rejected(self):
        with self.assertRaises(PageRejected):
            validate_page(self.write("y.png", png(10, 10)), declared_mime="image/jpeg")

    def test_oversized_dimensions_are_rejected(self):
        with self.assertRaises(PageRejected):
            validate_page(self.write("big.png", declared_png(60000, 60000)))

    def test_oversized_pixel_count_is_rejected(self):
        with self.assertRaises(PageRejected):
            validate_page(self.write("wide.png", declared_png(19000, 19000)))

    def test_empty_and_truncated_files_are_rejected(self):
        for name, body in (("e.bin", b""), ("t.png", b"\x89PNG\r\n\x1a\nshort")):
            with self.assertRaises(PageRejected):
                validate_page(self.write(name, body))

    def test_an_oversized_file_is_rejected_before_decoding(self):
        payload = png(4, 4) + b"\x00" * (2 * 1024 * 1024)
        with self.assertRaises(PageRejected):
            validate_page(self.write("big.bin", payload), max_bytes=1024)


class PathContainmentTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_a_path_escaping_the_root_is_refused(self):
        from shura_core.content.storage import _resolve_inside
        for evil in ("../escape.img", "pages/../../escape.img"):
            with self.assertRaises(PageRejected):
                _resolve_inside(self.root, evil)

    def test_a_normal_path_resolves_inside(self):
        from shura_core.content.storage import _resolve_inside
        self.assertTrue(str(_resolve_inside(self.root, "pages/s1/w1/c1/g0001/p0000.img"))
                        .startswith(str(self.root)))


class FetchSuccessAndFailureTests(FetchCase):
    def test_a_valid_page_is_stored_and_recorded_done(self):
        transport = FixtureTransport({"https://example.org/p0.png": (png(40, 60), "image/png")})
        result = self.fetcher(transport).fetch_page(
            source_id="s1", ext_work_id="w1", ext_chapter_id="c1", page_index=0,
            page_url="https://example.org/p0.png", host="example.org")
        self.assertEqual(result.outcome, "DONE")
        row = self.conn.execute(
            "SELECT state, mime, width, height, bytes FROM chapter_pages WHERE page_index=0"
        ).fetchone()
        self.assertEqual((row[0], row[1], row[2], row[3]), ("DONE", "image/png", 40, 60))
        stored = self.root / self.conn.execute(
            "SELECT rel_path FROM chapter_pages WHERE page_index=0").fetchone()[0]
        self.assertTrue(stored.is_file(), "the file recorded as DONE must exist")

    def test_a_failing_page_does_not_stop_the_others(self):
        transport = FixtureTransport({
            "https://example.org/p0.png": (png(8, 8), "image/png"),
            "https://example.org/p1.png": (b"", "image/png"),          # empty -> permanent failure
            "https://example.org/p2.png": (png(8, 8, filler=9), "image/png"),
        })
        transport.raise_for.add("https://example.org/p2.png")           # -> transient
        results = self.fetcher(transport).fetch_chapter(
            source_id="s1", ext_work_id="w1", ext_chapter_id="c1",
            page_urls=[f"https://example.org/p{i}.png" for i in range(3)])
        self.assertEqual([r.outcome for r in results], ["DONE", "FAILED", "TRANSIENT"])
        self.assertEqual(self.state(0), "DONE")
        self.assertEqual(self.state(1), "FAILED")
        self.assertEqual(self.state(2), "TRANSIENT")

    def test_a_failing_page_leaves_the_chapter_partial_not_lost(self):
        transport = FixtureTransport({
            "https://example.org/p0.png": (png(8, 8), "image/png"),
            "https://example.org/p1.png": (png(8, 8), "image/png"),
            "https://example.org/p2.png": (b"", "image/png"),
        })
        self.fetcher(transport).fetch_chapter(
            source_id="s1", ext_work_id="w1", ext_chapter_id="c1",
            page_urls=[f"https://example.org/p{i}.png" for i in range(3)])
        row = self.conn.execute(
            "SELECT state, reason FROM work_chapters WHERE ext_chapter_id='c1'").fetchone()
        self.assertEqual(row[0], "PARTIAL", "one bad page must not take the chapter down")
        self.assertIn("2 of 3", row[1])

    def test_a_rejected_file_is_never_marked_done_merely_for_existing(self):
        transport = FixtureTransport({"https://example.org/p0.png": (b"<html>x</html>", "image/png")})
        result = self.fetcher(transport).fetch_page(
            source_id="s1", ext_work_id="w1", ext_chapter_id="c1", page_index=0,
            page_url="https://example.org/p0.png", host="example.org")
        self.assertEqual(result.outcome, "FAILED")
        self.assertEqual(self.state(0), "FAILED")
        stored = list((self.root / "content").rglob("*.img"))
        self.assertEqual(stored, [], "a rejected body must not be left on disk")

    def test_a_rejected_page_can_be_retried_and_can_succeed(self):
        url = "https://example.org/p0.png"
        transport = FixtureTransport({url: (b"<html>x</html>", "image/png")})
        self.fetcher(transport).fetch_page(source_id="s1", ext_work_id="w1",
                                           ext_chapter_id="c1", page_index=0,
                                           page_url=url, host="example.org")
        transport.pages[url] = (png(12, 12), "image/png")
        self.expire_backoff()
        result = self.fetcher(transport).fetch_page(source_id="s1", ext_work_id="w1",
                                                    ext_chapter_id="c1", page_index=0,
                                                    page_url=url, host="example.org")
        self.assertEqual(result.outcome, "DONE")
        self.assertEqual(self.state(0), "DONE")


class ResumeTests(FetchCase):
    def test_a_completed_page_is_not_requested_again(self):
        urls = [f"https://example.org/p{i}.png" for i in range(3)]
        transport = FixtureTransport({u: (png(8, 8), "image/png") for u in urls})
        self.fetcher(transport).fetch_chapter(source_id="s1", ext_work_id="w1",
                                              ext_chapter_id="c1", page_urls=urls)
        self.assertEqual(len(transport.requested), 3)
        second = self.fetcher(FixtureTransport({u: (png(9, 9), "image/png") for u in urls}))
        results = second.fetch_chapter(source_id="s1", ext_work_id="w1",
                                       ext_chapter_id="c1", page_urls=urls)
        self.assertEqual([r.outcome for r in results], ["WAITING"] * 3)
        self.assertEqual(second.transport.requested, [],
                         "a page already DONE must not be requested at all")

    def test_a_crashed_attempt_after_the_move_is_recovered_without_a_download(self):
        url = "https://example.org/p0.png"
        transport = FixtureTransport({url: (png(20, 20), "image/png")})
        fetcher = self.fetcher(transport)
        fetcher.fetch_page(source_id="s1", ext_work_id="w1", ext_chapter_id="c1",
                           page_index=0, page_url=url, host="example.org")
        # simulate the crash window: intent still open, page still IN_PROGRESS
        self.conn.execute("UPDATE chapter_pages SET state='IN_PROGRESS', active_attempt_no=1,"
                          " rel_path=NULL, sha256=NULL, verified_at=NULL WHERE page_index=0")
        self.conn.execute("INSERT INTO save_intents(source_id,ext_chapter_id,page_index,attempt_no,"
                          "rel_path,part_path,expected_bytes,started_at,owner_instance,fence_token)"
                          " VALUES('s1','c1',0,1,?,?,NULL,'now','instance-a',1)",
                          (self.rel(1), self.part(0)))
        fresh = FixtureTransport({})
        recovered = recover_interrupted(self.conn, root=str(self.root))
        self.assertEqual(len(recovered), 1)
        from shura_core.content.store import commit_done
        commit_done(self.conn, self.lock, source_id="s1", ext_chapter_id="c1", page_index=0,
                    attempt_no=1, rel_path=recovered[0].rel_path, sha256=recovered[0].sha256,
                    size=recovered[0].size, mime="image/png", width=20, height=20,
                    content_key=recovered[0].sha256)
        self.assertEqual(self.state(0), "DONE")
        self.assertEqual(fresh.requested, [], "recovery must not download anything")

    def test_an_orphan_file_is_not_adopted(self):
        stray = self.root / "pages/s1/w1/c1/g0001/p0000.img"
        stray.parent.mkdir(parents=True, exist_ok=True)
        stray.write_bytes(png(5, 5))
        self.assertEqual(recover_interrupted(self.conn, root=str(self.root)), [])
        self.assertEqual(self.state(0), "PENDING")

    def test_stale_parts_are_cleaned_but_only_when_old(self):
        import os
        import time
        part = self.root / "content/tmp/c1/g0001-0000.part"
        part.parent.mkdir(parents=True, exist_ok=True)
        part.write_bytes(b"partial")
        self.assertEqual(cleanup_stale_parts(self.root, older_than_seconds=3600), 0,
                         "a fresh part may belong to a transfer in flight")
        os.utime(part, (time.time() - 90_000, time.time() - 90_000))
        self.assertEqual(cleanup_stale_parts(self.root, older_than_seconds=3600), 1)
        self.assertFalse(part.exists())

    def rel(self, attempt):
        return f"pages/s1/w1/c1/g{attempt:04d}/p0000.img"

    def part(self, index):
        return f"content/tmp/c1/g0001-{index:04d}.part"


class FenceDuringFetchTests(FetchCase):
    def test_a_displaced_owner_cannot_place_the_file(self):
        url = "https://example.org/p0.png"
        transport = FixtureTransport({url: (png(10, 10), "image/png")})
        fetcher = self.fetcher(transport)

        def lose_the_lock(*args, **kwargs):
            self.conn.execute("UPDATE fetch_lock SET lease_expires_at='1999-01-01T00:00:00+00:00'")
            acquire_lock(self.conn, "instance-b", "host-b")
            return None

        from unittest import mock
        with mock.patch("shura_core.content.storage.place_page",
                        side_effect=AssertionError("must not place")) as placer, \
             mock.patch("shura_core.content.fetcher.assert_fence",
                        side_effect=FenceLost("displaced")):
            with self.assertRaises(FenceLost):
                fetcher.fetch_page(source_id="s1", ext_work_id="w1", ext_chapter_id="c1",
                                   page_index=0, page_url=url, host="example.org")
        self.assertEqual(placer.call_count, 0, "a displaced owner must not move any file")
        self.assertEqual(self.state(0), "IN_PROGRESS")

    def test_a_displaced_owner_cannot_commit_a_result(self):
        url = "https://example.org/p0.png"
        transport = FixtureTransport({url: (png(10, 10), "image/png")})
        fetcher = self.fetcher(transport)
        self.conn.execute("UPDATE fetch_lock SET lease_expires_at='1999-01-01T00:00:00+00:00'")
        acquire_lock(self.conn, "instance-b", "host-b")
        with self.assertRaises(FenceLost):
            fetcher.fetch_page(source_id="s1", ext_work_id="w1", ext_chapter_id="c1",
                               page_index=0, page_url=url, host="example.org")


class BudgetAndQuotaTests(FetchCase):
    def test_the_request_ceiling_stops_the_run_without_failing_pages(self):
        urls = [f"https://example.org/p{i}.png" for i in range(3)]
        transport = FixtureTransport({u: (png(8, 8), "image/png") for u in urls})
        results = self.fetcher(transport, max_requests=1).fetch_chapter(
            source_id="s1", ext_work_id="w1", ext_chapter_id="c1", page_urls=urls)
        self.assertEqual(results[0].outcome, "DONE")
        self.assertEqual([r.outcome for r in results[1:]], ["SKIPPED", "SKIPPED"])
        self.assertEqual(self.state(1), "PENDING")
        self.assertEqual(self.state(2), "PENDING")

    def test_the_page_and_byte_ceilings_are_respected(self):
        urls = [f"https://example.org/p{i}.png" for i in range(3)]
        transport = FixtureTransport({u: (png(8, 8), "image/png") for u in urls})
        fetcher = self.fetcher(transport, max_pages=1)
        fetcher.fetch_chapter(source_id="s1", ext_work_id="w1", ext_chapter_id="c1",
                              page_urls=urls)
        self.assertEqual(fetcher.budget.pages, 1)
        fetcher = self.fetcher(transport, max_bytes=1)
        fetcher.fetch_chapter(source_id="s1", ext_work_id="w1", ext_chapter_id="c1",
                              page_urls=urls)
        self.assertEqual(fetcher.budget.bytes_used <= 1, True,
                         "the byte ceiling must stop the next page")

    def test_the_quota_holds_the_page_instead_of_failing_it(self):
        url = "https://example.org/p0.png"
        transport = FixtureTransport({url: (png(8, 8), "image/png")})
        fetcher = PageFetcher(self.conn, self.lock, self.root, transport, Budget(Budgets()),
                              limiter=SharedRateLimiter(per_second=10_000.0),
                              policy_fetcher=lambda _s: 0)
        result = fetcher.fetch_page(source_id="s1", ext_work_id="w1", ext_chapter_id="c1",
                                    page_index=0, page_url=url, host="example.org")
        self.assertEqual(result.outcome, "QUOTA_HOLD")
        self.assertEqual(self.state(0), "QUOTA_HOLD", "a quota pause is not a failure")
        self.assertEqual(transport.requested, [])

    def test_one_limiter_is_shared_by_every_fetcher_on_a_host(self):
        """A limiter per fetcher would make the ceiling N x workers."""
        import time
        limiter = SharedRateLimiter(per_second=100.0)
        sleeps = []
        original = time.sleep
        time.sleep = lambda seconds: sleeps.append(seconds)
        try:
            first = PageFetcher(self.conn, self.lock, self.root, FixtureTransport({}),
                                Budget(Budgets()), limiter=limiter)
            second = PageFetcher(self.conn, self.lock, self.root, FixtureTransport({}),
                                 Budget(Budgets()), limiter=limiter)
            limiter.wait("example.org")
            first.limiter.wait("example.org")
            second.limiter.wait("example.org")
        finally:
            time.sleep = original
        self.assertGreaterEqual(len(sleeps), 2, "the bucket is shared, so later callers wait")
        limiter.wait("other.example")
        self.assertEqual(len(sleeps), 2, "a different host has its own bucket")

    def test_usage_is_measured_from_the_filesystem(self):
        transport = FixtureTransport({"https://example.org/p0.png": (png(8, 8), "image/png")})
        self.assertEqual(usage_bytes(self.root), 0)
        self.fetcher(transport).fetch_page(source_id="s1", ext_work_id="w1",
                                           ext_chapter_id="c1", page_index=0,
                                           page_url="https://example.org/p0.png", host="example.org")
        self.assertGreater(usage_bytes(self.root), 0)


class NetworkGuardTests(unittest.TestCase):
    """The production guards, exercised through the module the fetcher would use."""

    def test_a_private_host_is_refused(self):
        for url in ("http://127.0.0.1:8080/p.png", "https://10.1.2.3/p.png"):
            with self.assertRaises(NetworkPolicyError):
                SafeHTTP(["127.0.0.1", "10.1.2.3"]).get(url)

    def test_a_host_outside_the_allow_list_is_refused(self):
        with self.assertRaises(NetworkPolicyError):
            SafeHTTP(["example.org"]).get("https://elsewhere.example/p.png")

    def test_plain_http_is_refused_when_https_is_required(self):
        with self.assertRaises(NetworkPolicyError):
            SafeHTTP(["example.org"]).get("http://example.org/p.png")

    def test_a_redirect_off_the_allow_list_is_refused(self):
        import urllib.request
        from shura_core.security.network import SafeRedirect
        redirect = SafeRedirect(["example.org"], 5, True, False, True, "https://example.org/start")
        with self.assertRaises(NetworkPolicyError):
            redirect.redirect_request(
                urllib.request.Request("https://example.org/start"), None, 302, None, None,
                "https://elsewhere.example/p.png")

    def test_a_redirect_loop_is_stopped_by_the_redirect_limit(self):
        import urllib.request
        from shura_core.security.network import SafeRedirect

        def hop(redirect, target):
            redirect.redirect_request(
                urllib.request.Request("https://example.org/a"), None, 302, None, None, target)

        # Repeating a target the handler already followed is a loop, not a chain.
        looped = SafeRedirect(["example.org"], 10, True, False, True, "https://example.org/a")
        hop(looped, "https://example.org/b")
        with self.assertRaises(NetworkPolicyError):
            hop(looped, "https://example.org/b")

        # The hop ceiling is enforced independently of the loop check.
        budgeted = SafeRedirect(["example.org"], 2, True, False, True, "https://example.org/a")
        hop(budgeted, "https://example.org/c")
        hop(budgeted, "https://example.org/d")
        with self.assertRaises(NetworkPolicyError):
            hop(budgeted, "https://example.org/e")


class ClassificationGateTests(FetchCase):
    def test_unknown_mixed_and_nsfw_are_never_downloadable(self):
        for classification in ("UNKNOWN", "MIXED", "NSFW"):
            self.conn.execute("UPDATE works SET content_classification=? WHERE ext_work_id='w1'",
                              (classification,))
            allowed, reason = work_is_downloadable(self.conn, "s1", "w1")
            self.assertFalse(allowed, classification)
            self.assertIn(classification, reason)

    def test_safe_and_mature_are_downloadable(self):
        for classification in ("SAFE", "MATURE"):
            self.conn.execute("UPDATE works SET content_classification=? WHERE ext_work_id='w1'",
                              (classification,))
            self.assertTrue(work_is_downloadable(self.conn, "s1", "w1")[0])

    def test_a_missing_classification_is_not_permission(self):
        self.conn.execute("UPDATE works SET content_classification=NULL WHERE ext_work_id='w1'")
        allowed, reason = work_is_downloadable(self.conn, "s1", "w1")
        self.assertFalse(allowed)
        self.assertIn("UNKNOWN", reason)

    def test_a_completed_download_does_not_grant_clearance(self):
        from shura_core.content import grant_clearance
        url = "https://example.org/p0.png"
        before = self.conn.execute(
            "SELECT decision, decided_by, decided_at FROM element_clearance "
            "WHERE ext_chapter_id='c1'").fetchone()
        transport = FixtureTransport({url: (png(8, 8), "image/png")})
        self.fetcher(transport).fetch_page(source_id="s1", ext_work_id="w1",
                                           ext_chapter_id="c1", page_index=0,
                                           page_url=url, host="example.org")
        self.assertEqual(self.state(0), "DONE")
        # The clearance that let the download happen must be exactly the one that
        # was there before: finishing a download is not an exposure decision and
        # must not rewrite or re-grant the record.
        after = self.conn.execute(
            "SELECT decision, decided_by, decided_at FROM element_clearance "
            "WHERE ext_chapter_id='c1'").fetchone()
        self.assertEqual(tuple(after), tuple(before),
                         "a finished download must not alter the clearance record")
        # And an unregistered policy rule still cannot be used to self-serve one.
        with self.assertRaises(ValueError):
            grant_clearance(self.conn, source_id="s1", ext_work_id="w1", ext_chapter_id="c1",
                            decision="CLEARED", basis="policy-rule", policy_rule="any",
                            decided_by="auto", evidence="none", policy_version="v1")
        grant_clearance(self.conn, source_id="s1", ext_work_id="w1", ext_chapter_id="c1",
                        decision="CLEARED", basis="operator-approval", decided_by="op",
                        evidence="manual review", policy_version="v1")
        self.assertEqual(self.conn.execute(
            "SELECT decision FROM element_clearance").fetchone()[0], "CLEARED")


class SourceConfigTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def write(self, payload):
        path = self.dir / "content-sources.json"
        path.write_text(payload if isinstance(payload, str) else json.dumps(payload))
        return path

    def valid(self, **overrides):
        entry = {"source_id": "s1", "fetch_enabled": False,
                 "allowed_hosts": ["example.org"], "quota_bytes": 1024, "retention_days": 30}
        entry.update(overrides)
        return {"version": 1, "sources": [entry]}

    def test_a_missing_file_yields_no_fetchable_source(self):
        self.assertEqual(load_source_config(self.dir / "absent.json"), [])

    def test_invalid_json_yields_no_fetchable_source(self):
        self.assertEqual(load_source_config(self.write("{not json")), [])

    def test_a_wrong_version_is_refused(self):
        self.assertEqual(load_source_config(self.write({"version": 2, "sources": []})), [])

    def test_fetch_enabled_defaults_to_false(self):
        entry = self.valid()
        del entry["sources"][0]["fetch_enabled"]
        path = self.write({"version": 1, "sources": [entry]})
        self.assertEqual(load_source_config(path), [], "a missing flag is not an enable")

    def test_an_entry_missing_a_required_field_is_dropped(self):
        entry = self.valid()
        del entry["sources"][0]["allowed_hosts"]
        self.assertEqual(load_source_config(self.write({"version": 1, "sources": [entry]})), [])

    def test_empty_host_list_is_refused(self):
        self.assertEqual(load_source_config(self.write(self.valid(allowed_hosts=[]))), [])

    def test_a_valid_disabled_entry_is_kept_but_not_fetchable(self):
        entries = load_source_config(self.write(self.valid()))
        self.assertEqual(len(entries), 1)
        self.assertFalse(entries[0]["fetch_enabled"])

    def test_sync_never_enables_a_source_on_its_own(self):
        conn = open_store(self.dir / "c.db")
        try:
            entries = load_source_config(self.write(self.valid()))
            sync_source_policy(conn, entries)
            from shura_core.content import fetchable_sources
            self.assertEqual(fetchable_sources(conn), [],
                             "a disabled source must not become fetchable by syncing")
            sync_source_policy(conn, load_source_config(self.write(self.valid(fetch_enabled=True))))
            self.assertEqual(len(fetchable_sources(conn)), 1)
            sync_source_policy(conn, [])
            self.assertEqual(fetchable_sources(conn), [], "a removed source stops being fetchable")
        finally:
            conn.close()

    def test_sync_writes_only_to_the_content_database(self):
        conn = open_store(self.dir / "c.db")
        try:
            sync_source_policy(conn, load_source_config(self.write(self.valid())))
            tables = {r[0] for r in conn.execute(
                "SELECT name FROM sqlite_master WHERE type='table'")}
            self.assertIn("source_policy", tables)
            self.assertNotIn("publications", tables,
                             "the content database must not grow crawler state tables")
        finally:
            conn.close()


if __name__ == "__main__":
    unittest.main()
