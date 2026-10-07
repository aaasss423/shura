import json, tempfile, unittest
from pathlib import Path
from shura_core.models import Source, Candidate, SourceState
from shura_core.state import StateStore
from shura_core.security.network import SafeHTTP, SafeRedirect, NetworkPolicyError, validate_url, is_private_host
from shura_core.crawler import IndexCrawler
from shura_core.coordinator import CrawlCoordinator
from shura_core.publishing.repository import RepositoryPublisher
from shura_core.publishing import protobuf, index_pb
from shura_core.observability import source_report


class FakeHTTP:
    def __init__(self, payload): self.payload = payload
    def get(self, url, headers=None): return self.payload, {}, url


class HardeningTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = StateStore(Path(self.tmp.name) / "h.db")
        self.base = Source("s1", "fixture", "https://example.org/list", "html", "ar",
                           configuration={"allowed_hosts": ["example.org"], "signing_key": "a" * 64})
        self.store.add_source(self.base)

    def tearDown(self):
        self.store.close(); self.tmp.cleanup()

    def test_private_and_reserved_hosts_are_blocked_by_default(self):
        for host in ("127.0.0.1", "169.254.169.254", "10.0.0.5", "192.168.1.1", "::1", "localhost"):
            self.assertTrue(is_private_host(host), host)
            with self.assertRaises(NetworkPolicyError):
                validate_url(f"https://{host}/x", {host})
        self.assertFalse(is_private_host("example.org"))
        # Explicit operator opt-in is required for controlled environments.
        self.assertEqual(validate_url("https://127.0.0.1/x", {"127.0.0.1"}, allow_private_hosts=True),
                         "https://127.0.0.1/x")

    def test_redirect_loop_and_disallowed_hop_rejected(self):
        seed = "https://example.org/a"
        loop = SafeRedirect({"example.org"}, 5, seed_url=seed)
        with self.assertRaises(NetworkPolicyError): loop.redirect_request(None, None, 302, "", {}, seed)
        hop = SafeRedirect({"example.org"}, 5, seed_url=seed)
        with self.assertRaises(NetworkPolicyError): hop.redirect_request(None, None, 302, "", {}, "https://evil.invalid/a")

    def test_crawl_budget_accounting_survives_crawler_exception(self):
        source = Source("boom", "boom", "https://example.org/x", "html",
                        configuration={"allowed_hosts": ["example.org"]})
        self.store.add_source(source)
        class FailingHTTP:
            def __init__(self, *a, **k): pass
            def get(self, *a, **k): raise RuntimeError("upstream exploded")
        coordinator = CrawlCoordinator(self.store, http_factory=lambda *a, **k: FailingHTTP())
        results, counters = coordinator.run({"boom"})
        self.assertEqual(counters["sourcesAttempted"], 1)
        self.assertEqual(counters["sourcesFailed"], 1)
        self.assertEqual(counters["sourcesAccounted"], 1)
        self.assertEqual(results, [])
        self.assertEqual(self.store.get_source("boom").state, SourceState.RETRY_LATER)

    def test_package_and_version_remain_distinct(self):
        payload = json.dumps({"extensions": [
            {"pkg": "org.example.ext", "version": "1.0", "apk": "https://example.org/a.apk", "name": "A"},
            {"pkg": "org.example.ext", "version": "2.0", "apk": "https://example.org/b.apk", "name": "A"},
        ]}).encode()
        source = Source("idx", "idx", "https://example.org/index.json", "index",
                        configuration={"allowed_hosts": ["example.org"]})
        result = IndexCrawler(FakeHTTP(payload)).crawl(source)
        identities = {c.identity for c in result.candidates}
        self.assertEqual(identities, {"org.example.ext|1.0", "org.example.ext|2.0"})

    def test_protobuf_index_roundtrip(self):
        scalar = protobuf.varint_field(3, 300)
        self.assertEqual(protobuf.decode(scalar)[3][0], 300)
        entries = [{"name": "Example", "pkg": "org.example.ext", "apk": "x.apk", "lang": "ar",
                    "version": "1.0", "code": 10000, "nsfw": False, "sources": [],
                    "apk_url": "https://example.org/apk/x.apk", "icon_url": ""}]
        decoded = index_pb.decode_index(index_pb.encode_index(
            entries, repo="Shura", signing_key="b" * 64, website="https://example.org"))
        self.assertEqual(decoded["repo"], "Shura")
        self.assertEqual(decoded["signing_key"], "b" * 64)
        self.assertEqual(decoded["website"], "https://example.org")
        self.assertEqual(decoded["extensions"][0]["package_name"], "org.example.ext")
        self.assertEqual(decoded["extensions"][0]["apk_url"], "https://example.org/apk/x.apk")
        self.assertEqual(decoded["extensions"][0]["version_code"], 10000)

    def test_publisher_emits_index_pb(self):
        artifact = Path(self.tmp.name) / "x.apk"; artifact.write_bytes(b"apk")
        c = Candidate("s1", "org.example.ext", "1.0", "https://example.org/x.apk", name="X", language="ar",
                      provenance={"page": "https://example.org/list"},
                      metadata={"_artifact_path": str(artifact), "_security": {"sha256": "c" * 64, "certificate": "a" * 64}})
        self.store.put_pending(c, "security-passed"); self.store.accept_pending("s1", c.identity)
        repo = Path(self.tmp.name) / "repo"
        RepositoryPublisher(self.store, repo).publish(release=True)
        self.assertTrue((repo / "index.pb").is_file())
        decoded = index_pb.decode_index((repo / "index.pb").read_bytes())
        self.assertEqual(decoded["extensions"][0]["package_name"], "org.example.ext")
        self.assertEqual(decoded["signing_key"], "a" * 64)
        self.assertTrue((repo / "apk" / "org_example_ext-1.0.apk").is_file())

    def test_accepted_source_state_is_reachable_and_eligible(self):
        self.store.transition("s1", SourceState.PAUSED, "discovered")
        self.store.accept_source("s1", reason="reviewed discovered source")
        source = self.store.get_source("s1")
        self.assertEqual(source.state, SourceState.ACCEPTED)
        self.assertTrue(self.store.eligible(source))
        self.assertEqual(source_report(self.store, "s1")["state"], "ACCEPTED")

    def test_acceptance_blocked_for_quarantined_source(self):
        c = Candidate("s1", "org.example.ext", "1", "https://example.org/a.apk", provenance={"page": "p"})
        self.store.quarantine_item(c, "signature mismatch", "d" * 64)
        with self.assertRaises(ValueError): self.store.accept_source("s1")

    def test_restart_preserves_quarantine_pending_and_publications(self):
        c = Candidate("s1", "org.example.ext", "1.0", "https://example.org/x.apk", name="X", language="ar",
                      provenance={"page": "p"})
        self.store.quarantine_item(c, "signature mismatch", "e" * 64)
        other = Candidate("s1", "org.other.ext", "2.0", "https://example.org/y.apk", name="Y", language="ar",
                          provenance={"page": "p"})
        self.store.put_pending(other, "security-passed")
        path = Path(self.tmp.name) / "h.db"
        self.store.close(); self.store = StateStore(path)
        self.assertTrue(self.store.is_quarantined("s1", c.identity))
        self.assertTrue(self.store.has_pending_for("s1", other.identity))
        self.assertEqual(self.store.get_source("s1").state, SourceState.QUARANTINED)


if __name__ == "__main__":
    unittest.main()
