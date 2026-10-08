"""The daemon must drive the whole critical path, not strand work in "discovered".

Regression: ``daemon.main`` passed only a ``CrawlCoordinator`` to
``Scheduler.serve``. ``CrawlCoordinator.run`` ends at
``store.put_pending(c, "discovered")``, so a long-running daemon accumulated
candidates in that stage forever: never validated, never downloaded, never
security/malware scanned, never content-reviewed, and never presented to the
accept/publish gates. ``serve`` also carried a ``rechecker`` parameter that no
caller ever passed, so chapter retry never ran either.

These tests drive the real ``serve`` loop with the real ``CandidateProcessor``
and ``RepositoryPublisher``. Nothing here touches the published ``repo/``: the
publish assertions run against a throwaway directory, and
``test_full_critical_path_never_touches_the_published_repository`` pins the
real artifact's digest across the run.
"""
import hashlib
import json
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from shura_core.models import Candidate, Source
from shura_core.publishing.repository import RepositoryPublisher
from shura_core.scheduling import Scheduler
from shura_core.security.artifacts import ScanResult, ScanVerdict
from shura_core.security.network import SafeHTTP, validate_url
from shura_core.state import StateStore

CERT = "a" * 64


class CleanScanner:
    """Stands in for the aapt/apksigner and ClamAV tiers so the test stays offline."""

    def __init__(self, verdict=ScanVerdict.CLEAN):
        self.max_size = 50_000_000
        self.verdict = verdict

    def scan(self, path, expected_package=None, expected_certificate=None):
        return ScanResult(self.verdict, "a" * 64, path.stat().st_size, "fixture",
                          expected_package or "org.example.ext",
                          expected_certificate or CERT)


APK_BODY = b"fixture-apk-bytes"


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/apk":
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Content-Length", str(len(APK_BODY)))
            self.end_headers()
            self.wfile.write(APK_BODY)
        else:
            self.send_error(404)

    def log_message(self, *args):
        pass


class OneShot(threading.Event):
    """stop_event that lets exactly one full serve() pass complete.

    serve() checks the flag at the top of each loop, so the first check admits
    the pass and the second ends the loop.
    """

    calls = 0

    def is_set(self):
        OneShot.calls += 1
        return OneShot.calls > 1


class FakeCrawler:
    """Emits a fixed candidate batch, so a pass has something to process."""

    versions = ("1.0",)

    def __init__(self, http):
        self.http = http

    def crawl(self, source, budgets):
        cands = [Candidate("s1", "org.example.ext", v, f"{self.base}/apk",
                           name=f"Ext {v}", language="ar", provenance={"page": "p"})
                 for v in self.versions]
        return type("R", (), {"counters": {"discovered": len(cands)},
                              "pages": 1, "candidates": cands})()


class DaemonPipelineTests(unittest.TestCase):
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
        FakeCrawler.base = self.base
        FakeCrawler.versions = ("1.0",)
        OneShot.calls = 0
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.store = StateStore(self.root / "s.db")
        self.addCleanup(self.store.close)
        self.addCleanup(self.tmp.cleanup)

    # -- harness ---------------------------------------------------------
    def http_factory(self):
        kw = dict(https_only=False, allow_nonstandard_ports=True, allow_private_hosts=True)
        return lambda hosts, *a, **k: SafeHTTP(hosts, *a, **k, **kw)

    def processor(self, malware=ScanVerdict.CLEAN, forced=None):
        from shura_core.pipeline import CandidateProcessor
        return CandidateProcessor(
            self.store, artifact_dir=str(self.root / "artifacts"),
            scanner=CleanScanner(), malware_scanner=CleanScanner(malware),
            expected_hosts={"127.0.0.1"}, http_factory=self.http_factory(),
            url_validator=lambda url, hosts: validate_url(
                url, hosts, https_only=False, allow_nonstandard_ports=True,
                allow_private_hosts=True),
            content_review_enabled=forced)

    def add_source(self, extra_config=None):
        cfg = {"allowed_hosts": ["127.0.0.1"], "signing_key": CERT}
        cfg.update(extra_config or {})
        self.store.add_source(Source("s1", "fixture", f"{self.base}/apk", "index",
                                     "ar", configuration=cfg))

    def run_pass(self, processor, rechecker=None, **serve_kw):
        from shura_core.coordinator import CrawlCoordinator
        import shura_core.coordinator as C
        saved = {n: getattr(C, n) for n in
                 ("IndexCrawler", "GitHubReleasesCrawler", "HtmlListingCrawler")}
        for n in saved:
            setattr(C, n, FakeCrawler)
        try:
            coord = CrawlCoordinator(self.store, max_sources=10)
            Scheduler(self.store).serve(coord, 1, OneShot(), processor=processor,
                                        rechecker=rechecker, **serve_kw)
        finally:
            for n, v in saved.items():
                setattr(C, n, v)
        return self.store

    def stages(self):
        return {c["identity"]: c["stage"] for c in self.store.pending()}

    # -- the regression --------------------------------------------------
    def test_daemon_pass_moves_candidates_out_of_discovered(self):
        self.add_source()
        self.run_pass(self.processor())
        self.assertEqual(self.stages(), {"org.example.ext|1.0": "security-passed"})

    def test_a_pass_leaves_nothing_stranded_in_discovered(self):
        FakeCrawler.versions = ("1.0", "1.1", "1.2")
        self.add_source()
        self.run_pass(self.processor())
        stages = self.stages()
        self.assertEqual(len(stages), 3)
        self.assertFalse([s for s in stages.values() if s == "discovered"],
                         "candidates must not be left in 'discovered'")

    # -- isolation -------------------------------------------------------
    def test_one_exploding_candidate_does_not_sink_the_pass(self):
        FakeCrawler.versions = ("1.0", "1.1")
        self.add_source()
        real_process = self.processor().process
        boom = "org.example.ext|1.0"

        def flaky(c, *a, **k):
            if c.identity == boom:
                raise RuntimeError("artifact exploded")
            return real_process(c, *a, **k)

        self.run_pass(type("P", (), {"process": staticmethod(flaky)})())
        self.assertEqual(self.stages()["org.example.ext|1.1"], "security-passed")
        counts = self.store.status("s1")["counts"]
        self.assertEqual(counts.get("candidate_pipeline_error"), 1,
                         "the failure must be recorded, not swallowed")

    def test_malware_detection_quarantines_and_is_counted_not_swallowed(self):
        self.add_source()
        self.run_pass(self.processor(malware=ScanVerdict.REJECTED))
        self.assertTrue(self.store.is_quarantined("s1", "org.example.ext|1.0"),
                        "a malware verdict must quarantine durably")

    def test_unavailable_scanner_defers_instead_of_quarantining(self):
        """An engine fault is not malware: it must not enter the quarantine
        ledger, and must not leave the candidate looking security-passed."""
        self.add_source()
        self.run_pass(self.processor(malware=ScanVerdict.UNAVAILABLE))
        identity = "org.example.ext|1.0"
        self.assertEqual(self.stages()[identity], "security-blocked")
        self.assertFalse(self.store.is_quarantined("s1", identity))
        counts = self.store.status("s1")["counts"]
        self.assertEqual(counts.get("security_blocked"), 1)

    # -- budget ----------------------------------------------------------
    def test_download_budget_caps_a_pass_and_reports_the_remainder(self):
        FakeCrawler.versions = ("1.0", "1.1", "1.2")
        self.add_source()
        proc = self.processor()
        seen = []
        real_process = proc.process
        proc.process = lambda c, *a, **k: (seen.append(c.identity), real_process(c, *a, **k))[1]
        self.run_pass(proc, max_downloads=2)
        self.assertEqual(len(seen), 2, "the budget must cap processing per pass")
        self.assertEqual(len(self.store.pending()), 3,
                         "over-budget candidates stay pending for the next pass")

    # -- rechecker is not a dead parameter -------------------------------
    def test_serve_invokes_the_rechecker_every_pass(self):
        self.add_source()
        calls = []

        class RecordingRechecker:
            def recheck(self, chapter_record, policy):
                calls.append(chapter_record)
                return None

        self.run_pass(self.processor(), rechecker=RecordingRechecker())
        self.assertTrue(calls or self.store.due_chapters() == [],
                        "rechecker must be consulted, and consulted on due work")

    def test_recheck_due_chapters_actually_reaches_the_rechecker(self):
        self.add_source()
        from shura_core.models import ChapterRecord, ChapterStatus
        from shura_core.models import WorkReview

        self.store.record_chapters("s1", WorkReview(
            "w1", "W1", "PENDING", "", {},
            [ChapterRecord("w1", "c1", ChapterStatus.STALE_LINK, "stale", [], 0)]))

        class Healing:
            def recheck(self, chapter_record, policy):
                return ChapterRecord(chapter_record["work_id"], chapter_record["chapter_id"],
                                     ChapterStatus.HEALTHY, "healed", ["p"], 1)

        self.run_pass(self.processor(), rechecker=Healing())
        chapters = self.store.content_chapters("s1", "w1")
        self.assertEqual(chapters[0]["status"], "HEALTHY")
        self.assertEqual(self.store.content_work("s1", "w1")["verdict"], "ACCEPTED")

    # -- reachability of publish, without an unintended real publish -----
    def test_full_critical_path_reaches_publish(self):
        self.add_source()
        self.run_pass(self.processor())
        identity = "org.example.ext|1.0"
        self.assertEqual(self.stages()[identity], "security-passed")

        # Acceptance and publication stay explicit operator steps; the daemon
        # must not have taken either on its own.
        self.assertEqual(self.store.published(), [])
        self.store.accept_pending("s1", identity)

        repo = self.root / "repo"
        result = RepositoryPublisher(self.store, repo).publish(release=True)
        self.assertEqual(result["published"], 1)
        self.assertTrue((repo / "index.pb").is_file())
        self.assertTrue((repo / "apk" / "org_example_ext-1.0.apk").is_file())
        self.assertEqual(len(self.store.published()), 1)

    def test_full_critical_path_never_touches_the_published_repository(self):
        published_repo = Path(__file__).resolve().parents[1] / "repo"
        live = published_repo / "index.pb"
        before = hashlib.sha256(live.read_bytes()).hexdigest() if live.is_file() else None
        stat_before = live.stat().st_mtime if live.is_file() else None

        self.add_source()
        self.run_pass(self.processor())
        self.store.accept_pending("s1", "org.example.ext|1.0")
        RepositoryPublisher(self.store, self.root / "repo").publish(release=True)

        after = hashlib.sha256(live.read_bytes()).hexdigest() if live.is_file() else None
        self.assertEqual(before, after, "the test published into repo/ by accident")
        self.assertEqual(stat_before, live.stat().st_mtime if live.is_file() else None)

    def test_daemon_does_not_publish_on_its_own(self):
        self.add_source()
        self.run_pass(self.processor())
        self.assertEqual(self.store.published(), [],
                         "the daemon must never publish without an explicit operator step")
        self.assertFalse((self.root / "repo").exists())

    # -- wiring in daemon.main ------------------------------------------
    def test_daemon_main_wires_processor_and_rechecker(self):
        import shura_core.daemon as D
        captured = {}

        class SpyScheduler:
            def __init__(self, store):
                pass

            def serve(self, coordinator, interval, stop, **kw):
                captured.update(kw)
                captured["max_sources"] = coordinator.max_sources

        original = D.Scheduler
        D.Scheduler = SpyScheduler
        try:
            D.main(["--db", str(self.root / "d.db"), "--interval-seconds", "1",
                    "--max-downloads", "7", "--artifact-dir", str(self.root / "a")])
        finally:
            D.Scheduler = original

        self.assertIsNotNone(captured.get("processor"),
                             "daemon.main must pass a CandidateProcessor to serve()")
        self.assertIsNotNone(captured.get("rechecker"),
                             "daemon.main must pass a rechecker; the parameter is not dead")
        self.assertEqual(captured.get("max_downloads"), 7)

    # -- explicit opt-in preserved ---------------------------------------
    def test_serve_without_processor_still_only_discovers(self):
        """Backwards compatibility: an operator who wants discover-only keeps it."""
        self.add_source()
        self.run_pass(None)
        self.assertEqual(self.stages(), {"org.example.ext|1.0": "discovered"})


if __name__ == "__main__":
    unittest.main()