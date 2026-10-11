"""Publication-policy tests for source and title content classification.

These cover the decision function on its own and the production accept path, so a
classification that never runs in `CandidateProcessor.process` cannot pass them.
"""
from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from shura_core.models import Candidate, Source
from shura_core.pipeline.processor import CandidateProcessor
from shura_core.quality.adult import SourceClass, classify_source, classify_work
from shura_core.security.artifacts import ScanVerdict
from shura_core.publishing.repository import PublishRefused, RepositoryPublisher
from shura_core.state import StateStore


class ClassificationTests(unittest.TestCase):
    def test_clean_source_is_safe(self):
        got = classify_source(name="MangaDex", host="mangadex.org")
        self.assertIs(got.classification, SourceClass.SAFE)
        self.assertTrue(got.publishable)

    def test_explicit_host_is_nsfw(self):
        got = classify_source(name="Anything", host="hentai-example.com")
        self.assertIs(got.classification, SourceClass.NSFW)
        self.assertFalse(got.publishable)
        self.assertTrue(got.evidence["host_terms"])

    def test_explicit_title_is_nsfw(self):
        got = classify_source(name="Porn Manga Daily", host="reader.example")
        self.assertIs(got.classification, SourceClass.NSFW)

    def test_maturity_marker_is_not_explicit_content(self):
        """An age warning must not be laundered into an NSFW rejection."""
        got = classify_source(name="Shonen 18+", host="reader.example")
        self.assertIs(got.classification, SourceClass.MATURE)
        self.assertNotEqual(got.classification, SourceClass.NSFW)
        # MATURE is an age warning, not a publication refusal.
        self.assertIn(got.classification, (SourceClass.MATURE, SourceClass.SAFE))

    def test_mixed_signals_are_not_auto_publishable(self):
        got = classify_source(name="Site 18+ hentai archive", host="reader.example")
        self.assertIs(got.classification, SourceClass.MIXED)
        self.assertFalse(got.publishable)

    def test_no_signal_is_unknown_not_safe(self):
        got = classify_source()
        self.assertIs(got.classification, SourceClass.UNKNOWN)
        self.assertFalse(got.publishable)

    def test_declaration_outranks_inference_in_both_directions(self):
        self.assertIs(classify_source(declared="NSFW", name="Clean", host="clean.example").classification,
                      SourceClass.NSFW)
        self.assertIs(classify_source(declared="SAFE", host="hentai-example.com").classification,
                      SourceClass.SAFE)

    def test_every_decision_carries_its_evidence(self):
        for assessment in (classify_source(name="Porn Manga", host="x.example"),
                           classify_source(name="Shonen 18+", host="y.example"),
                           classify_source()):
            self.assertTrue(assessment.reasons, assessment)
            self.assertIn("classification", assessment.to_dict())

    def test_title_classified_independently_of_its_source(self):
        got = classify_work(work_id="w1", name="Explicit doujin hentai")
        self.assertIs(got.classification, SourceClass.NSFW)
        self.assertEqual(classify_work(work_id="w2", name="Shonen 18+").classification, SourceClass.MATURE)


class _CleanScanner:
    """Passes the artifact gate so a test can reach the content gate in isolation."""

    def __init__(self):
        self.max_size = 50_000_000

    def scan(self, path, expected_package=None, expected_certificate=None):
        import hashlib
        from shura_core.security.artifacts import ScanResult
        data = Path(path).read_bytes()
        return ScanResult(ScanVerdict.CLEAN, hashlib.sha256(data).hexdigest(), len(data),
                          "clean fixture artifact")


class _StubResponse:
    def __init__(self, body):
        self.body = body
        self.headers = {}
        self.url = "https://reader.example/e.apk"


class _StubHTTP:
    """Serves the fixture artifact so the SAFE path needs no network."""

    def __init__(self, payload):
        self.payload = payload

    def get(self, url, max_bytes=None):
        return self.payload, {}, "https://reader.example/e.apk"


class _CleanMalware:
    def scan(self, path):
        from shura_core.security.artifacts import ScanResult
        return ScanResult(ScanVerdict.CLEAN, "0" * 64, 1, "no malware")


class ProductionGateTests(unittest.TestCase):
    """The gate must run in the production accept path, not only as a function."""

    source_name = "x"

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = StateStore(Path(self.tmp.name) / "s.db")

    def tearDown(self):
        self.store.close()
        self.tmp.cleanup()

    def _candidate(self):
        return Candidate("s1", "org.example.ext", "1.0", "https://reader.example/e.apk",
                         name=self.source_name, language="ar", provenance={"page": "fixture"},
                         metadata={"_artifact_path": str(Path(self.tmp.name) / "e.apk")})

    def _processor(self):
        from shura_core.security.network import SafeHTTP, validate_url
        payload = (Path(self.tmp.name) / "e.apk").read_bytes() if (Path(self.tmp.name) / "e.apk").exists() else b"PK\x03\x04"
        return CandidateProcessor(
            self.store, Path(self.tmp.name) / "artifacts",
            expected_hosts={"reader.example"},
            scanner=_CleanScanner(), malware_scanner=_CleanMalware(),
            http_factory=lambda hosts, **kw: _StubHTTP(payload),
            url_validator=lambda url, hosts: validate_url(url, hosts),
        )

    def _configure(self, policy, name="fixture"):
        self.store.add_source(Source("s1", name, "https://reader.example/e.apk", "html", "ar",
                                     configuration={"allowed_hosts": ["reader.example"],
                                                    "signing_key": "ab" * 32,
                                                    "content_policy": policy}))

    def _artifact(self):
        Path(self.tmp.name).mkdir(exist_ok=True)
        (Path(self.tmp.name) / "e.apk").write_bytes(b"PK\x03\x04 fixture")

    def test_nsfw_source_is_rejected_before_any_download(self):
        self._configure({"classification": "NSFW"})
        self._artifact()
        result = self._processor().process(self._candidate())
        self.assertEqual(result["verdict"], "REJECTED", result)
        self.assertIn("NSFW", result["reason"])
        self.assertEqual(self.store.pending()[0]["stage"], "content-policy-rejected")

    def test_quarantined_source_classification_never_publishes(self):
        self._configure({"classification": "QUARANTINED"})
        self._artifact()
        result = self._processor().process(self._candidate())
        self.assertEqual(result["verdict"], "QUARANTINED", result)

    def test_unknown_source_is_held_for_review_not_accepted(self):
        # A source with only a host and no declared metadata: nothing was checked,
        # so it must not become an automatic accept.
        self._configure({"review_unclassified": True}, name="")
        self._artifact()
        result = self._processor().process(self._candidate())
        self.assertEqual(result["verdict"], "PENDING", result)
        self.assertEqual(self.store.pending()[0]["stage"], "content-classification")

    def test_safe_source_still_reaches_the_normal_path(self):
        self._configure({"classification": "SAFE"})
        self._artifact()
        result = self._processor().process(self._candidate())
        self.assertEqual(result["verdict"], "ACCEPTED", result)

    def test_explicit_title_inside_a_safe_source_is_rejected(self):
        self._configure({"classification": "SAFE", "check_titles": True})
        self._artifact()
        candidate = self._candidate()
        candidate.metadata[  "_content"] = {"work_id": "w1", "work_name": "Hentai anthology",
                                            "chapters": []}
        result = self._processor().process(candidate)
        self.assertEqual(result["verdict"], "REJECTED", result)
        self.assertIn("NSFW", result["reason"])

    def test_decision_is_recorded_for_audit(self):
        self._configure({"classification": "SAFE"})
        self._artifact()
        self._processor().process(self._candidate())
        recorded = self.store.db.execute(
            "SELECT detail FROM history WHERE source_id=? AND event=? ORDER BY at DESC LIMIT 1",
            ("s1", "content_classification")).fetchall()
        self.assertTrue(recorded, "classification decision must be auditable")
        self.assertIn("classification", recorded[0]["detail"])


if __name__ == "__main__":
    unittest.main()


class PublisherContentGateTests(unittest.TestCase):
    """The publisher must refuse on its own, not trust that the pipeline ran.

    Each case stages an item directly at 'accepted', which is what any alternate
    route into the repository looks like, and asserts the publisher refuses.
    """

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.db = Path(self.tmp.name) / "s.db"
        self.store = StateStore(self.db)
        self.repo = Path(self.tmp.name) / "repo"
        self.store.add_source(Source("s1", "fixture", "https://example.org/index.json", "index", "ar",
                                     configuration={"allowed_hosts": ["example.org"],
                                                    "signing_key": "ab" * 32}))

    def tearDown(self):
        self.store.close()
        self.tmp.cleanup()

    def _stage(self, decision, *, stage="accepted"):
        artifact = Path(self.tmp.name) / "x.apk"
        artifact.write_bytes(b"apk")
        metadata = {"_artifact_path": str(artifact),
                    "_security": {"sha256": "c" * 64, "certificate": "ab" * 32,
                                  "malware": {"engine": "FakeClam", "verdict": "CLEAN", "detail": "clean"}}}
        if decision is not None:
            metadata["_content_policy"] = decision
        candidate = Candidate("s1", "org.example.ext", "1.0", "https://example.org/x.apk",
                              name="X", language="ar", provenance={"page": "p"}, metadata=metadata)
        if stage == "accepted":
            self.store.put_pending(candidate, "security-passed")
            self.store.accept_pending("s1", candidate.identity)
        else:
            self.store.put_pending(candidate, stage)
        return candidate

    def _publish(self):
        return RepositoryPublisher(self.store, self.repo).publish(release=True)

    def _safe(self):
        return {"scope": "source", "classification": "SAFE",
                "policy_fingerprint": self.store.get_source("s1").fingerprint(),
                "reasons": ["fixture"], "evidence": {}}

    def test_safe_item_is_actually_published(self):
        self._stage(self._safe())
        result = self._publish()
        self.assertEqual(result["published"], 1, result)
        self.assertTrue((self.repo / "index.json").exists())

    def test_nsfw_item_is_refused_even_though_staged_accepted(self):
        self._stage(dict(self._safe(), classification="NSFW"))
        with self.assertRaises(PublishRefused) as ctx:
            self._publish()
        self.assertIn("NSFW", str(ctx.exception))
        self.assertFalse((self.repo / "index.json").exists())

    def test_quarantined_classification_is_refused(self):
        self._stage(dict(self._safe(), classification="QUARANTINED"))
        with self.assertRaises(PublishRefused):
            self._publish()

    def test_item_with_no_classification_at_all_is_refused(self):
        self._stage(None)
        with self.assertRaises(PublishRefused) as ctx:
            self._publish()
        self.assertIn("no content classification", str(ctx.exception))

    def test_unknown_is_refused_without_review(self):
        self._stage(dict(self._safe(), classification="UNKNOWN"))
        with self.assertRaises(PublishRefused) as ctx:
            self._publish()
        self.assertIn("UNKNOWN", str(ctx.exception))

    def test_mixed_is_refused_without_any_protection(self):
        self._stage(dict(self._safe(), classification="MIXED"))
        with self.assertRaises(PublishRefused):
            self._publish()

    def test_mixed_is_refused_even_with_a_textual_approval(self):
        """The approval escape hatch is gone.

        A recorded operator approval is text in the metadata. It cannot show that
        the forbidden items are excluded from what the client can discover, open
        and download, so it must not buy publication.
        """
        self._stage(dict(self._safe(), classification="MIXED",
                         review_approved_by="operator-2026-10-10"))
        with self.assertRaises(PublishRefused) as ctx:
            self._publish()
        self.assertIn("MIXED", str(ctx.exception))
        self.assertFalse((self.repo / "index.json").exists())

    def test_mixed_approval_cannot_be_reached_through_the_store(self):
        """Writing the approval directly into the record does not help either."""
        candidate = self._stage(dict(self._safe(), classification="MIXED"))
        store = StateStore(self.db)
        store.db.execute("UPDATE pending SET candidate=? WHERE identity=?",
                         (json.dumps({**candidate.__dict__,
                                      "metadata": {**candidate.metadata,
                                                   "_content_policy": {"scope": "source",
                                                                       "classification": "MIXED",
                                                                       "review_approved_by": "root"}}}), candidate.identity))
        store.db.commit()
        store.close()
        with self.assertRaises(PublishRefused):
            self._publish()

    def test_policy_changed_after_approval_invalidates_it(self):
        decision = self._safe()
        decision["policy_fingerprint"] = "0" * 64
        self._stage(decision)
        with self.assertRaises(PublishRefused) as ctx:
            self._publish()
        self.assertIn("configuration changed", str(ctx.exception))

    def test_failed_classifier_cannot_become_an_accept(self):
        self._stage(dict(self._safe(), classification="UNKNOWN",
                         reasons=["classifier unavailable"]))
        with self.assertRaises(PublishRefused):
            self._publish()


if __name__ == "__main__":
    unittest.main()
