import json
import sqlite3
import unittest
from pathlib import Path

from shura_core.agent import AgentLoop, Attempt, Cause, Knowledge, classify, classify_build
from shura_core.agent.loop import SiteResult
from shura_core.content.htmlsite import SiteDescriptor
from shura_core.models import Source
from shura_core.state import StateStore

REAL_SITE = "https://www.asurascans.com"
REAL_KEY = "9f02ca8a8946e77b0cd768b085cafa0037ffd598d5772f08690f862683118a18"


def _descriptor(**over):
    base = dict(source_id="site:example.test", name="Example", language="ar",
                catalogue_url="https://example.test/series", host="example.test")
    base.update(over)
    return SiteDescriptor(**base)


class ClassifyTests(unittest.TestCase):
    """A refusal must be categorised, because the category decides the retry."""

    def test_no_images_is_the_site_fault_and_is_not_retried(self):
        cause, gate = classify(["a chapter page was read and served no reading images"])
        self.assertEqual(cause, Cause.JAVASCRIPT_RENDERED)
        self.assertEqual(gate, "converter:page_images_observed")

    def test_auth_is_never_treated_as_a_tunable_gate(self):
        cause, _ = classify(["site refused public access (401/403)"])
        self.assertEqual(cause, Cause.AUTH_WALLED)

    def test_an_unparseable_page_prefix_is_our_extractor(self):
        cause, gate = classify(["no page path prefix could be established for this site"])
        self.assertEqual(cause, Cause.EXTRACTOR_LIMITED)
        self.assertIn("derive_page_prefix", gate)

    def test_the_most_severe_cause_wins_over_the_first_one(self):
        # A plan carrying both is a site with no readable pages. Reporting the
        # listing problem would send the agent back to a listing it cannot read.
        cause, _ = classify(["no entry link was observed on the index",
                             "a chapter page was read and served no reading images"])
        self.assertEqual(cause, Cause.JAVASCRIPT_RENDERED)

    def test_an_unknown_blocker_is_reported_not_guessed(self):
        cause, gate = classify(["something nobody has ever seen before"])
        self.assertEqual(cause, "unknown")
        self.assertIn("something nobody", gate)

    def test_an_empty_gate_list_stays_retryable(self):
        cause, _ = classify([])
        self.assertEqual(cause, Cause.INSUFFICIENT_EVIDENCE)

    def test_an_empty_gradle_cache_is_not_a_compile_error(self):
        cause, gate = classify_build("gradle failed: no cached version available for offline mode")
        self.assertEqual(cause, Cause.BUILD_FAILED)
        self.assertEqual(gate, "builder:gradle_cache_empty")


class KnowledgeTests(unittest.TestCase):
    def setUp(self):
        self.k = Knowledge(sqlite3.connect(":memory:", isolation_level=None))

    def test_an_unseen_site_is_always_worth_probing(self):
        self.assertEqual(self.k.is_worth_retrying("site:none.test"), (True, ""))

    def test_a_site_already_published_is_not_probed_again(self):
        self.k.record(Attempt(source_id="site:a.test", stage="registered",
                              outcome="published", cause=Cause.OK))
        retry, reason = self.k.is_worth_retrying("site:a.test")
        self.assertFalse(retry)
        self.assertIn("already published", reason)

    def test_a_javascript_site_is_never_probed_again(self):
        self.k.record(Attempt(source_id="site:js.test", stage="convert",
                              outcome="refused", cause=Cause.JAVASCRIPT_RENDERED))
        retry, reason = self.k.is_worth_retrying("site:js.test")
        self.assertFalse(retry)
        self.assertIn("javascript_rendered", reason)

    def test_an_extractor_limited_site_is_retried_once_the_extractor_changes(self):
        # Refusing to look again would make the agent unable to benefit from its
        # own improvements, which is the whole point of recording this.
        self.k.record(Attempt(source_id="site:e.test", stage="convert",
                              outcome="refused", cause=Cause.EXTRACTOR_LIMITED))
        self.assertTrue(self.k.is_worth_retrying("site:e.test")[0])

    def test_history_is_capped_so_one_site_cannot_own_the_table(self):
        for i in range(40):
            self.k.record(Attempt(source_id="site:many.test", stage="convert",
                                  outcome="refused", cause=Cause.EXTRACTOR_LIMITED,
                                  blockers=[f"attempt {i}"]))
        self.assertLessEqual(len(self.k.attempts("site:many.test", limit=100)), 20)

    def test_the_newest_attempt_is_the_one_remembered(self):
        self.k.record(Attempt(source_id="site:t.test", stage="convert",
                              outcome="refused", cause=Cause.EXTRACTOR_LIMITED))
        self.k.record(Attempt(source_id="site:t.test", stage="convert",
                              outcome="refused", cause=Cause.INSUFFICIENT_EVIDENCE))
        self.assertEqual(self.k.last("site:t.test").cause, Cause.INSUFFICIENT_EVIDENCE)

    def test_blame_points_the_dominant_cause_at_a_module(self):
        self.k.record(Attempt(source_id="site:x.test", stage="convert",
                              outcome="refused", cause=Cause.EXTRACTOR_LIMITED))
        blamed = self.k.blame()
        self.assertEqual(blamed[0]["cause"], Cause.EXTRACTOR_LIMITED)
        self.assertIn("htmlsite", blamed[0]["gate"])


class AgentLoopTests(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(self.enterContext(__import__("tempfile").TemporaryDirectory()))
        self.db = self.tmp / "state.db"
        self.store = StateStore(str(self.db))
        self.sources_file = self.tmp / "sources.json"
        self.sources_file.write_text(json.dumps([
            {"source_id": "site:example.test", "name": "Example",
             "url": "https://example.test/series", "kind": "local-artifact",
             "language": "ar", "region": "ar", "enabled": True,
             "configuration": {"allowed_hosts": ["example.test"],
                               "package": "app.shura.extension.example", "signing_key": REAL_KEY}},
        ]), encoding="utf-8")

    def _loop(self, **over):
        kw = dict(sources_path=self.sources_file, android_root=self.tmp / "android",
                  evidence_only=True, build=False)
        kw.update(over)
        return AgentLoop(self.store, **kw)

    def _source(self):
        return Source("site:example.test", "Example", "https://example.test/series",
                      "local-artifact", "ar", region="ar", enabled=True,
                      configuration={"allowed_hosts": ["example.test"],
                                     "package": "app.shura.extension.example",
                                     "signing_key": REAL_KEY})

    def test_a_source_without_a_trust_list_is_refused_before_any_request(self):
        source = Source("site:no.test", "No", "https://no.test/", "local-artifact",
                        "ar", configuration={})
        result = self._loop().run_site(source)
        self.assertEqual(result.outcome, "refused")
        self.assertEqual(result.cause, Cause.SITE_REFUSED)

    def test_a_learned_site_is_skipped_instead_of_reprobed(self):
        self.store  # the loop reads and writes the same store
        Knowledge(self.store).record(Attempt(source_id="site:example.test",
                                             stage="convert", outcome="refused",
                                             cause=Cause.JAVASCRIPT_RENDERED))
        result = self._loop().run_site(self._source())
        self.assertEqual(result.outcome, "skipped")
        self.assertEqual(result.cause, "known_unconvertible")

    def test_every_run_is_recorded_even_when_it_refuses(self):
        # The hole this package exists to close: blockers used to reach stdout
        # and then nowhere at all.
        result = self._loop().run_site(self._source())
        remembered = Knowledge(self.store).last(result.source_id)
        self.assertIsNotNone(remembered)
        self.assertEqual(remembered.outcome, result.outcome)

    def test_the_report_attributes_each_refusal_to_a_gate(self):
        report = self._loop().run([self._source()])
        self.assertIn("learning", report)
        for entry in report["learning"]:
            self.assertTrue(entry["cause"])
            self.assertTrue(entry["gate"])


if __name__ == "__main__":
    unittest.main()