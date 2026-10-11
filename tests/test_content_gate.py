"""The content safety gate on the fetch path.

Every case here calls ``PageFetcher.fetch_page`` **directly**, not through
``run_fetch_run``. The gate lives inside ``fetch_page`` precisely so that a
caller who never goes near the runner cannot route around it, and a test that
only exercised the runner would not prove that.

What is asserted for every refused case:

* the transport was never asked for anything (``transport.requested == []``)
* no download attempt was created (no save intent, no bump of ``attempts``)
* the page is not ``DONE`` and no ``*.img`` was placed
* the page stays startable, so a later clearance decision is re-evaluated

A local fixture transport and a temporary database only. No socket, no host.
"""

from __future__ import annotations

import json
import struct
import tempfile
import unittest
import zlib
import unittest.mock
from contextlib import contextmanager
from email.message import Message
from pathlib import Path

from shura_core.content import Budget, Budgets, PageFetcher, acquire_lock, chapter_is_fetchable
from shura_core.content.schema import open_store
from shura_core.content.store import grant_clearance, release_lock
from shura_core.content.storage import WRITE_CHUNK
from shura_core.security.network import ResponseTooLarge


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
URL = "https://example.org/p0.png"


class RecordingTransport:
    """Records every request. Opens no socket."""

    def __init__(self, body: bytes = PNG, mime: str = "image/png"):
        self.body = body
        self.mime = mime
        self.requested: list[str] = []

    def get(self, url):
        self.requested.append(url)
        return self.body, self.mime

    @contextmanager
    def stream(self, url, *, max_bytes, headers=None):
        self.requested.append(url)
        limit = max_bytes
        response_headers = Message()
        response_headers["Content-Type"] = self.mime
        offset = 0

        def _chunks():
            nonlocal offset
            remaining = limit + 1
            while remaining > 0:
                size = min(WRITE_CHUNK, remaining, len(self.body) - offset)
                if size <= 0:
                    break
                block = self.body[offset:offset + size]
                offset += size
                remaining -= size
                yield block
            if remaining == 0 and offset < len(self.body):
                raise ResponseTooLarge("response exceeds size limit")

        yield response_headers, url, _chunks(), limit


class GateCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.conn = open_store(self.root / "content.db")
        self.lock = acquire_lock(self.conn, "instance-a", "host-a")
        self.conn.execute(
            "INSERT INTO source_policy(source_id,kind,allowed_hosts,fetch_enabled,quota_bytes,"
            "retention_days,synced_at) VALUES('s1','index','[\"example.org\"]',1,?,'30','now')",
            (1 << 40,))
        self.add_work("w1", "SAFE")
        self.add_chapter("c1", "w1")
        self.add_page(0)

    def tearDown(self):
        release_lock(self.conn, self.lock)
        self.conn.close()
        self.tmp.cleanup()

    def add_work(self, work_id, classification):
        self.conn.execute(
            "INSERT INTO works(source_id,ext_work_id,source_work_id,title,"
            "content_classification,discovered_at,updated_at) VALUES('s1',?,'src','T',?,"
            "'now','now')", (work_id, classification))

    def add_chapter(self, chapter_id, work_id):
        self.conn.execute(
            "INSERT INTO work_chapters(source_id,ext_chapter_id,ext_work_id,source_chapter_id,"
            "chapter_number,ordinal,first_seen,updated_at) "
            "VALUES('s1',?,?,'sc',1.0,0,'n','n')", (chapter_id, work_id))

    def add_page(self, index, chapter_id="c1"):
        self.conn.execute(
            "INSERT INTO chapter_pages(source_id,ext_chapter_id,page_index,ordinal,"
            "source_page_url,state,created_at) VALUES('s1',?,?,0,?,'PENDING','now')",
            (chapter_id, index, URL))

    def clear(self, chapter_id="c1", work_id="w1", decision="CLEARED",
              basis="operator-approval", policy_rule=None):
        grant_clearance(self.conn, source_id="s1", ext_work_id=work_id,
                        ext_chapter_id=chapter_id, decision=decision, basis=basis,
                        decided_by="test", evidence="test", policy_version="test",
                        policy_rule=policy_rule)

    def fetch(self, transport=None, chapter_id="c1", work_id="w1", page_index=0):
        transport = transport if transport is not None else RecordingTransport()
        fetcher = PageFetcher(self.conn, self.lock, self.root, transport, Budget(Budgets()),
                              policy_fetcher=lambda _s: 1 << 40)
        return transport, fetcher.fetch_page(
            source_id="s1", ext_work_id=work_id, ext_chapter_id=chapter_id,
            page_index=page_index, page_url=URL, host="example.org")

    def assertNothingHappened(self, transport, result):
        self.assertEqual(result.outcome, "REFUSED")
        self.assertEqual(transport.requested, [],
                         "a refused chapter must not reach the transport at all")
        intents = self.conn.execute("SELECT COUNT(*) FROM save_intents").fetchone()[0]
        self.assertEqual(intents, 0, "a refused chapter must not create a download attempt")
        row = self.conn.execute(
            "SELECT state, attempts, sha256 FROM chapter_pages WHERE page_index=0").fetchone()
        self.assertNotEqual(row[0], "DONE")
        self.assertEqual(row[1], 0, "attempts must not be incremented")
        self.assertIsNone(row[2])
        self.assertEqual(list(self.root.rglob("*.img")), [])


class RefusedWithoutClassificationTests(GateCase):
    def test_nsfw_is_never_fetched(self):
        self.conn.execute("UPDATE works SET content_classification='NSFW' WHERE ext_work_id='w1'")
        self.clear()
        transport, result = self.fetch()
        self.assertNothingHappened(transport, result)

    def test_unknown_is_never_fetched(self):
        self.conn.execute("UPDATE works SET content_classification='UNKNOWN' WHERE ext_work_id='w1'")
        self.clear()
        transport, result = self.fetch()
        self.assertNothingHappened(transport, result)

    def test_mixed_is_never_fetched(self):
        self.conn.execute("UPDATE works SET content_classification='MIXED' WHERE ext_work_id='w1'")
        self.clear()
        transport, result = self.fetch()
        self.assertNothingHappened(transport, result)

    def test_absent_classification_is_not_permission(self):
        self.conn.execute("UPDATE works SET content_classification=NULL WHERE ext_work_id='w1'")
        self.clear()
        transport, result = self.fetch()
        self.assertNothingHappened(transport, result)


class RefusedWithoutClearanceTests(GateCase):
    def test_safe_alone_does_not_grant_permission(self):
        transport, result = self.fetch()
        self.assertNothingHappened(transport, result)
        self.assertIn("no clearance decision", result.reason)

    def test_mature_alone_does_not_grant_permission(self):
        self.conn.execute("UPDATE works SET content_classification='MATURE' WHERE ext_work_id='w1'")
        transport, result = self.fetch()
        self.assertNothingHappened(transport, result)

    def test_withheld_is_not_fetched(self):
        self.clear(decision="WITHHELD")
        transport, result = self.fetch()
        self.assertNothingHappened(transport, result)

    def test_pending_review_is_not_fetched(self):
        self.clear(decision="PENDING_REVIEW")
        transport, result = self.fetch()
        self.assertNothingHappened(transport, result)

    def test_clearance_for_another_chapter_does_not_grant_permission(self):
        self.add_chapter("c2", "w1")
        self.clear(chapter_id="c2")
        transport, result = self.fetch()
        self.assertNothingHappened(transport, result)

    def test_an_unregistered_policy_rule_basis_is_refused(self):
        # grant_clearance refuses this basis, so it can only exist if written
        # behind the helper. The gate re-checks rather than assuming.
        self.conn.execute(
            "INSERT INTO element_clearance(source_id,ext_work_id,ext_chapter_id,decision,basis,"
            "policy_rule,decided_by,evidence,policy_version,decided_at) "
            "VALUES('s1','w1','c1','CLEARED','policy-rule','rule-that-does-not-exist',"
            "'test','test','test','now')")
        transport, result = self.fetch()
        self.assertNothingHappened(transport, result)
        self.assertIn("not a registered", result.reason)


class AllowedTests(GateCase):
    def test_safe_with_cleared_clearance_is_fetched(self):
        self.clear()
        transport, result = self.fetch()
        self.assertEqual(result.outcome, "DONE")
        self.assertEqual(transport.requested, [URL])
        stored = self.conn.execute(
            "SELECT state FROM chapter_pages WHERE page_index=0").fetchone()[0]
        self.assertEqual(stored, "DONE")
        self.assertEqual(len(list(self.root.rglob("*.img"))), 1)

    def test_mature_with_cleared_clearance_is_fetched(self):
        self.conn.execute("UPDATE works SET content_classification='MATURE' WHERE ext_work_id='w1'")
        self.clear()
        transport, result = self.fetch()
        self.assertEqual(result.outcome, "DONE")

    def test_the_gate_reports_both_conditions_when_satisfied(self):
        self.clear()
        allowed, why = chapter_is_fetchable(self.conn, "s1", "c1")
        self.assertTrue(allowed)
        self.assertIn("CLEARED", why)


class ReEvaluationTests(GateCase):
    def test_a_refusal_does_not_burn_the_page_and_later_clearance_is_honoured(self):
        transport, result = self.fetch()
        self.assertEqual(result.outcome, "REFUSED")
        # Still startable, and still the same page - nothing was consumed.
        state, attempts = self.conn.execute(
            "SELECT state, attempts FROM chapter_pages WHERE page_index=0").fetchone()
        self.assertEqual(state, "PENDING")
        self.assertEqual(attempts, 0)
        self.assertEqual(transport.requested, [])

        # The operator clears it; the very next run fetches.
        self.clear()
        transport2, result2 = self.fetch()
        self.assertEqual(result2.outcome, "DONE")

    def test_withdrawing_clearance_stops_further_fetching(self):
        self.clear()
        self.add_page(1)
        transport, result = self.fetch(page_index=1)
        self.assertEqual(result.outcome, "DONE")
        self.conn.execute("DELETE FROM element_clearance WHERE ext_chapter_id='c1'")
        transport2, result2 = self.fetch(page_index=1)
        self.assertEqual(result2.outcome, "REFUSED")
        self.assertEqual(transport2.requested, [])

    def test_a_refusal_writes_a_reason_but_not_a_state(self):
        transport, result = self.fetch()
        reason = self.conn.execute(
            "SELECT reason FROM chapter_pages WHERE page_index=0").fetchone()[0]
        self.assertIn("fetch refused", reason)
        self.assertIn("no clearance decision", reason)

    def test_a_refusal_does_not_consume_the_request_budget(self):
        budget = Budget(Budgets(max_requests=1))
        transport = RecordingTransport()
        fetcher = PageFetcher(self.conn, self.lock, self.root, transport, budget,
                              policy_fetcher=lambda _s: 1 << 40)
        for index in range(3):
            fetcher.fetch_page(source_id="s1", ext_work_id="w1", ext_chapter_id="c1",
                               page_index=index, page_url=URL, host="example.org")
        self.assertEqual(budget.requests, 0, "a refused page must not spend a request")


class RunnerPathTests(GateCase):
    """The runner must not be a way around the gate either.

    ``SafeHTTP`` is stubbed so that if the gate ever failed to refuse first,
    this test would record a request rather than reach the network.
    """

    def setUp(self):
        # Unlike the other cases this one must NOT hold the content lock:
        # run_fetch_run takes it itself, and would otherwise just lock out.
        super().setUp()
        release_lock(self.conn, self.lock)
        self.lock = None

    def tearDown(self):
        self.conn.close()
        self.tmp.cleanup()

    def run_once(self, transport):
        from shura_core.content import runner

        class StubHTTP:
            def __init__(self, *a, **k):
                pass

            def stream(self, url, *, max_bytes, headers=None):
                transport.requested.append(url)
                raise AssertionError("the gate let a request through")

            def get(self, url, headers=None):
                transport.requested.append(url)
                raise AssertionError("the gate let a request through")

        config = self.root / "content-sources.json"
        config.write_text(json.dumps({"version": 1, "sources": [{
            "source_id": "s1", "fetch_enabled": True,
            "allowed_hosts": ["example.org"], "quota_bytes": 1 << 30,
            "retention_days": 30}]}), encoding="utf-8")
        with unittest.mock.patch.object(runner, "SafeHTTP", StubHTTP):
            return runner.run_fetch_run(self.conn, self.root, config_path=config,
                                        instance_id="gate-test", host="host-test")

    def test_the_runner_refuses_an_uncleared_chapter(self):
        transport = RecordingTransport()
        report = self.run_once(transport)
        self.assertEqual(report.pages_done, 0)
        self.assertEqual(report.refused, 1,
                         "a gate refusal is counted as refused, not as a failure")
        self.assertEqual(report.failed, 0)
        self.assertEqual(report.transient, 0)
        self.assertEqual(transport.requested, [],
                         "the runner must not reach the network for an uncleared chapter")
        self.assertEqual(self.conn.execute("SELECT COUNT(*) FROM save_intents").fetchone()[0], 0)

    def test_the_runner_fetches_once_the_chapter_is_cleared(self):
        self.clear()
        transport = RecordingTransport()
        report = self.run_once(transport)
        # The stub refuses to serve, so the page cannot complete - but it must
        # have been attempted, proving the gate opened for a genuinely cleared one.
        self.assertEqual(transport.requested, [URL],
                         "a cleared, SAFE chapter must be allowed through to the transport")


class GateHelperTests(GateCase):
    def test_an_unknown_chapter_is_not_fetchable(self):
        allowed, why = chapter_is_fetchable(self.conn, "s1", "no-such-chapter")
        self.assertFalse(allowed)
        self.assertIn("no such chapter", why)

    def test_the_work_is_resolved_from_the_chapter_not_the_caller(self):
        # Two works: w1 is SAFE, w2 is NSFW. Calling for w1's chapter while
        # naming w2 must not borrow w2's own classification or clearance.
        self.add_work("w2", "NSFW")
        self.clear(chapter_id="c1", work_id="w1")
        allowed, why = chapter_is_fetchable(self.conn, "s1", "c1")
        self.assertTrue(allowed, why)
        self.conn.execute("UPDATE works SET content_classification='NSFW' WHERE ext_work_id='w1'")
        allowed, why = chapter_is_fetchable(self.conn, "s1", "c1")
        self.assertFalse(allowed)
        self.assertIn("NSFW", why)


if __name__ == "__main__":
    unittest.main()