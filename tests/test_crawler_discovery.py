"""Discovery -> trust setup -> crawl -> verify -> scan -> accept -> publish.

The extension pipeline is already covered from a *configured* source onward. This
file covers the part in front of it: a source nobody has heard of yet, found by
discovery, turned into something the crawler can actually trust, and carried all
the way to the published index.

Everything runs against local fixtures. No socket is opened, and no file under
``repo/`` is ever touched.
"""

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from shura_core.coordinator import CrawlCoordinator
from shura_core.crawler.arabic import (ArabicSiteDiscovery, SeedFileFinder, evaluate_site)
from shura_core.discovery.service import SourceDiscovery
from shura_core.models import Candidate, Source, SourceState
from shura_core.pipeline import CandidateProcessor
from shura_core.publishing.repository import RepositoryPublisher
from shura_core.security.artifacts import ScanVerdict
from shura_core.security.network import SafeHTTP
from shura_core.sources.registry import SourceConfigurationError, SourceRegistry
from shura_core.state import StateStore

SIGNING_KEY = "ab" * 32
APK = b"PK\x03\x04shura-fixture-apk-bytes"


class FakeResponse:
    def __init__(self, url, body):
        self._url, self._body, self._offset = url, body, 0
        self.headers = {"Content-Type": "application/json",
                        "Content-Length": str(len(body))}

    def geturl(self):
        return self._url

    def read(self, size=-1):
        if size is None or size < 0:
            size = len(self._body) - self._offset
        chunk = self._body[self._offset:self._offset + size]
        self._offset += len(chunk)
        return chunk

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


class GitHubFixture:
    """Serves GitHub search and releases, and refuses every other host."""

    def __init__(self, repos, releases=None, fail_queries=()):
        self.repos = repos
        self.releases = releases or {}
        #: Substrings of search URLs that must fail, to model a rate limit or blip.
        self.fail_queries = tuple(fail_queries)
        self.calls = []

    def _body(self, url):
        for needle in self.fail_queries:
            if needle in url:
                raise ConnectionError("simulated search failure")
        if "/search/repositories" in url:
            return json.dumps({"items": self.repos}).encode()
        if "/releases" in url:
            for needle, payload in self.releases.items():
                if needle in url:
                    return payload
            return json.dumps([]).encode()
        if url.endswith(".apk"):
            return APK
        raise AssertionError(f"unexpected url {url}")

    def opener(self, redirect_handler):
        # The ORIGINAL, captured before this class patched it in; reading
        # network.build_opener here would return this method and recurse.
        fixture = self

        def open_(req, **kw):
            fixture.calls.append(req.full_url)
            return FakeResponse(req.full_url, fixture._body(req.full_url))

        opener = self._original(redirect_handler)
        opener.open = open_
        return opener

    def __enter__(self):
        from shura_core.security import network
        self._original = network.build_opener
        network.build_opener = self.opener
        return self

    def __exit__(self, *exc):
        from shura_core.security import network
        network.build_opener = self._original
        return False


def repo(full, language="ar"):
    return {"full_name": full, "name": full.split("/")[-1],
            "html_url": f"https://github.com/{full}",
            "language": language}


class Case(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.store = StateStore(self.root / "state.db")

    def tearDown(self):
        self.store.close()
        self.tmp.cleanup()


class DiscoveryTests(Case):
    def test_a_proposal_is_never_enabled_or_crawled(self):
        fixture = GitHubFixture([repo("arab/reader"), repo("other/reader")])
        with fixture:
            http = SafeHTTP(["api.github.com"])
            found = SourceDiscovery(self.store, http, daily_limit=250).discover()
        self.assertEqual(len(found), 2)
        for source in found:
            self.assertFalse(source.enabled, "a search hit must never be enabled")
            self.assertIs(source.state, SourceState.PAUSED)
            self.assertTrue(source.configuration.get("review_required"))
            self.assertEqual(source.configuration["package"], "")
            self.assertEqual(source.configuration["signing_key"], "")

    def test_arabic_is_queried_before_other_languages(self):
        self.assertEqual(SourceDiscovery.QUERIES[0][0], "ar",
                         "Arabic must lead the discovery order")

    def test_a_known_source_is_not_re_added(self):
        fixture = GitHubFixture([repo("arab/reader")])
        with fixture:
            http = SafeHTTP(["api.github.com"])
            first = SourceDiscovery(self.store, http).discover()
            second = SourceDiscovery(self.store, http).discover()
        self.assertEqual(len(first), 1)
        self.assertEqual(second, [], "a known proposal must not be duplicated")

    def test_the_daily_limit_is_respected(self):
        fixture = GitHubFixture([repo(f"arab/r{i}") for i in range(40)])
        with fixture:
            http = SafeHTTP(["api.github.com"])
            found = SourceDiscovery(self.store, http, daily_limit=3).discover()
        self.assertLessEqual(len(found), 3)


class DiscoveryResilienceTests(Case):
    """One failing query must not throw away the proposals already gathered."""

    def test_a_failing_query_does_not_lose_earlier_results(self):
        fixture = GitHubFixture([repo("arab/reader")],
                                fail_queries=("manhwa",))
        with fixture:
            http = SafeHTTP(["api.github.com"])
            finder = SourceDiscovery(self.store, http)
            found = finder.discover()
        self.assertGreaterEqual(len(found), 1,
                                "proposals from the queries that worked must survive")
        self.assertTrue(finder.errors, "the failing query must be recorded, not swallowed")
        self.assertIn("simulated search failure", finder.errors[0]["error"])

    def test_the_failure_is_written_to_the_event_ledger(self):
        fixture = GitHubFixture([repo("arab/reader")], fail_queries=("manhwa",))
        with fixture:
            finder = SourceDiscovery(self.store, SafeHTTP(["api.github.com"]))
            finder.discover()
        events = self.store.db.execute(
            "SELECT event FROM history WHERE event='discovery_query_failed'").fetchall()
        self.assertTrue(events, "a partial discovery must leave an auditable trace")

    def test_every_query_failing_is_reported_not_raised(self):
        fixture = GitHubFixture([repo("arab/reader")],
                                fail_queries=("q=", "topic%3Amanga",))
        with fixture:
            finder = SourceDiscovery(self.store, SafeHTTP(["api.github.com"]))
            found = finder.discover()
        self.assertEqual(found, [])
        self.assertTrue(finder.errors)


class TrustSetupTests(Case):
    """The step between 'found' and 'crawled' - where a proposal becomes a source."""

    def proposal(self):
        return Source(source_id="github:arab/reader", name="reader",
                      url="https://api.github.com/repos/arab/reader/releases",
                      kind="github-releases", language="ar", enabled=False,
                      state=SourceState.PAUSED,
                      configuration={"allowed_hosts": ["api.github.com"], "package": "",
                                     "signing_key": "", "review_required": True})

    def configured(self, **over):
        source = self.proposal()
        source.enabled = True
        source.state = SourceState.ACTIVE
        source.configuration = {"allowed_hosts": ["api.github.com"],
                                "package": "org.example.ext", "signing_key": SIGNING_KEY,
                                "review_required": True}
        for key, value in over.items():
            source.configuration[key] = value
        return source

    def test_a_discovered_proposal_cannot_be_crawled_as_is(self):
        self.store.add_source(self.proposal())
        source = self.store.get_source("github:arab/reader")
        self.assertFalse(self.store.eligible(source),
                         "a proposal with no trust configuration is not crawlable")

    def test_an_enabled_source_without_a_signing_key_is_refused(self):
        with self.assertRaises(SourceConfigurationError):
            SourceRegistry.validate(self.configured(signing_key=""))

    def test_an_enabled_github_source_without_a_package_is_refused(self):
        with self.assertRaises(SourceConfigurationError):
            SourceRegistry.validate(self.configured(package=""))

    def test_a_malformed_signing_key_is_refused(self):
        for bad in ("not-a-fingerprint", "ab" * 31, "zz" * 32):
            with self.assertRaises(SourceConfigurationError, msg=bad):
                SourceRegistry.validate(self.configured(signing_key=bad))

    def test_a_fully_configured_source_validates_and_is_eligible(self):
        source = SourceRegistry.validate(self.configured())
        self.store.add_source(source)
        self.assertTrue(self.store.eligible(self.store.get_source(source.source_id)))

    def test_configuring_a_proposal_does_not_silently_unpause_it(self):
        self.store.add_source(self.proposal())
        configured = self.configured()
        self.store.add_source(configured)          # update_state defaults to False
        reloaded = self.store.get_source(configured.source_id)
        self.assertIs(reloaded.state, SourceState.PAUSED,
                      "a reconfigured proposal stays paused until an operator accepts it")
        self.assertFalse(self.store.eligible(reloaded))


class AcceptanceTests(Case):
    """accept-source is a trust decision, not a rubber stamp.

    A proposal found by discovery carries no package and no signing key by
    construction. Accepting it would put it in the same state a configured,
    reviewed source occupies, so acceptance is refused until the trust material
    is present. Crawl enablement stays a separate switch.
    """

    def setUp(self):
        super().setUp()
        self.source = Source(source_id="github:arab/reader", name="reader",
                             url="https://api.github.com/repos/arab/reader/releases",
                             kind="github-releases", language="ar", enabled=False,
                             state=SourceState.PAUSED,
                             configuration={"allowed_hosts": ["api.github.com"],
                                            "package": "", "signing_key": "",
                                            "review_required": True})
        self.store.add_source(self.source)

    def complete(self, **over):
        source = self.store.get_source("github:arab/reader")
        source.configuration.update({"package": "org.example.ext",
                                     "signing_key": SIGNING_KEY})
        source.configuration.update(over)
        self.store.add_source(source)
        return source

    def test_an_incomplete_proposal_cannot_be_accepted(self):
        with self.assertRaises(ValueError) as caught:
            self.store.accept_source("github:arab/reader", reason="looks promising")
        message = str(caught.exception)
        self.assertIn("signing_key", message)
        self.assertIn("package", message)
        source = self.store.get_source("github:arab/reader")
        self.assertIsNot(source.state, SourceState.ACCEPTED,
                         "a source with no trust material must not reach the accepted state")
        self.assertFalse(self.store.eligible(source))

    def test_the_refusal_is_recorded_with_what_was_missing(self):
        with self.assertRaises(ValueError):
            self.store.accept_source("github:arab/reader")
        row = self.store.db.execute(
            "SELECT detail FROM history WHERE event='source_acceptance_refused'").fetchone()
        self.assertIsNotNone(row, "a refused acceptance must leave an auditable trace")
        detail = json.loads(row[0])
        self.assertTrue(any("signing_key" in m for m in detail["missing"]))

    def test_a_proposal_is_accepted_once_its_trust_material_is_complete(self):
        self.complete()
        self.store.accept_source("github:arab/reader", reason="signing key verified")
        source = self.store.get_source("github:arab/reader")
        self.assertIs(source.state, SourceState.ACCEPTED)
        self.assertFalse(self.store.eligible(source),
                         "acceptance is trust, not crawl enablement: enabled is still false")

    def test_acceptance_and_enablement_remain_separate_switches(self):
        self.complete()
        self.store.accept_source("github:arab/reader")
        # accepted, but still not crawlable until the operator enables it
        self.assertFalse(self.store.eligible(self.store.get_source("github:arab/reader")))
        source = self.store.get_source("github:arab/reader")
        source.enabled = True
        self.store.add_source(source, update_state=True)
        self.assertTrue(self.store.eligible(self.store.get_source("github:arab/reader")))

    def test_acceptance_does_not_lift_a_paused_source_by_itself(self):
        self.complete()
        self.store.accept_source("github:arab/reader")
        self.assertFalse(self.store.eligible(self.store.get_source("github:arab/reader")))

    def test_a_quarantined_source_is_still_refused(self):
        self.complete()
        candidate = Candidate("github:arab/reader", "org.example.ext", "1",
                              "https://api.github.com/x.apk", provenance={"page": "p"})
        self.store.quarantine_item(candidate, "signature mismatch")
        with self.assertRaises(ValueError):
            self.store.accept_source("github:arab/reader")


if __name__ == "__main__":
    unittest.main()

ARABIC_PAGE = """<!doctype html>
<html lang="ar" dir="rtl"><head><title>مانجا ليك</title></head>
<body><nav><a href="/manga/">فهرس المانجا</a></nav>
<ul class="manga-list">
 <li><a href="/manga/the-overly/">The Overly</a></li>
</ul>
<p><a href="/manga/the-overly/chapter-1/">فصل 1</a></p>
</body></html>"""

LATIN_PAGE = """<!doctype html>
<html lang="en"><head><title>Reader</title></head>
<body><a href="/manga/">Catalog</a></body></html>"""

# Arabic language signals (lang/dir) but deliberately no work index and no
# chapter listing: public and Arabic, yet nothing verifiable.
THIN_PAGE = """<!doctype html>
<html lang="ar" dir="rtl"><head><title>قارئ</title></head>
<body><p>لا يوجد شيء بعد.</p></body></html>"""


class SiteFixture:
    """Serves candidate pages by host. Opens no socket."""

    def __init__(self, pages, blocked=()):
        self.pages = pages
        self.blocked = set(blocked)
        self.requested = []

    def opener(self, redirect_handler):
        fixture = self
        original = self._original

        def open_(req, **kw):
            from urllib.error import HTTPError
            url = req.full_url
            fixture.requested.append(url)
            host = url.split("/")[2]
            if host in fixture.blocked:
                raise HTTPError(url, 403, "Forbidden", {}, None)
            body = fixture.pages.get(host, "").encode()
            return FakeResponse(url, body)

        opener = original(redirect_handler)
        opener.open = open_
        return opener

    def __enter__(self):
        from shura_core.security import network
        self._original = network.build_opener
        network.build_opener = self.opener
        return self

    def __exit__(self, *exc):
        from shura_core.security import network
        network.build_opener = self._original
        return False


def seed_file(path, urls):
    path.write_text(json.dumps({"sites": [{"url": u, "language": "ar"} for u in urls]}),
                    encoding="utf-8")


class ArabicSiteDiscoveryTests(Case):
    """An Arabic site that can be proven becomes a disabled, paused proposal."""

    def finder(self, urls):
        seed_file(self.root / "seeds.json", urls)
        return SeedFileFinder(self.root / "seeds.json")

    def test_a_verifiable_arabic_site_becomes_a_disabled_paused_proposal(self):
        with SiteFixture({"arab.example": ARABIC_PAGE}):
            finder = self.finder(["https://arab.example/"])
            proposals = ArabicSiteDiscovery(self.store, SafeHTTP(["arab.example"])).discover([finder])
        self.assertEqual(len(proposals), 1)
        self.assertEqual(proposals[0]["verdict"], "CANDIDATE")
        self.assertGreater(proposals[0]["score"], 0)
        source = self.store.get_source("site:arab.example")
        self.assertIsNotNone(source, "the proposal must land in the existing sources ledger")
        self.assertFalse(source.enabled, "a discovered site is never enabled by discovery")
        self.assertIs(source.state, SourceState.PAUSED)
        self.assertTrue(source.configuration["review_required"])
        self.assertEqual(source.configuration["signing_key"], "",
                         "trust material stays empty until an operator supplies it")
        self.assertFalse(self.store.eligible(source),
                         "a proposal must be impossible to crawl")

    def test_a_proposal_cannot_be_accepted_without_trust_material(self):
        with SiteFixture({"arab.example": ARABIC_PAGE}):
            ArabicSiteDiscovery(self.store, SafeHTTP(["arab.example"])).discover(
                [self.finder(["https://arab.example/"])])
        with self.assertRaises(ValueError):
            self.store.accept_source("site:arab.example")

    def test_a_non_arabic_site_is_rejected_with_a_reason(self):
        with SiteFixture({"latin.example": LATIN_PAGE}):
            proposals = ArabicSiteDiscovery(self.store, SafeHTTP(["latin.example"])).discover(
                [self.finder(["https://latin.example/"])])
        self.assertEqual(proposals, [])
        detail = self._last_event("site_candidate")
        self.assertEqual(detail["verdict"], "REJECTED")
        self.assertTrue(any("Arabic" in r for r in detail["reasons"]))

    def test_a_site_that_refuses_public_access_is_rejected_not_worked_around(self):
        with SiteFixture({"walled.example": ARABIC_PAGE}, blocked={"walled.example"}):
            proposals = ArabicSiteDiscovery(self.store, SafeHTTP(["walled.example"])).discover(
                [self.finder(["https://walled.example/"])])
        self.assertEqual(proposals, [])
        detail = self._last_event("site_candidate")
        self.assertEqual(detail["verdict"], "REJECTED")
        self.assertTrue(detail["blocked"], "a 403 is recorded as a refusal, not a puzzle")

    def test_a_site_with_no_verifiable_index_is_deferred_not_accepted(self):
        with SiteFixture({"thin.example": THIN_PAGE}):
            ArabicSiteDiscovery(self.store, SafeHTTP(["thin.example"])).discover(
                [self.finder(["https://thin.example/"])])
        detail = self._last_event("site_candidate")
        self.assertEqual(detail["verdict"], "DEFERRED")
        self.assertIsNone(self.store.get_source("site:thin.example"),
                          "a deferred candidate is not proposed as a source")

    def test_repeating_a_pass_adds_no_duplicates(self):
        with SiteFixture({"arab.example": ARABIC_PAGE}):
            finder = self.finder(["https://arab.example/"])
            http = SafeHTTP(["arab.example"])
            discovery = ArabicSiteDiscovery(self.store, http)
            first = discovery.discover([finder])
            second = discovery.discover([finder])
        self.assertEqual(len(first), 1)
        self.assertEqual(second, [], "a repeat pass must not re-propose a known site")
        count = self.store.db.execute(
            "SELECT COUNT(*) FROM sources WHERE source_id='site:arab.example'").fetchone()[0]
        self.assertEqual(count, 1)

    def test_one_broken_finder_does_not_cost_the_pass(self):
        class ExplodingFinder:
            def find(self, limit=250):
                raise RuntimeError("finder is down")

        with SiteFixture({"arab.example": ARABIC_PAGE}):
            discovery = ArabicSiteDiscovery(self.store, SafeHTTP(["arab.example"]))
            proposals = discovery.discover([ExplodingFinder(), self.finder(["https://arab.example/"])])
        self.assertEqual(len(proposals), 1, "the good finder still produced its proposal")
        self.assertTrue(discovery.errors, "the broken finder is recorded, not swallowed")

    def test_the_daily_limit_is_respected(self):
        hosts = {f"s{i}.example": ARABIC_PAGE for i in range(20)}
        allowed = [f"s{i}.example" for i in range(20)]
        with SiteFixture(hosts):
            finder = self.finder([f"https://{h}/" for h in allowed])
            discovery = ArabicSiteDiscovery(self.store, SafeHTTP(allowed), daily_limit=3)
            proposals = discovery.discover([finder])
        self.assertLessEqual(len(proposals), 3)

    def test_a_plain_http_candidate_is_never_recorded(self):
        with SiteFixture({}):
            proposals = ArabicSiteDiscovery(self.store, SafeHTTP([])).discover(
                [self.finder(["http://insecure.example/"])])
        self.assertEqual(proposals, [])
        self.assertIsNone(self.store.get_source("site:insecure.example"))

    def test_nothing_is_published_or_written_to_the_repository(self):
        repo = Path(__file__).resolve().parents[1] / "repo"
        before = {p: p.stat().st_mtime for p in repo.rglob("*") if p.is_file()}
        with SiteFixture({"arab.example": ARABIC_PAGE}):
            ArabicSiteDiscovery(self.store, SafeHTTP(["arab.example"])).discover(
                [self.finder(["https://arab.example/"])])
        after = {p: p.stat().st_mtime for p in repo.rglob("*") if p.is_file()}
        self.assertEqual(before, after, "discovery must never touch the published repository")

    def _last_event(self, event):
        row = self.store.db.execute(
            "SELECT detail FROM history WHERE event=? ORDER BY id DESC LIMIT 1",
            (event,)).fetchone()
        self.assertIsNotNone(row, f"expected a {event} event")
        return json.loads(row[0])


class ArabicSiteDiscoveryCommandTests(Case):
    """The CLI reports proposals without ever enabling one."""

    def run_cli(self, argv):
        import contextlib, io
        from shura_core import cli
        out = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(out):
            try:
                code = cli.main(argv)
            except SystemExit as exc:
                code = int(exc.code or 0)
        return code, out.getvalue()

    @staticmethod
    def last_json(text):
        for line in reversed([l for l in text.splitlines() if l.strip()]):
            try:
                return json.loads(line)
            except json.JSONDecodeError:
                continue
        raise AssertionError(f"no JSON line in output: {text!r}")

    def test_the_command_proposes_only_and_reports_its_candidates(self):
        seed_file(self.root / "seeds.json", ["https://arab.example/"])
        with SiteFixture({"arab.example": ARABIC_PAGE}):
            # No --db: discover-sites must never open the operator's database.
            code, out = self.run_cli(["discover-sites", "--seeds",
                                      str(self.root / "seeds.json")])
        self.assertEqual(code, 0)
        report = self.last_json(out)
        self.assertEqual(report["candidates"], 1)
        self.assertEqual(len(report["proposed"]), 1)
        self.assertEqual(report["proposed"][0]["verdict"], "CANDIDATE")
        self.assertIsNone(self.store.get_source("site:arab.example"),
                          "the CLI only reports; persisting a proposal is the service's job")

    def test_the_reporting_command_never_opens_the_operator_database(self):
        before = Path.cwd() / "shura.db"
        existed = before.exists()
        seed_file(self.root / "seeds.json", ["https://arab.example/"])
        with SiteFixture({"arab.example": ARABIC_PAGE}):
            code, _ = self.run_cli(["discover-sites", "--seeds",
                                    str(self.root / "seeds.json")])
        self.assertEqual(code, 0)
        self.assertEqual(before.exists(), existed,
                         "discover-sites must not create or open the operator's shura.db")

    def test_the_command_survives_an_empty_seed_file(self):
        seed_file(self.root / "seeds.json", [])
        code, out = self.run_cli(["discover-sites", "--seeds",
                                  str(self.root / "seeds.json")])
        self.assertEqual(code, 0)
        self.assertEqual(self.last_json(out)["proposed"], [])
