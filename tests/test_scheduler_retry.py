"""Scheduler retry/backoff must be bounded and must not be reset by side channels.

The failure ladder lives in ``StateStore.record_attempt``: it counts
``crawl_failure`` events recorded after the most recent ``crawl_success``, and
moves a source to DEAD at ten. ``Scheduler.recheck_due_chapters`` used to report
a healed chapter through ``record_attempt(True)``, which emits a
``crawl_success`` the source never produced. Every healed chapter therefore reset
the source's own failure window and set it back to ACTIVE, skipping its backoff.
A source that never crawled successfully would be polled on every pass, forever,
and would never reach DEAD.

These tests pin the ladder itself and the backoff schedule, and check that a
failing source cannot crowd out healthy ones.
"""
import unittest
from datetime import datetime, timedelta, timezone

from shura_core.models import ChapterRecord, ChapterStatus, Source, SourceState, WorkReview
from shura_core.scheduling import Scheduler
from shura_core.state import StateStore

CERT = "a" * 64


class Base(unittest.TestCase):
    def setUp(self):
        import tempfile
        from pathlib import Path
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.store = StateStore(self.root / "s.db")
        self.addCleanup(self.store.close)
        self.addCleanup(self.tmp.cleanup)

    def source(self, sid="s1"):
        self.store.add_source(Source(sid, f"fx-{sid}", "https://example.org/i.json",
                                     "index", "ar",
                                     configuration={"allowed_hosts": ["example.org"],
                                                   "signing_key": CERT}))
        return self.store.get_source(sid)


class FailureLadderTests(Base):
    def test_ten_consecutive_failures_reach_dead(self):
        self.source()
        for _ in range(9):
            self.store.record_attempt("s1", False, "boom")
            self.assertNotEqual(self.store.get_source("s1").state, SourceState.DEAD)
        self.store.record_attempt("s1", False, "boom")
        self.assertEqual(self.store.get_source("s1").state, SourceState.DEAD)
        self.assertIsNone(self.store.get_source("s1").next_retry)

    def test_a_success_resets_the_ladder(self):
        self.source()
        for _ in range(9):
            self.store.record_attempt("s1", False, "boom")
        self.store.record_attempt("s1", True, "ok")
        self.assertEqual(self.store.get_source("s1").state, SourceState.ACTIVE)
        for _ in range(9):
            self.store.record_attempt("s1", False, "boom")
        self.assertNotEqual(self.store.get_source("s1").state, SourceState.DEAD,
                            "failures before a success must not count toward DEAD")

    def test_backoff_grows_from_six_hours_to_one_day(self):
        self.source()
        self.store.record_attempt("s1", False, "boom")
        first = datetime.fromisoformat(self.store.get_source("s1").next_retry)
        self.assertTrue(5.5 <= (first - datetime.now(timezone.utc)).total_seconds() / 3600 <= 6.5)
        for _ in range(3):  # 4th failure crosses the >=3 threshold
            self.store.record_attempt("s1", False, "boom")
        later = datetime.fromisoformat(self.store.get_source("s1").next_retry)
        self.assertGreater(later - first, timedelta(hours=12),
                           "later failures must back off further, not retry sooner")

    def test_backoff_actually_blocks_eligibility(self):
        self.source()
        self.store.record_attempt("s1", False, "boom")
        self.assertFalse(self.store.eligible(self.store.get_source("s1")))
        future = datetime.now(timezone.utc) + timedelta(days=2)
        self.assertTrue(self.store.eligible(self.store.get_source("s1"), at=future))


class ChapterHealMustNotResetTheLadderTests(Base):
    def _healing_rechecker(self):
        class Healing:
            def recheck(self, chapter_record, policy):
                return ChapterRecord(chapter_record["work_id"],
                                     chapter_record["chapter_id"],
                                     ChapterStatus.HEALTHY, "healed", ["p"], 1)
        return Healing()

    def _seed_retryable_chapter(self):
        self.store.record_chapters("s1", WorkReview(
            "w1", "W1", "PENDING", "", {},
            [ChapterRecord("w1", "c1", ChapterStatus.TEMPORARY_FAILURE, "boom", [], 0)]))

    def _heal_once(self):
        return Scheduler(self.store).recheck_due_chapters(self._healing_rechecker())

    def test_a_healed_chapter_does_not_record_a_crawl_success(self):
        self.source()
        self._seed_retryable_chapter()
        self._heal_once()
        self.assertIsNone(self.store.get_source("s1").last_success,
                          "a chapter heal is not a successful crawl")
        counts = self.store.status("s1")["counts"]
        self.assertEqual(counts.get("crawl_success", 0), 0)
        self.assertEqual(counts.get("chapter_recheck_healed"), 1)

    def test_chronic_failure_still_reaches_dead_despite_heals(self):
        """The regression: endless healing used to reset the ladder forever.

        A chapter that heals and then breaks again puts the source in this loop
        forever, so the heal has to be re-armed each pass to reproduce it.
        """
        self.source()
        self._seed_retryable_chapter()
        for _ in range(12):
            self.store.record_attempt("s1", False, "boom")
            self._seed_retryable_chapter()   # the chapter broke again
            self._heal_once()
            if self.store.get_source("s1").state == SourceState.DEAD:
                break
        self.assertEqual(self.store.get_source("s1").state, SourceState.DEAD,
                         "a source that never crawls successfully must still be retired")

    def test_flapping_chapters_never_accumulate_attempts(self):
        self.source()
        self._seed_retryable_chapter()
        for _ in range(6):
            self.store.record_attempt("s1", False, "boom")
            self._seed_retryable_chapter()
            self._heal_once()
        self.assertEqual(self.store.get_source("s1").attempt_count, 6,
                         "one attempt per crawl, none invented by content recheck")

    def test_a_healed_chapter_does_not_wake_a_backing_off_source(self):
        self.source()
        self.store.record_attempt("s1", False, "boom")
        self.assertEqual(self.store.get_source("s1").state, SourceState.RETRY_LATER)
        self._seed_retryable_chapter()
        self._heal_once()
        self.assertEqual(self.store.get_source("s1").state, SourceState.RETRY_LATER,
                         "content health must not override crawl backoff")
        self.assertFalse(self.store.eligible(self.store.get_source("s1")))

    def test_attempt_count_reflects_crawls_only(self):
        self.source()
        self._seed_retryable_chapter()
        self.store.record_attempt("s1", False, "boom")
        self._heal_once()
        self.assertEqual(self.store.get_source("s1").attempt_count, 1)


class OneFailingSourceDoesNotBlockOthersTests(Base):
    def test_due_returns_healthy_sources_while_another_is_backing_off(self):
        good, bad = self.source("good"), self.source("bad")
        self.store.record_attempt("bad", False, "boom")
        due = {s.source_id for s in Scheduler(self.store).due()}
        self.assertIn("good", due)
        self.assertNotIn("bad", due)

    def test_a_dead_source_is_never_due(self):
        self.source("dead")
        for _ in range(10):
            self.store.record_attempt("dead", False, "boom")
        self.assertEqual(self.store.get_source("dead").state, SourceState.DEAD)
        self.assertNotIn("dead", {s.source_id for s in Scheduler(self.store).due()})

    def test_paused_and_quarantined_sources_are_never_due(self):
        self.source("p")
        self.source("q")
        self.store.transition("p", SourceState.PAUSED, "operator")
        from shura_core.models import Candidate
        self.store.quarantine_item(
            Candidate("q", "org.example.ext", "1.0", "https://example.org/a.apk",
                      name="x", language="ar", provenance={"page": "p"}), "bad")
        due = {s.source_id for s in Scheduler(self.store).due()}
        self.assertEqual(due, set())


class ManualRetryTests(Base):
    def test_dead_source_requires_an_explicit_resume(self):
        self.source()
        for _ in range(10):
            self.store.record_attempt("s1", False, "boom")
        with self.assertRaises(ValueError) as ctx:
            Scheduler(self.store).retry("s1")
        self.assertIn("--resume-dead", str(ctx.exception))
        self.assertEqual(self.store.get_source("s1").state, SourceState.DEAD)

    def test_resume_dead_requires_the_flag_and_restores_the_source(self):
        self.source()
        for _ in range(10):
            self.store.record_attempt("s1", False, "boom")
        Scheduler(self.store).retry("s1", resume_dead=True)
        self.assertEqual(self.store.get_source("s1").state, SourceState.ACTIVE)

    def test_quarantine_cannot_be_bypassed_by_retry(self):
        from shura_core.models import Candidate
        self.source("q")
        self.store.quarantine_item(
            Candidate("q", "org.example.ext", "1.0", "https://example.org/a.apk",
                      name="x", language="ar", provenance={"page": "p"}), "bad")
        for resume in (False, True):
            with self.assertRaises(ValueError):
                Scheduler(self.store).retry("q", resume_dead=resume)

    def test_manual_retry_on_backoff_makes_the_source_eligible_again(self):
        self.source()
        self.store.record_attempt("s1", False, "boom")
        self.assertFalse(self.store.eligible(self.store.get_source("s1")))
        Scheduler(self.store).retry("s1")
        self.assertEqual(self.store.get_source("s1").state, SourceState.ACTIVE)
        self.assertTrue(self.store.eligible(self.store.get_source("s1")))


if __name__ == "__main__":
    unittest.main()