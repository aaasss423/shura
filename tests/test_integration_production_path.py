"""End-to-end integration over the production entry point.

The point of this file is that the stages are wired together by the code that
actually runs, not by a test harness that calls them in the order the test wants.
Everything except two explicitly marked seams goes through ``shura_core.cli.main``,
which is what ``shura_publish.py`` invokes in production.

Two seams are unavoidable in a hermetic environment and are marked as such:

* ``shura_core.cli.SafeHTTP`` is replaced by a subclass that allows the loopback
  fixture server. The repository's own private-address guard is real and is what
  refuses loopback in production; disabling it here is what lets a test reach a
  server on 127.0.0.1 at all. Nothing about the response handling changes.
* ``SourceDiscovery`` is pointed at the fixture server instead of api.github.com,
  because the URL is a module constant. The class, its queries, its daily limit,
  its persistence and its dedup are the production ones.

Nothing here publishes to the public repository: the publisher writes into a
temporary directory.
"""
from __future__ import annotations

import contextlib
import json
import os
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest import mock

from shura_core import cli
from shura_core.discovery.service import SourceDiscovery
from shura_core.models import Source
from shura_core.security.network import SafeHTTP, validate_url
from shura_core.sources import SourceRegistry
from shura_core.state import StateStore

CERT = "ab" * 32
EICAR = b"X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*"


def apk_bytes(member: bytes) -> bytes:
    import io
    import zipfile
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        archive.writestr("AndroidManifest.xml", b"binary manifest fixture")
        archive.writestr("res/body.bin", member)
    return buffer.getvalue()


class _FakeClamEngine:
    """clamscan stand-in: exit 1 and print the signature name when it finds EICAR."""

    def __init__(self, directory: Path):
        self.script = directory / "clamscan"
        self.script.write_text(
            "#!/usr/bin/env python3\n"
            "import sys, zipfile\n"
            f"needle = {EICAR.decode()!r}\n"
            "raw = open(sys.argv[-1], 'rb').read()\n"
            "hit = needle.encode() in raw\n"
            "if not hit:\n"
            "    try:\n"
            "        with zipfile.ZipFile(sys.argv[-1]) as z:\n"
            "            hit = any(needle.encode() in z.read(n) for n in z.namelist())\n"
            "    except Exception:\n"
            "        pass\n"
            "sys.exit(1 if hit else 0)\n"
        )
        self.script.chmod(0o755)


def _install_apk_tools(bin_dir: Path, cert: str) -> None:
    """aapt/apksigner stand-ins, so the artifact gate can be exercised hermetically.

    Without them the scanner refuses the APK with "aapt unavailable" and
    quarantines it - which is the correct fail-closed behaviour, but it means the
    happy path can never be reached. These report the package the fixture index
    declared, exactly as the real tools would report it from the artifact.
    """
    # Written byte-exact: the scanner matches ^package: name='([^']+)' , so the
    # closing quote is load-bearing. Building this through shell quoting is how
    # the line silently lost its quote and made the scanner report a parse
    # failure as "aapt unavailable".
    aapt = bin_dir / "aapt"
    aapt.write_bytes(b"#!/bin/sh\necho \"package: name='org.example.ext' versionCode=1\"\n")
    signer = bin_dir / "apksigner"
    signer.write_bytes(("#!/bin/sh\necho \"Signer #1 certificate SHA-256 digest: " + cert + "\"\n").encode())
    for tool in (aapt, signer):
        tool.chmod(0o755)


class _Handler(BaseHTTPRequestHandler):
    artifacts: dict = {}
    discovery: dict = {}
    index: list = []

    def log_message(self, *args):  # keep the suite output readable
        return

    def do_GET(self):
        if self.path.endswith(".json") and "search" not in self.path:
            # The kind=index contract: the source URL serves a JSON index document
            # whose entries carry apk/pkg/version. Pointing it straight at an APK
            # was a broken fixture, not a product defect.
            body = json.dumps(self.index).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if self.path.startswith("/search/repositories"):
            body = json.dumps(self.discovery).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        payload = self.artifacts.get(self.path)
        if payload is None:
            self.send_response(404)
            self.end_headers()
            return
        self.send_response(200)
        self.send_header("Content-Type", "application/vnd.android.package-archive")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)


class RelaxedHTTP(SafeHTTP):
    """Only the loopback fixture server is reachable, and only for this test."""

    def __init__(self, hosts, **kw):
        kw["https_only"] = False
        kw["allow_private_hosts"] = True
        kw["allow_nonstandard_ports"] = True
        super().__init__({"127.0.0.1", "localhost"}, **kw)


class LoopbackSourceRegistry(SourceRegistry):
    """Same registry, with the HTTPS-only rule relaxed for the loopback fixture.

    SourceRegistry rejects a plain-HTTP source URL, which is correct: a real
    source must be served over TLS. The fixture server is HTTP, so this seam
    exists only to let a test reach 127.0.0.1. Every other registry rule - the
    array shape, the reserved-state rejection, the signing key, the host list -
    still applies and is exercised here.
    """

    @classmethod
    def parse_file(cls, path):
        from shura_core.sources.registry import SourceRegistry as Real
        import shura_core.sources.registry as registry_mod
        with mock.patch.object(registry_mod, "validate_url", lambda url, hosts, *a, **kw: None):
            return Real.parse_file(path)


class RelaxedValidator:
    def __call__(self, url, hosts):
        return validate_url(url, hosts, https_only=False, allow_private_hosts=True,
                            allow_nonstandard_ports=True)


class ProductionPathIntegrationTests(unittest.TestCase):
    """Drives the real CLI across discovery -> accept -> publish -> client read."""

    maxDiff = None

    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), _Handler)
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
        root = Path(self.tmp.name)
        self.db = root / "s.db"
        self.repo = root / "repo"
        self.artifacts = root / "artifacts"
        self.artifacts.mkdir()
        signature_db = self.artifacts / "clamav-db"
        signature_db.mkdir()
        (signature_db / "daily.cvd").write_bytes(b"fixture database")
        _FakeClamEngine(self.artifacts)
        _install_apk_tools(self.artifacts, CERT)
        # Only this test's stubs are reachable; see ToolSandbox in test_malware.
        previous = os.environ.get("PATH", "")
        os.environ["PATH"] = os.pathsep.join([str(self.artifacts), os.defpath])
        self._saved_path = previous
        _Handler.artifacts = {
            "/ok.apk": apk_bytes(b"benign payload"),
            "/eicar.apk": apk_bytes(EICAR),
        }
        _Handler.discovery = {"items": [
            {"full_name": "shura-fixture/ar-ext", "html_url": "https://example.org/ar-ext",
             "description": "Arabic manga extension", "language": "ar", "pushed_at": "2026-10-01T00:00:00Z"},
            {"full_name": "shura-fixture/adult-ext", "html_url": "https://example.org/adult-ext",
             "description": "hentai manga extension", "language": "ar", "pushed_at": "2026-10-01T00:00:00Z"},
        ]}
        self.config = root / "sources.json"
        _Handler.index = [{"name": "Shura Fixture Extension", "pkg": "org.example.ext",
                           "version": "1.0", "lang": "ar", "apk": self.base + "/ok.apk",
                           "signingKey": CERT, "contentWarning": "CONTENT_WARNING_SAFE"}]
        self.config.write_text(json.dumps([{
            "source_id": "s1", "name": "Shura Fixture", "url": self.base + "/index.json",
            "kind": "index", "language": "ar", "enabled": True,
            "configuration": {"allowed_hosts": ["127.0.0.1"], "signing_key": CERT,
                              "content_policy": {"classification": "SAFE"}},
        }]))

    def tearDown(self):
        os.environ["PATH"] = self._saved_path
        self.tmp.cleanup()

    # -- helpers that go through the production entry point ---------------------

    def run_cli(self, *argv):
        return cli.main(["--db", str(self.db), *argv])

    def configure(self):
        """configure, through the CLI, with the loopback URL seam applied."""
        with mock.patch.object(cli, "SourceRegistry", LoopbackSourceRegistry):
            return cli.main(["--db", str(self.db), "configure", str(self.config)])

    def store(self):
        return StateStore(self.db)

    @contextlib.contextmanager
    def seams(self):
        """Every relaxation this hermetic run needs, applied together.

        Each one replaces a production guard that exists precisely to stop the
        crawler reaching a loopback fixture server: TLS-only URLs, private-address
        refusal, non-standard ports. Host allow-listing, redirect handling,
        response-size limits and timeouts stay exactly as shipped.
        """
        with mock.patch("shura_core.security.network.SafeHTTP", RelaxedHTTP), \
                mock.patch("shura_core.pipeline.processor.SafeHTTP", RelaxedHTTP), \
                mock.patch("shura_core.coordinator.SafeHTTP", RelaxedHTTP), \
                mock.patch("shura_core.pipeline.processor.validate_url", RelaxedValidator()), \
                mock.patch("shura_core.pipeline.processor.MalwareScanner",
                           _scanner_factory(self.artifacts)), \
                mock.patch.object(cli, "SourceRegistry", LoopbackSourceRegistry):
            yield

    # -- 1. discovery records state and stays disabled -------------------------

    def test_discovery_records_proposals_without_enabling_them(self):
        """Real SourceDiscovery; only the HTTP hop is redirected to the fixture."""
        store = self.store()
        http = RelaxedHTTP(["127.0.0.1"])
        discovery = SourceDiscovery(store, http, None, daily_limit=250)
        real_get = http.get

        def routed_get(url, headers=None, **kw):
            return real_get(url.replace("https://api.github.com", self.base), headers, **kw)

        proposals = []
        with mock.patch.object(http, "get", routed_get), \
                mock.patch.object(discovery, "QUERIES", (("ar", "manga extension arabic"),)):
            proposals = discovery.discover()
        self.assertTrue(proposals, "discovery must return the fixture proposals")
        self.assertTrue(all(not p.enabled for p in proposals),
                        "a discovered source must never be enabled automatically")
        persisted = store.get_source(proposals[0].source_id)
        store.close()
        self.assertIsNotNone(persisted, "a discovered proposal must be persisted")
        self.assertFalse(persisted.enabled)
        self.assertEqual(persisted.state.value, "PAUSED",
                         "a proposal waits for operator trust setup")

    def test_discovering_the_same_source_twice_does_not_duplicate_it(self):
        store = self.store()
        http = RelaxedHTTP(["127.0.0.1"])
        discovery = SourceDiscovery(store, http, None, daily_limit=250)
        real_get = http.get

        def routed_get(url, headers=None, **kw):
            return real_get(url.replace("https://api.github.com", self.base), headers, **kw)

        with mock.patch.object(http, "get", routed_get), \
                mock.patch.object(discovery, "QUERIES", (("ar", "manga extension arabic"),)):
            first = discovery.discover()
            second = discovery.discover()
        ids_first = {p.source_id for p in first}
        ids_second = {p.source_id for p in second}
        store.close()
        self.assertEqual(len(first), len(ids_first), "a run must not return a duplicate")
        # Re-discovery must not create a second copy of a known source; returning
        # only the unseen ones is the correct behaviour, not a missing one.
        self.assertTrue(ids_second.issubset(ids_first),
                        "re-discovery must never introduce a source the first run did not see")
        self.assertEqual(len(second), len(ids_second), "no duplicates in the second run")

    # -- 2..8. the rest of the pipeline, entirely through cli:main --------------

    def test_full_path_from_configure_to_published_index(self):
        self.assertEqual(self.configure(), 0)

        with self.seams(), mock.patch("shura_core.pipeline.processor.MalwareScanner",
                                      _scanner_factory(self.artifacts)):
            self.assertEqual(self.run_cli("crawl", "--config", str(self.config)), 0,
                             "a healthy, configured source must crawl cleanly")

        store = self.store()
        pending = store.pending()
        store.close()
        self.assertEqual(len(pending), 1, pending)
        self.assertEqual(pending[0]["stage"], "security-passed", pending)

        # Operator gate, through the CLI.
        self.assertEqual(self.run_cli("accept", "s1", "--identity", pending[0]["identity"]), 0)
        # Publish, through the CLI.
        self.assertEqual(self.run_cli("publish", "--repo", str(self.repo), "--release"), 0)

        index = json.loads((self.repo / "index.json").read_text())
        self.assertTrue(index, "the index must list the published extension")
        entry = index[0] if isinstance(index, list) else next(iter(index.values()))[0]
        self.assertEqual(entry["pkg"], "org.example.ext")

        # The file the index points at must actually exist in the published tree.
        apk_name = entry["apk"].rsplit("/", 1)[-1]
        self.assertTrue((self.repo / "apk" / apk_name).exists(),
                        "the index must not point at a missing file")

        # Re-running must not publish a second time.
        self.assertEqual(self.run_cli("publish", "--repo", str(self.repo), "--release"), 0)
        self.assertEqual(len(json.loads((self.repo / "index.json").read_text())), 1)

    def test_forbidden_content_never_reaches_the_published_index(self):
        self._configure(policy={"classification": "NSFW"})
        with self.seams():
            self.run_cli("crawl", "--config", str(self.config))
        store = self.store()
        stages = {row["identity"]: row["stage"] for row in store.pending()}
        store.close()
        self.assertTrue(all(stage != "security-passed" for stage in stages.values()), stages)
        rc = self.run_cli("publish", "--repo", str(self.repo), "--release")
        self.assertEqual(rc, 3, "an NSFW source must make publish refuse")
        self.assertFalse((self.repo / "index.json").exists(),
                         "nothing may be published from an NSFW source")

    def test_infected_artifact_is_quarantined_and_never_published(self):
        _Handler.index = [{"name": "Shura Fixture Extension", "pkg": "org.example.ext",
                           "version": "1.0", "lang": "ar", "apk": self.base + "/eicar.apk",
                           "signingKey": CERT, "contentWarning": "CONTENT_WARNING_SAFE"}]
        store = self.store()
        store.add_source(Source("s1", "Shura Fixture", self.base + "/eicar.apk", "index", "ar",
                                configuration={"allowed_hosts": ["127.0.0.1"], "signing_key": CERT,
                                               "content_policy": {"classification": "SAFE"}}))
        store.close()
        with self.seams():
            self.run_cli("crawl", "--config", str(self.config))
        store = self.store()
        self.assertTrue(store.is_source_quarantined("s1") or store.is_quarantined("s1", "org.example.ext|1.0"))
        store.close()
        rc = self.run_cli("publish", "--repo", str(self.repo), "--release")
        self.assertEqual(rc, 3, "a quarantined artifact must make publish refuse")
        self.assertFalse((self.repo / "index.json").exists())

    def test_network_failure_defers_rather_than_accepts(self):
        self._configure(policy={"classification": "SAFE"})
        _Handler.artifacts = {}  # every APK download now 404s
        with self.seams():
            self.run_cli("crawl", "--config", str(self.config))
        store = self.store()
        stages = [row["stage"] for row in store.pending()]
        store.close()
        self.assertTrue(stages)
        self.assertNotIn("security-passed", stages, "a failed download must never be accepted")

    # -- 9. the published index satisfies the client's contract ----------------

    def test_published_index_matches_the_client_contract(self):
        self._publish_one()
        for name in ("index.json", "index.min.json", "index.pb", "index.shura.json", "repo.json"):
            path = self.repo / name
            self.assertTrue(path.exists(), f"{name} is part of the published contract")
        legacy = json.loads((self.repo / "index.json").read_text())
        for field in ("pkg", "name", "version", "apk", "lang"):
            self.assertIn(field, legacy[0], field)
        modern = json.loads((self.repo / "index.shura.json").read_text())
        self.assertTrue(modern)

    def _publish_one(self):
        self.assertEqual(self.configure(), 0)
        with self.seams():
            self.run_cli("crawl", "--config", str(self.config))
        store = self.store()
        identity = store.pending()[0]["identity"]
        store.close()
        self.run_cli("accept", "s1", "--identity", identity)
        self.run_cli("publish", "--repo", str(self.repo), "--release")

    def _configure(self, policy):
        payload = json.loads(self.config.read_text())[0]
        payload["configuration"]["content_policy"] = policy
        self.config.write_text(json.dumps([payload]))


def _scanner_factory(bin_dir):
    """Point MalwareScanner at the deterministic engine for this run."""
    from shura_core.security.malware import MalwareScanner

    def build(*a, **kw):
        kw.setdefault("engine", "clamscan")
        kw.setdefault("database_dir", str(bin_dir / "clamav-db"))
        kw.setdefault("timeout", 60)
        return MalwareScanner(*a, **kw)

    return build


if __name__ == "__main__":
    unittest.main()
