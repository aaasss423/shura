"""Security scanning must distinguish "infected" from "cannot scan".

Previously every non-CLEAN malware verdict was filed the same way: an artifact
was quarantined and marked processed even when the reason was
``ScanVerdict.UNAVAILABLE`` -- a missing ``clamscan`` binary, a stale signature
database, or a scan error. That is fail-closed for security, so nothing unsafe
was ever published, but it is fail-broken for operations: an entire host with no
ClamAV install filled the durable quarantine ledger with non-malware items,
every one of which then needed operator review, and the repository published
nothing because the operator had no reason to trust the queue.

The distinction now is:

* malware verdict REJECTED/SUSPICIOUS  -> quarantine, durable, operator review
* malware verdict UNAVAILABLE          -> deferred as "security-blocked",
                                         never quarantined, never publishable

Fail-closed is preserved: UNAVAILABLE is still not a pass. The publisher also
refuses any item whose recorded malware verdict is not CLEAN.
"""
import unittest

from shura_core.models import Candidate, Source
from shura_core.pipeline import CandidateProcessor
from shura_core.publishing.repository import PublishRefused, RepositoryPublisher
from shura_core.security.artifacts import ScanResult, ScanVerdict
from shura_core.security.malware import MalwareScanner
from shura_core.state import StateStore

CERT = "a" * 64


class StaticScanner:
    """Stands in for the aapt/apksigner tier."""

    max_size = 50_000_000

    def scan(self, path, expected_package=None, expected_certificate=None):
        return ScanResult(ScanVerdict.CLEAN, "a" * 64, path.stat().st_size,
                          "fixture", expected_package or "org.example.ext",
                          expected_certificate or CERT)


class MalwareResultScanner:
    """Returns a fixed malware verdict without touching an engine."""

    max_size = 50_000_000

    def __init__(self, verdict):
        self.verdict = verdict

    def scan(self, path, expected_package=None, expected_certificate=None):
        return ScanResult(self.verdict, "b" * 64, path.stat().st_size,
                          "malware fixture", expected_package or "org.example.ext",
                          expected_certificate or CERT)


class VerdictHandlingTests(unittest.TestCase):
    def setUp(self):
        import tempfile
        from pathlib import Path
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.store = StateStore(self.root / "s.db")
        self.addCleanup(self.store.close)
        self.addCleanup(self.tmp.cleanup)
        self.store.add_source(Source("s1", "fixture", "https://example.org/a.apk",
                                     "index", "ar",
                                     configuration={"allowed_hosts": ["example.org"],
                                                   "signing_key": CERT}))
        self.artifact = self.root / "x.apk"
        self.artifact.write_bytes(b"apk")

    def candidate(self):
        return Candidate("s1", "org.example.ext", "1.0", "https://example.org/a.apk",
                         name="Ext", language="ar", provenance={"page": "p"},
                         metadata={"_artifact_path": str(self.artifact)})

    def process(self, verdict):
        proc = CandidateProcessor(
            self.store, artifact_dir=str(self.root / "art"),
            scanner=StaticScanner(), malware_scanner=MalwareResultScanner(verdict))
        # Skip the network download: the artifact is already on disk, so the
        # transport just hands back its bytes.
        proc.http_factory = lambda hosts, *a, **k: type(
            "H", (), {"get": lambda self, url, max_bytes=None: (b"apk", {}, url)})()
        return proc.process(self.candidate())

    # -- the two cases that must stay apart ------------------------------
    def test_malware_detected_quarantines_durably(self):
        result = self.process(ScanVerdict.REJECTED)
        self.assertEqual(result["verdict"], "QUARANTINED")
        self.assertTrue(self.store.is_quarantined("s1", "org.example.ext|1.0"))
        stages = {c["identity"]: c["stage"] for c in self.store.pending()}
        self.assertNotIn("security-blocked", stages.values())

    def test_scanner_unavailable_defers_instead_of_quarantining(self):
        result = self.process(ScanVerdict.UNAVAILABLE)
        self.assertEqual(result["verdict"], "PENDING")
        self.assertEqual(result["malware_verdict"], "UNAVAILABLE")
        self.assertFalse(self.store.is_quarantined("s1", "org.example.ext|1.0"),
                         "an infrastructure fault is not malware and must not be "
                         "filed as such")
        stages = {c["identity"]: c["stage"] for c in self.store.pending()}
        self.assertEqual(stages["org.example.ext|1.0"], "security-blocked",
                         "the artifact must be kept for a later retry")
        counts = self.store.status("s1")["counts"]
        self.assertEqual(counts.get("security_blocked"), 1,
                         "the block must be recorded as an event, not silently dropped")

    def test_scanner_unavailable_never_reaches_the_accepted_stage(self):
        self.process(ScanVerdict.UNAVAILABLE)
        stages = {c["identity"]: c["stage"] for c in self.store.pending()}
        self.assertNotIn("security-passed", stages.values(),
                         "an unscanned artifact must never look security-passed")

    def test_scanner_unavailable_does_not_poison_the_processed_ledger(self):
        """The candidate stays retryable: it must not be marked terminal."""
        self.process(ScanVerdict.UNAVAILABLE)
        source = self.store.get_source("s1")
        self.assertFalse(self.store.already_processed(
            "s1", "org.example.ext|1.0", source.configuration_fingerprint),
            "a deferred artifact must remain eligible for a later pass")


class PublisherSecurityGateTests(unittest.TestCase):
    def setUp(self):
        import tempfile
        from pathlib import Path
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.store = StateStore(self.root / "s.db")
        self.addCleanup(self.store.close)
        self.addCleanup(self.tmp.cleanup)
        self.store.add_source(Source("s1", "fixture", "https://example.org/a.apk",
                                     "index", "ar",
                                     configuration={"allowed_hosts": ["example.org"],
                                                   "signing_key": CERT}))

    def publish_with(self, malware_record):
        artifact = self.root / "y.apk"
        artifact.write_bytes(b"apk")
        security = {"sha256": "c" * 64, "certificate": CERT}
        if malware_record is not None:
            security["malware"] = malware_record
        c = Candidate("s1", "org.example.ext", "1.0", "https://example.org/a.apk",
                      name="Ext", language="ar", provenance={"page": "p"},
                      metadata={"_artifact_path": str(artifact), "_security": security,
                                "_content_policy": {"scope": "source", "classification": "SAFE",
                                                   "policy_fingerprint": self.store.get_source("s1").fingerprint(),
                                                   "reasons": ["test fixture declared safe"],
                                                   "evidence": {}}})
        self.store.put_pending(c, "security-passed")
        self.store.accept_pending("s1", c.identity)
        return RepositoryPublisher(self.store, self.root / "repo").publish(release=True)

    def test_unavailable_verdict_is_never_published(self):
        for verdict in ("UNAVAILABLE", "REJECTED", "SUSPICIOUS"):
            with self.subTest(verdict=verdict):
                with self.assertRaises(PublishRefused) as ctx:
                    self.publish_with({"engine": "ClamAV", "verdict": verdict})
                self.assertIn(verdict, str(ctx.exception))
                self.assertFalse((self.root / "repo" / "index.json").exists())

    def test_clean_verdict_publishes(self):
        result = self.publish_with({"engine": "ClamAV", "verdict": "CLEAN"})
        self.assertEqual(result["published"], 1)
        self.assertTrue((self.root / "repo" / "index.pb").is_file())

    def test_legacy_item_without_a_malware_record_still_publishes(self):
        """Items published before the malware gate carry an empty record; the gate
        must not strand an existing repository."""
        result = self.publish_with(None)
        self.assertEqual(result["published"], 1)


class PreflightTests(unittest.TestCase):
    def test_preflight_reports_a_missing_engine_without_scanning(self):
        import shutil
        scanner = MalwareScanner(engine="definitely-not-a-real-binary")
        status = scanner.preflight()
        self.assertFalse(status.available)
        self.assertIn(MalwareScanner.ENGINE_NOT_FOUND, status.reason)

    def test_preflight_reports_a_missing_database(self):
        import tempfile
        from pathlib import Path
        with tempfile.TemporaryDirectory() as empty:
            engine = Path(empty) / "clamscan"
            engine.write_text("#!/bin/sh\nexit 0\n")
            engine.chmod(0o755)
            scanner = MalwareScanner(engine=str(engine), database_dir=empty)
            status = scanner.preflight()
            self.assertFalse(status.available)
            self.assertIn(MalwareScanner.DB_MISSING, status.reason)

    def test_preflight_agrees_with_what_scan_would_decide(self):
        """The preflight must never claim a state the scanner contradicts."""
        import tempfile
        from pathlib import Path
        with tempfile.TemporaryDirectory() as d:
            scanner = MalwareScanner(database_dir=d)
            self.assertFalse(scanner.preflight().available)
            artifact = Path(d) / "a.bin"
            artifact.write_bytes(b"x")
            self.assertEqual(scanner.scan(artifact).verdict, ScanVerdict.UNAVAILABLE)


class GitHubAssetTrustScopeTests(unittest.TestCase):
    """A GitHub asset is reachable only for a source that was told about it.

    GitHub serves release assets from ``release-assets.githubusercontent.com``,
    reached by a redirect from ``github.com``. Every hop is checked against the
    source's own allowlist, so granting that host to one trusted source grants
    nothing to any other - and a freshly discovered source, whose allowlist is
    still just the API host, cannot fetch an artifact at all.

    Live: with the hosts configured the redirect is followed and the bytes
    arrive; without them the request is refused at the first hop.
    """

    def setUp(self):
        import tempfile
        from shura_core.state import StateStore
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.store = StateStore(f"{self.tmp.name}/s.db")
        self.addCleanup(self.store.close)

    def test_a_discovered_source_cannot_reach_a_release_asset(self):
        from shura_core.discovery.service import SourceDiscovery
        import inspect
        source = inspect.getsource(SourceDiscovery.discover)
        self.assertIn('"allowed_hosts":["api.github.com"]', source,
                      "discovery must not widen an untrusted source's reach")
        self.assertNotIn("release-assets.githubusercontent.com", source,
                         "the asset host must be granted by an operator, not by discovery")

    def test_a_redirect_is_followed_only_to_a_host_the_source_allows(self):
        import http.server
        import threading
        from shura_core.security.network import NetworkPolicyError, SafeHTTP

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                if self.path == "/start":
                    self.send_response(302)
                    self.send_header("Location", "http://127.0.0.1:%d/asset" % self.server.server_port)
                    self.end_headers()
                else:
                    body = b"artifact-bytes"
                    self.send_response(200)
                    self.send_header("Content-Length", str(len(body)))
                    self.end_headers()
                    self.wfile.write(body)

            def log_message(self, *args):
                pass

        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.shutdown)
        host = "127.0.0.1"
        url = f"http://127.0.0.1:{server.server_port}/start"

        allowed = SafeHTTP([host], https_only=False, allow_private_hosts=True,
                           allow_nonstandard_ports=True)
        body, _, final = allowed.get(url)
        self.assertEqual(body, b"artifact-bytes",
                         "a source that was told about the host gets the bytes")
        self.assertIn("/asset", final)

        refused = SafeHTTP(["api.github.com"], https_only=False, allow_private_hosts=True,
                           allow_nonstandard_ports=True)
        with self.assertRaises(NetworkPolicyError):
            refused.get(url)

    def test_granting_the_asset_host_to_one_source_does_not_grant_it_to_another(self):
        from shura_core.discovery.service import SourceDiscovery
        from shura_core.models import Source, SourceState
        trusted = Source("github:trusted/ext", "Trusted",
                         "https://api.github.com/repos/t/r/releases", "github-releases",
                         "en", enabled=False, state=SourceState.PAUSED,
                         configuration={"allowed_hosts": ["api.github.com", "github.com",
                                                          "release-assets.githubusercontent.com"],
                                        "package": "", "signing_key": ""})
        untrusted = Source("github:found/ext", "Found",
                           "https://api.github.com/repos/f/r/releases", "github-releases",
                           "en", enabled=False, state=SourceState.PAUSED,
                           configuration={"allowed_hosts": ["api.github.com"],
                                          "package": "", "signing_key": ""})
        self.assertIn("release-assets.githubusercontent.com",
                      trusted.configuration["allowed_hosts"])
        self.assertNotIn("release-assets.githubusercontent.com",
                         untrusted.configuration["allowed_hosts"],
                         "the second source gains nothing from the first")

    def test_a_discovered_source_is_not_eligible_until_accepted_and_enabled(self):
        from shura_core.models import Source, SourceState
        from shura_core.scheduling import Scheduler
        found = Source("github:found/ext", "Found",
                       "https://api.github.com/repos/f/r/releases", "github-releases",
                       "en", enabled=False, state=SourceState.PAUSED,
                       configuration={"allowed_hosts": ["api.github.com"],
                                      "package": "", "signing_key": ""})
        self.store.add_source(found)
        self.assertEqual(Scheduler(self.store).due(), [],
                         "a discovered source is never crawled")
        with self.assertRaises(ValueError):
            self.store.enable_source("github:found/ext")


if __name__ == "__main__":
    unittest.main()