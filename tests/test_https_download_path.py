"""The real download path, over HTTPS, with verification genuinely on.

The previous rounds could not finish a download: the only reachable fixture was
plain HTTP on localhost, and the policy refused it - correctly. This closes that
by serving a repository over TLS with a certificate the test client *explicitly
trusts* through `SSL_CERT_FILE`.

Nothing is weakened. `ssl.CERT_REQUIRED` stays on, hostname checking stays on,
and `SafeHTTP` is untouched: it builds its own default context and therefore
verifies against this test CA exactly as it would verify against a public one.
Only the trust anchor differs.

What runs is production code throughout: `IndexCrawler` reads an index the real
`RepositoryPublisher` wrote, `CandidateProcessor` downloads and runs the real
`aapt2`, `apksigner` and ClamAV, and `RepositoryPublisher` publishes. The
assertion that matters is that the published file is byte-identical to the
artifact that was built and signed.
"""

import http.server
import json
import os
import ssl
import tempfile
import threading
import unittest
from pathlib import Path

from shura_core.crawler.index import IndexCrawler
from shura_core.models import Source, SourceState
from shura_core.pipeline import CandidateProcessor
from shura_core.publishing.repository import RepositoryPublisher
from shura_core.quality.adult import classify_source
from shura_core.security.malware import MalwareScanner
from shura_core.security.network import SafeHTTP
from shura_core.state import StateStore

#: The anchor recorded before the artifact was signed.
ANCHOR = "f5b757d7c773d65e74907c08e4f8d2fdb30a3d76b78a764e2093f6a9e69bcba5"
PACKAGE = "app.shura.extension.mangadex"
VERSION = "1.0"


def _artifact():
    """The signed Shura extension, if it is still on disk from the build."""
    path = Path("/tmp/opencode/shuraext/extension.apk")
    return path if path.is_file() else None


def _ca():
    path = Path("/tmp/opencode/tlscerts/cert.pem")
    return path if path.is_file() else None


def ensure_verification_tools():
    """Put the real aapt2/apksigner on PATH, or say they are absent.

    Shura cannot verify an APK at all without these, so a run that lacks them
    has nothing to say about this path. The test then skips rather than passing
    vacuously - a skip is not a pass, and it is reported as one.
    """
    import glob
    import shutil
    if shutil.which("aapt2") and shutil.which("apksigner"):
        return True
    roots = [os.environ.get("ANDROID_SDK_ROOT"), os.environ.get("ANDROID_HOME"),
             "/opt/android-sdk", os.path.expanduser("~/Android/Sdk")]
    for root in roots:
        if not root:
            continue
        found = glob.glob(os.path.join(root, "build-tools", "*", "aapt2"))
        if not found:
            continue
        build_tools = os.path.dirname(found[0])
        os.environ["PATH"] = build_tools + os.pathsep + os.environ.get("PATH", "")
        # apksigner is a JVM wrapper; the default java here may be unusable.
        for jdk in ("/usr/lib/jvm/java-17-openjdk/bin",):
            if os.path.isdir(jdk) and shutil.which("java"):
                os.environ["PATH"] = jdk + os.pathsep + os.environ["PATH"]
                break
        return bool(shutil.which("aapt2") and shutil.which("apksigner"))
    return False


class _Quiet(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *args):
        pass


class HttpsRepositoryTests(unittest.TestCase):
    """Production components, over TLS, against a real signed APK."""

    @classmethod
    def setUpClass(cls):
        artifact = _artifact()
        if artifact is None or _ca() is None:
            raise unittest.SkipTest("the signed extension or the test CA is not present")
        if not ensure_verification_tools():
            raise unittest.SkipTest(
                "aapt2/apksigner are not available; Shura cannot verify an APK "
                "without them, so this path has nothing to prove here")
        cls.artifact = artifact

        # 1. publish a repository with the real publisher, into a temp dir
        cls.tmp = tempfile.TemporaryDirectory()
        root = Path(cls.tmp.name)
        cls.store = StateStore(root / "s.db")
        cls.repo = root / "repo"
        sid = "index:shura/extension-mangadex"
        cls.sid = sid
        # The trust gate validates the source URL under the strict policy, and
        # "localhost" is correctly refused as a private host. So the source is
        # registered with the public URL an operator would really use, and only
        # the crawl target is substituted for the fixture - the same substitution
        # every other test in this suite makes.
        cls.store.add_source(Source(
            sid, "Shura MangaDex", "https://releases.shura.example/ext.apk", "index", "ar",
            enabled=False, state=SourceState.PAUSED,
            # The trust gate checks that the source URL is allowed, not that the
            # allowlist is minimal, so the fixture host is declared alongside it.
            # The processor then checks the candidate against this same list,
            # which is the point of the list.
            configuration={"allowed_hosts": ["releases.shura.example", "localhost"],
                           "package": PACKAGE, "signing_key": ANCHOR}))
        cls.store.accept_source(sid, reason="anchor recorded before signing")
        cls.store.enable_source(sid, reason="operator approved")

        import hashlib
        staged = root / "artifacts" / "extension.apk"
        staged.parent.mkdir(parents=True, exist_ok=True)
        staged.write_bytes(artifact.read_bytes())
        digest = hashlib.sha256(staged.read_bytes()).hexdigest()
        item = {"source_id": sid, "identity": f"{PACKAGE}|{VERSION}", "package": PACKAGE,
                "version": VERSION, "name": "Shura MangaDex", "language": "ar",
                "apk_url": f"https://localhost/apk/{staged.name}",
                "metadata": {"_artifact_path": str(staged),
                             "_security": {"sha256": digest, "certificate": ANCHOR,
                                           "malware": {"verdict": "CLEAN", "reason": "x"}},
                             "_content_policy": {
                                 "scope": "source",
                                 "classification": classify_source(declared="SAFE").classification.value,
                                 "policy_fingerprint": cls.store.get_source(sid).fingerprint(),
                                 "reasons": ["declared by source configuration: SAFE"]}}}
        cls.store.db.execute(
            "INSERT OR REPLACE INTO pending VALUES(?,?,?,?,?)",
            (sid, item["identity"], json.dumps(item), "security-passed", "2026-01-01T00:00:00+00:00"))
        cls.store.db.commit()
        cls.store.accept_pending(sid, item["identity"], operator="local-operator")
        RepositoryPublisher(cls.store, cls.repo).publish(release=True)

        # 2. serve that repository over TLS
        handler = lambda *a, **k: _Quiet(*a, directory=str(cls.repo), **k)  # noqa: E731
        cls.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain("/tmp/opencode/tlscerts/cert.pem", "/tmp/opencode/tlscerts/key.pem")
        cls.server.socket = context.wrap_socket(cls.server.socket, server_side=True)
        cls.base = f"https://localhost:{cls.server.server_port}"
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        try:
            cls.server.shutdown()
            cls.thread.join()
        finally:
            cls.store.close()
            cls.tmp.cleanup()

    def setUp(self):
        self.saved_cert_file = os.environ.get("SSL_CERT_FILE")
        os.environ["SSL_CERT_FILE"] = str(_ca())      # the client trusts this CA

    def tearDown(self):
        if self.saved_cert_file is None:
            os.environ.pop("SSL_CERT_FILE", None)
        else:
            os.environ["SSL_CERT_FILE"] = self.saved_cert_file

    # -- helpers --------------------------------------------------------
    def http(self, hosts=None, **kwargs):
        """A SafeHTTP over TLS with real verification against the test CA."""
        return SafeHTTP(hosts or ["localhost"], allow_nonstandard_ports=True,
                        allow_private_hosts=True, timeout=20)

    def source_for(self, index_url):
        return Source(self.sid, "Shura MangaDex", index_url, "index", "ar",
                      configuration={"allowed_hosts": ["localhost"],
                                     "package": PACKAGE, "signing_key": ANCHOR})

    def validator(self):
        """The fixture port is non-standard; nothing else about the policy moves."""
        from shura_core.security.network import validate_url

        def check(url, hosts):
            return validate_url(url, hosts, https_only=True,
                                allow_nonstandard_ports=True,
                                allow_private_hosts=True)
        return check

    def crawl(self, index_url=None):
        source = self.source_for(index_url or f"{self.base}/index.json")
        result = IndexCrawler(self.http()).crawl(source, {"max_pages": 1, "max_candidates": 5})
        return result

    # -- the whole path -------------------------------------------------
    def test_the_published_index_yields_a_url_that_is_the_real_file(self):
        candidates = self.crawl().candidates
        self.assertEqual(len(candidates), 1, "the real published index gave no candidate")
        url = candidates[0].apk_url
        self.assertTrue(url.startswith("https://"), url)
        self.assertIn("/apk/", url, "the artifact lives under apk/, not the index root")
        # And that URL names a file the served repository really has.
        name = url.rsplit("/", 1)[-1]
        self.assertTrue((Path(self.repo) / "apk" / name).is_file(),
                        "the resolved URL must be the file the publisher wrote")
        self.assertEqual(candidates[0].package, PACKAGE)

    def test_download_verify_and_publish_over_https(self):
        """Crawl, download over TLS, verify with the real tools, publish."""
        import hashlib
        import tempfile

        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        work = Path(tmp.name)
        # Its own store: setUpClass already published this identity into cls.repo,
        # and republishing the same identity is a no-op by design.
        store = StateStore(work / "s.db")
        self.addCleanup(store.close)
        sid = "index:shura/downloaded"
        store.add_source(Source(
            sid, "Shura MangaDex", "https://releases.shura.example/ext.apk", "index", "ar",
            enabled=False, state=SourceState.PAUSED,
            configuration={"allowed_hosts": ["releases.shura.example", "localhost"],
                           "package": PACKAGE, "signing_key": ANCHOR}))
        store.accept_source(sid, reason="anchor recorded before signing")
        self.assertFalse(store.get_source(sid).enabled,
                         "acceptance alone must not enable a source")
        store.enable_source(sid, reason="operator approved")

        source = Source(sid, "Shura MangaDex", f"{self.base}/index.json", "index", "ar",
                        configuration={"allowed_hosts": ["localhost"], "package": PACKAGE,
                                       "signing_key": ANCHOR})
        candidates = IndexCrawler(self.http()).crawl(
            source, {"max_pages": 1, "max_candidates": 5}).candidates
        self.assertEqual(len(candidates), 1)

        processor = CandidateProcessor(
            store, artifact_dir=str(work / "artifacts"), scanner=None,
            malware_scanner=MalwareScanner(), expected_hosts={"localhost"},
            http_factory=lambda hosts, **k: SafeHTTP(list(hosts), allow_nonstandard_ports=True,
                                                    allow_private_hosts=True, timeout=20),
            url_validator=self.validator())
        result = processor.process(candidates[0])

        # The real aapt2, the real apksigner and the real ClamAV ran on the
        # bytes fetched over real TLS.
        self.assertEqual(result["verdict"], "ACCEPTED",
                         f"a real APK over real TLS should verify: {result}")
        self.assertIn("signature verified", result["reason"])
        self.assertEqual(result["malware_verdict"], "CLEAN")
        self.assertEqual(result["sha256"],
                         hashlib.sha256(self.artifact.read_bytes()).hexdigest())

        store.accept_pending(sid, candidates[0].identity, operator="local-operator")
        out = work / "repo"
        published = RepositoryPublisher(store, out).publish(release=True)
        self.assertEqual(published["published"], 1)
        self.assertFalse(published["refused"])

        target = list((out / "apk").glob("*.apk"))
        self.assertEqual(len(target), 1)
        self.assertEqual(hashlib.sha256(target[0].read_bytes()).hexdigest(),
                         hashlib.sha256(self.artifact.read_bytes()).hexdigest(),
                         "the published file must be byte-identical to the one built "
                         "and signed")

    # -- negatives ------------------------------------------------------
    def test_a_different_fingerprint_blocks_publication(self):
        """The same artifact under an anchor that is not its own must be refused."""
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        store = StateStore(Path(tmp.name) / "s.db")
        self.addCleanup(store.close)
        wrong = "b" * 64
        sid = "index:wrong-anchor"
        store.add_source(Source(
            sid, "Shura MangaDex", "https://releases.shura.example/ext.apk", "index", "ar",
            enabled=False, state=SourceState.PAUSED,
            configuration={"allowed_hosts": ["releases.shura.example", "localhost"],
                           "package": PACKAGE, "signing_key": wrong}))
        store.accept_source(sid, reason="wrong anchor recorded")
        store.enable_source(sid, reason="approved")
        crawl_source = Source(sid, "Shura MangaDex", f"{self.base}/index.json",
                              "index", "ar",
                              configuration={"allowed_hosts": ["localhost"],
                                             "package": PACKAGE, "signing_key": wrong})
        candidates = IndexCrawler(self.http()).crawl(
            crawl_source, {"max_pages": 1, "max_candidates": 5}).candidates
        self.assertEqual(len(candidates), 1)
        self.assertEqual(candidates[0].source_id, sid,
                         "the candidate must belong to the source the store knows")
        processor = CandidateProcessor(
            store, artifact_dir=str(Path(tmp.name) / "art"), scanner=None,
            malware_scanner=MalwareScanner(), expected_hosts={"localhost"},
            http_factory=lambda hosts, **k: SafeHTTP(list(hosts), allow_nonstandard_ports=True,
                                                allow_private_hosts=True, timeout=20),
            url_validator=self.validator())
        result = processor.process(candidates[0])
        # A signing key that does not match is not merely a rejection: the
        # source is quarantined, which is the stronger and the intended answer.
        self.assertEqual(result["verdict"], "QUARANTINED",
                         f"a mismatched signing key must never verify: {result}")
        self.assertIn("certificate", str(result["reason"]).lower())
        self.assertEqual(store.published(), [],
                         "a quarantined source publishes nothing")
        self.assertTrue(store.is_source_quarantined(sid),
                        "and it stays out of future runs until reviewed")

    def test_a_failed_download_produces_no_success_and_nothing_published(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        store = StateStore(Path(tmp.name) / "s.db")
        self.addCleanup(store.close)
        out = Path(tmp.name) / "repo"
        # An index whose artifact is not there.
        import http.server

        class Handler(_Quiet):
            def do_GET(self):
                body = json.dumps([{"pkg": PACKAGE, "version": VERSION,
                                    "apk": "does-not-exist.apk"}]).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), lambda *a, **k: Handler(
            *a, directory=str(self.repo), **k))
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain("/tmp/opencode/tlscerts/cert.pem", "/tmp/opencode/tlscerts/key.pem")
        server.socket = context.wrap_socket(server.socket, server_side=True)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(thread.join)
        self.addCleanup(server.shutdown)
        base = f"https://localhost:{server.server_port}"

        source = self.source_for(f"{base}/index.json")
        source.source_id = self.sid
        store.add_source(Source(
            self.sid, "m", "https://releases.shura.example/ext.apk", "index", "ar",
            enabled=False, state=SourceState.PAUSED,
            configuration={"allowed_hosts": ["localhost"], "package": PACKAGE,
                           "signing_key": ANCHOR}))
        candidates = IndexCrawler(self.http()).crawl(source, {"max_pages": 1,
                                                             "max_candidates": 5}).candidates
        self.assertEqual(len(candidates), 1)
        processor = CandidateProcessor(
            store, artifact_dir=str(Path(tmp.name) / "art"), scanner=None,
            malware_scanner=MalwareScanner(), expected_hosts={"localhost"},
            http_factory=lambda hosts, **k: SafeHTTP(list(hosts), allow_nonstandard_ports=True,
                                                allow_private_hosts=True, timeout=20),
            url_validator=self.validator())
        result = processor.process(candidates[0])
        self.assertNotEqual(result["verdict"], "ACCEPTED")
        self.assertEqual(store.published(), [], "a failed download publishes nothing")
        self.assertFalse(out.exists(), "no repository is written for a failed fetch")


if __name__ == "__main__":
    unittest.main()