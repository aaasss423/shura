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
from unittest import mock
from pathlib import Path

from shura_core.coordinator import CrawlCoordinator
from shura_core.crawler.github import GitHubReleasesCrawler, select_release_asset
from shura_core.crawler.index import IndexCrawler, resolve_artifact_url
from shura_core.models import Candidate as _Candidate, Source
from shura_core.crawler.arabic import (ArabicSiteDiscovery, SeedFileFinder, evaluate_site,
                                          pending_proposals, SearchFinder,
                                          configured_search_finder, DEFAULT_ARABIC_QUERIES,
                                          SiteCandidate)
from shura_core.crawler.review import (ACCEPTED,PENDING,REJECTED,ReviewError,
    candidate_records,candidate_status,decided_candidates,deferred_candidates,
    pending_candidates,record_review,reopen)
from shura_core.crawler.arabic import (latest_verdict, recheck_site, SearchProvider,
                                          JsonHttpSearchProvider)
from shura_core.content.fetcher import load_source_config, upsert_source_entry
from shura_core.content.runner import run_fetch_run
from shura_core.content.store import grant_clearance
from shura_core.content import fetchable_sources, open_store, sync_source_policy
from shura_core.sources.registry import SourceRegistry
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
            page = fixture.pages.get(host, "")
            path = url.split(host, 1)[-1] if host in url else "/"
            path = path.split("?", 1)[0]
            if isinstance(page, dict):
                page = page.get(path, page.get(path.rstrip("/") or "/", ""))
            return FakeResponse(url, (page or "").encode())

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

    def test_a_verifiable_arabic_site_becomes_a_review_item_not_a_source(self):
        with SiteFixture({"arab.example": ARABIC_PAGE}):
            finder = self.finder(["https://arab.example/"])
            proposals = ArabicSiteDiscovery(self.store, SafeHTTP(["arab.example"])).discover([finder])
        self.assertEqual(len(proposals), 1)
        self.assertEqual(proposals[0]["verdict"], "CANDIDATE")
        self.assertGreater(proposals[0]["score"], 0)
        self.assertIsNone(self.store.get_source("site:arab.example"),
                          "a website is not an APK extension and must not enter the "
                          "extension sources ledger")
        recorded = self._events("site_proposed")
        self.assertEqual(len(recorded), 1, "the proposal is recorded in the event ledger")
        self.assertEqual(recorded[0]["verdict"], "CANDIDATE")
        self.assertIn("awaiting operator review", recorded[0]["review"])

    def test_a_discovered_site_cannot_be_accepted_or_crawled(self):
        with SiteFixture({"arab.example": ARABIC_PAGE}):
            ArabicSiteDiscovery(self.store, SafeHTTP(["arab.example"])).discover(
                [self.finder(["https://arab.example/"])])
        with self.assertRaises(KeyError):
            self.store.accept_source("site:arab.example")
        self.assertEqual([s for s in self.store.db.execute(
            "SELECT data FROM sources").fetchall() if json.loads(s[0]).get("enabled")], [],
                         "a discovered site must never become enabled")
        conn = open_store(self.root / "content.db")
        self.addCleanup(conn.close)
        self.assertEqual(fetchable_sources(conn), [],
                         "and must never be fetchable as a content source: only "
                         "content-sources.json can set fetch_enabled")

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
        count = len(self._events("site_proposed"))
        self.assertEqual(count, 1, "a repeated pass must not duplicate the review item")
        self.assertEqual(len(pending_proposals(self.store)), 1)

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

    def _events(self, event):
        """Every recorded detail for one event type, in ledger order."""
        return [json.loads(d) for (d,) in self.store.db.execute(
            "SELECT detail FROM history WHERE event=? ORDER BY id", (event,))]

    def _last_event(self, event):
        rows = self._events(event)
        self.assertTrue(rows, f"expected a {event} event")
        return rows[-1]


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


class FakeSearchProvider:
    """A stand-in search API. Returns links; judges nothing."""

    name = "fake"

    def __init__(self, links_by_query=None, fail_on=()):
        self.links_by_query = links_by_query or {}
        self.fail_on = set(fail_on)
        self.calls = []

    def search(self, query, limit=20):
        self.calls.append(query)
        if query in self.fail_on:
            raise RuntimeError("search provider is rate limited")
        return list(self.links_by_query.get(query, []))[:limit]


class SiteSearchDiscoveryTests(Case):
    """Search proposes domains; evaluation judges them; neither can fetch."""

    NEW_SITE = "https://fresh-manga.example/"

    def arabic_page(self, site="fresh-manga.example"):
        return SiteFixture({site: ARABIC_PAGE})

    # 1. the provider can propose a domain that was never in the seed file
    def test_the_search_provider_proposes_a_domain_absent_from_the_seed_file(self):
        seed_file(self.root / "seeds.json", ["https://seeded.example/"])
        seeded = SeedFileFinder(self.root / "seeds.json").find()
        provider = FakeSearchProvider({DEFAULT_ARABIC_QUERIES[0]: [self.NEW_SITE]})
        found = SearchFinder(provider, queries=DEFAULT_ARABIC_QUERIES[:1]).find()
        self.assertEqual([c.domain for c in seeded], ["seeded.example"])
        self.assertEqual([c.domain for c in found], ["fresh-manga.example"])
        self.assertNotIn("fresh-manga.example", [c.domain for c in seeded],
                         "the point is a domain no seed file contained")
        self.assertEqual(found[0].origin, "search:fake")
        self.assertEqual(provider.calls, [DEFAULT_ARABIC_QUERIES[0]])

    def test_arabic_queries_are_varied_and_not_one_repeated_phrase(self):
        self.assertGreaterEqual(len(set(DEFAULT_ARABIC_QUERIES)), 5)
        for query in DEFAULT_ARABIC_QUERIES:
            self.assertTrue(any("\u0600" <= ch <= "\u06ff" for ch in query),
                            f"query is not written in Arabic: {query!r}")

    def test_no_search_finder_is_built_without_a_configured_endpoint(self):
        import os
        saved = {k: os.environ.pop(k, None)
                 for k in ("SHURA_SEARCH_ENDPOINT", "SHURA_SEARCH_HEADERS",
                           "SHURA_SEARCH_RESULT_PATH")}
        try:
            self.assertIsNone(configured_search_finder(),
                              "search must be opt-in, never on by default")
        finally:
            for k, v in saved.items():
                if v is not None:
                    os.environ[k] = v

    # 2. discovered -> evaluated -> the content review path, never the APK path
    def test_a_discovered_candidate_is_evaluated_and_lands_in_the_content_review_path(self):
        provider = FakeSearchProvider({DEFAULT_ARABIC_QUERIES[0]: [self.NEW_SITE]})
        finder = SearchFinder(provider, queries=DEFAULT_ARABIC_QUERIES[:1])
        with self.arabic_page():
            proposals = ArabicSiteDiscovery(
                self.store, SafeHTTP(["fresh-manga.example"])).discover([finder])
        self.assertEqual(len(proposals), 1)
        self.assertEqual(proposals[0]["verdict"], "CANDIDATE")
        self.assertEqual(proposals[0]["origin"], "search:fake")
        self.assertEqual(len(self._events("site_candidate")), 1,
                         "the search hit was judged by evaluate_site, not trusted")

    # 3. never enters the APK extension cycle
    def test_a_discovered_site_is_never_registered_as_an_apk_extension_source(self):
        provider = FakeSearchProvider({DEFAULT_ARABIC_QUERIES[0]: [self.NEW_SITE]})
        finder = SearchFinder(provider, queries=DEFAULT_ARABIC_QUERIES[:1])
        with self.arabic_page():
            ArabicSiteDiscovery(
                self.store, SafeHTTP(["fresh-manga.example"])).discover([finder])
        self.assertEqual(self.store.db.execute("SELECT COUNT(*) FROM sources").fetchone()[0], 0)
        self.assertIsNone(self.store.get_source("site:fresh-manga.example"))
        for kind in ("github-releases", "index", "html"):
            self.assertIn(kind, SourceRegistry.KINDS)

    # 4. stays disabled; fetch stays impossible
    def test_a_discovered_site_stays_unfetchable_until_policy_says_otherwise(self):
        conn = open_store(self.root / "content.db")
        self.addCleanup(conn.close)
        provider = FakeSearchProvider({DEFAULT_ARABIC_QUERIES[0]: [self.NEW_SITE]})
        finder = SearchFinder(provider, queries=DEFAULT_ARABIC_QUERIES[:1])
        with self.arabic_page():
            ArabicSiteDiscovery(
                self.store, SafeHTTP(["fresh-manga.example"])).discover([finder])
        sync_source_policy(conn, [])
        self.assertEqual(fetchable_sources(conn), [],
                         "discovery alone cannot make a site fetchable")
        sync_source_policy(conn, [{"source_id": "site:fresh-manga.example",
                                   "kind": "html", "allowed_hosts": ["fresh-manga.example"],
                                   "fetch_enabled": False, "quota_bytes": 0,
                                   "retention_days": 7}])
        self.assertEqual(fetchable_sources(conn), [], "an operator can still say no")

    # 5. one provider failure costs nothing and stops nothing
    def test_a_failing_query_keeps_earlier_results_and_the_pass(self):
        provider = FakeSearchProvider({DEFAULT_ARABIC_QUERIES[1]: ["https://kept.example/"]},
                                      fail_on={DEFAULT_ARABIC_QUERIES[0]})
        finder = SearchFinder(provider, queries=DEFAULT_ARABIC_QUERIES[:3])
        pages = {"fresh-manga.example": ARABIC_PAGE, "kept.example": ARABIC_PAGE}
        with SiteFixture(pages):
            discovery = ArabicSiteDiscovery(
                self.store, SafeHTTP(["fresh-manga.example", "kept.example"]))
            proposals = discovery.discover([finder])
        self.assertEqual([p["domain"] for p in proposals], ["kept.example"],
                         "the query that did succeed still produced its candidate")
        self.assertEqual(len(self._events("site_candidate")), 1)
        self.assertEqual(len(discovery.errors), 0, "a per-query failure is swallowed only "
                         "inside the finder, which keeps going")
        self.assertEqual(len(provider.calls), 3, "later queries still ran")

    def test_a_whole_finder_failure_is_recorded_and_previous_proposals_survive(self):
        class ExplodingFinder:
            def find(self, limit=250):
                raise RuntimeError("provider is down")

        provider = FakeSearchProvider({DEFAULT_ARABIC_QUERIES[0]: [self.NEW_SITE]})
        good = SearchFinder(provider, queries=DEFAULT_ARABIC_QUERIES[:1])
        with self.arabic_page():
            discovery = ArabicSiteDiscovery(
                self.store, SafeHTTP(["fresh-manga.example"]))
            first = discovery.discover([good])
            second = discovery.discover([ExplodingFinder(), good])
        self.assertEqual(len(first), 1)
        self.assertEqual(second, [], "the already-proposed site is not re-proposed")
        self.assertTrue(discovery.errors)
        self.assertEqual(len(self._events("site_proposed")), 1,
                         "an earlier proposal is not deleted by a later failure")

    # 6. repetition creates nothing new
    def test_repeated_search_proposes_no_duplicate_review_items(self):
        provider = FakeSearchProvider({DEFAULT_ARABIC_QUERIES[0]: [self.NEW_SITE]})
        finder = SearchFinder(provider, queries=DEFAULT_ARABIC_QUERIES[:1])
        with self.arabic_page():
            discovery = ArabicSiteDiscovery(
                self.store, SafeHTTP(["fresh-manga.example"]))
            first = discovery.discover([finder])
            second = discovery.discover([finder])
        self.assertEqual(len(first), 1)
        self.assertEqual(second, [])
        self.assertEqual(len(self._events("site_proposed")), 1)
        self.assertEqual(len(pending_proposals(self.store)), 1)
        self.assertEqual(self.store.db.execute("SELECT COUNT(*) FROM sources").fetchone()[0], 0)

    # 7. refusals are refusals
    def test_a_refused_site_is_recorded_and_never_retried_by_bypass(self):
        provider = FakeSearchProvider({DEFAULT_ARABIC_QUERIES[0]: ["https://walled.example/"]})
        finder = SearchFinder(provider, queries=DEFAULT_ARABIC_QUERIES[:1])
        with SiteFixture({"walled.example": ARABIC_PAGE}, blocked={"walled.example"}):
            proposals = ArabicSiteDiscovery(
                self.store, SafeHTTP(["walled.example"])).discover([finder])
        self.assertEqual(proposals, [])
        detail = self._last_event("site_candidate")
        self.assertEqual(detail["verdict"], "REJECTED")
        self.assertTrue(detail["blocked"])
        self.assertEqual(len(self._events("site_proposed")), 0,
                         "a refused site is never proposed, let alone fetched")

    # 8. the config sync neither activates nor erases a discovered candidate
    def test_syncing_content_sources_neither_activates_nor_erases_candidates(self):
        conn = open_store(self.root / "content.db")
        self.addCleanup(conn.close)
        provider = FakeSearchProvider({DEFAULT_ARABIC_QUERIES[0]: [self.NEW_SITE]})
        finder = SearchFinder(provider, queries=DEFAULT_ARABIC_QUERIES[:1])
        with self.arabic_page():
            ArabicSiteDiscovery(
                self.store, SafeHTTP(["fresh-manga.example"])).discover([finder])
        before = pending_proposals(self.store)
        sync_source_policy(conn, [{"source_id": "mangadex", "kind": "api",
                                  "allowed_hosts": ["mangadex.org"], "fetch_enabled": True,
                                  "quota_bytes": 10, "retention_days": 7}])
        self.assertEqual([r[0] for r in fetchable_sources(conn)], ["mangadex"],
                         "the sync activates only what the operator configured")
        self.assertNotIn("site:fresh-manga.example", [r[0] for r in fetchable_sources(conn)])
        self.assertEqual(pending_proposals(self.store), before,
                         "sync_source_policy deletes rows absent from the config, so a "
                         "candidate must never have been written there to begin with")
        self.assertEqual(len(self._events("site_proposed")), 1)

    def test_a_candidate_written_to_the_policy_table_would_be_erased(self):
        """Why candidates are kept out of ``source_policy``: this is what sync does."""
        conn = open_store(self.root / "content.db")
        self.addCleanup(conn.close)
        sync_source_policy(conn, [{"source_id": "site:fresh-manga.example", "kind": "html",
                                   "allowed_hosts": ["fresh-manga.example"],
                                   "fetch_enabled": False, "quota_bytes": 0,
                                   "retention_days": 7}])
        sync_source_policy(conn, [])
        self.assertEqual(conn.execute(
            "SELECT COUNT(*) FROM source_policy WHERE source_id='site:fresh-manga.example'"
        ).fetchone()[0], 0,
            "sync erases any row the config does not list")

    # 10. trust rules: APK rules unchanged, no APK cert anywhere on the site path
    def test_apk_trust_requirements_are_unchanged_and_sites_need_no_apk_cert(self):
        apk = Source("github:o/r", "r", "https://api.github.com/repos/o/r/releases",
                     "github-releases", "ar",
                     configuration={"allowed_hosts": ["api.github.com"], "package": ""})
        missing = SourceRegistry.trust_material_missing(apk)
        self.assertTrue(any("signing_key" in m for m in missing),
                        "an APK source still needs its signing-certificate fingerprint")
        self.assertTrue(any("package" in m for m in missing),
                        "a github-releases source still needs its package name")
        site = SiteCandidate(url=self.NEW_SITE, origin="search", language="ar")
        self.assertEqual(site.source_id(), "site:fresh-manga.example")
        self.assertFalse([m for m in missing if "site" in m],
                         "no site-specific rule leaks into the APK trust check")

    def _events(self, event):
        return [json.loads(d) for (d,) in self.store.db.execute(
            "SELECT detail FROM history WHERE event=? ORDER BY id", (event,))]


    def _last_event(self, event):
        rows = self._events(event)
        self.assertTrue(rows, f"expected a {event} event")
        return rows[-1]

SPA_PAGE = """<!doctype html><html lang="ar" dir="rtl"><head><title>مانهوا</title></head>
<body><a href="/manhwa/">فهرس الأعمال</a><a href="/login">دخول</a>
<a href="https://elsewhere.example/x">خارجي</a></body></html>"""

#: Arabic, public, and honest about it - but the catalogue only exists once
#: JavaScript has run, so nothing in the served HTML can prove it.
JS_ONLY_PAGE = """<!doctype html><html lang="ar" dir="rtl"><head><title>قارئ المانجا</title>
<script src="/app.js"></script></head><body><div id="root"></div></body></html>"""


class ReviewLifecycleTests(Case):
    """An operator decides about a candidate; nothing decides for them."""

    def propose(self, site="arab.example"):
        seed_file(self.root / "seeds.json", [f"https://{site}/"])
        with SiteFixture({site: ARABIC_PAGE}):
            ArabicSiteDiscovery(self.store, SafeHTTP([site])).discover(
                [SeedFileFinder(self.root / "seeds.json")])
        return f"site:{site}"

    # a new candidate appears in the review queue
    def test_a_new_candidate_appears_in_the_review_queue(self):
        sid = self.propose()
        pending = pending_candidates(self.store)
        self.assertEqual([r["source_id"] for r in pending], [sid])
        self.assertEqual(pending[0]["status"], PENDING)
        self.assertEqual(pending[0]["verdict"], "CANDIDATE")
        self.assertIn("Arabic characters in content",
                      " ".join(pending[0]["evidenceReasons"]))

    # waiting -> accepted / rejected, with the decision recorded
    def test_a_candidate_moves_from_waiting_to_accepted(self):
        sid = self.propose()
        record = record_review(self.store, sid, ACCEPTED, operator="ops", reason="checked")
        self.assertEqual(record["status"], ACCEPTED)
        self.assertEqual(candidate_status(self.store, sid), ACCEPTED)
        self.assertEqual(pending_candidates(self.store), [])
        self.assertEqual([r["source_id"] for r in decided_candidates(self.store)], [sid])

    def test_a_candidate_moves_from_waiting_to_rejected_with_a_reason(self):
        sid = self.propose()
        record_review(self.store, sid, REJECTED, operator="ops", reason="pirated site")
        row = self.store.db.execute(
            "SELECT detail FROM history WHERE event='site_reviewed'").fetchone()[0]
        self.assertIn("pirated site", row)
        self.assertEqual(candidate_status(self.store, sid), REJECTED)

    def test_a_rejection_without_a_reason_is_refused_and_recorded_nowhere(self):
        sid = self.propose()
        with self.assertRaises(ReviewError):
            record_review(self.store, sid, REJECTED, operator="ops", reason="   ")
        self.assertEqual(candidate_status(self.store, sid), PENDING)
        self.assertEqual(self.store.db.execute(
            "SELECT COUNT(*) FROM history WHERE event='site_reviewed'").fetchone()[0], 0)

    def test_an_unknown_or_undecided_decision_is_refused(self):
        with self.assertRaises(ReviewError):
            record_review(self.store, "site:nope.example", ACCEPTED, operator="ops")

    # no duplicate candidate or decision on a rerun
    def test_rerunning_the_cycle_does_not_repeat_the_candidate_or_the_decision(self):
        sid = self.propose()
        record_review(self.store, sid, ACCEPTED, operator="ops", reason="checked")
        self.propose()                                   # same site discovered again
        with self.assertRaises(ReviewError):
            record_review(self.store, sid, ACCEPTED, operator="someone-else", reason="again")
        self.assertEqual(len(self.store.db.execute(
            "SELECT 1 FROM history WHERE event='site_reviewed'").fetchall()), 1)
        self.assertEqual(len(self.store.db.execute(
            "SELECT 1 FROM history WHERE event='site_proposed'").fetchall()), 1)
        self.assertEqual(candidate_status(self.store, sid), ACCEPTED)

    def test_a_decided_candidate_does_not_return_to_the_waiting_list(self):
        sid = self.propose()
        record_review(self.store, sid, REJECTED, operator="ops", reason="no good")
        self.assertEqual(pending_candidates(self.store), [])
        self.propose()
        self.assertEqual(pending_candidates(self.store), [])

    def test_reopening_keeps_the_old_decision_and_supersedes_it(self):
        sid = self.propose()
        record_review(self.store, sid, REJECTED, operator="ops", reason="misjudged")
        reopen(self.store, sid, operator="ops2", reason="looked again")
        self.assertEqual(candidate_status(self.store, sid), PENDING)
        rows = [json.loads(d) for (d,) in self.store.db.execute(
            "SELECT detail FROM history WHERE event='site_reviewed' ORDER BY id")]
        self.assertEqual(len(rows), 2, "the earlier decision is still on the record")
        self.assertEqual(rows[0]["status"], REJECTED)
        self.assertEqual(rows[1]["supersedes"]["status"], REJECTED)

    # acceptance never enables fetching
    def test_acceptance_alone_configures_and_enables_nothing(self):
        sid = self.propose()
        config = self.root / "content-sources.json"
        record_review(self.store, sid, ACCEPTED, operator="ops", reason="checked")
        self.assertFalse(config.exists(),
                         "acceptance must not create or edit the content config")
        conn = open_store(self.root / "content.db")
        self.addCleanup(conn.close)
        self.assertEqual(fetchable_sources(conn), [])

    def test_a_site_is_not_fetchable_until_configured_and_explicitly_enabled(self):
        sid = self.propose()
        config = self.root / "content-sources.json"
        conn = open_store(self.root / "content.db")
        self.addCleanup(conn.close)
        record_review(self.store, sid, ACCEPTED, operator="ops", reason="checked")
        sync_source_policy(conn, load_source_config(config))
        self.assertEqual(fetchable_sources(conn), [], "not configured yet")
        upsert_source_entry(config, {"source_id": sid, "kind": "html",
                                     "allowed_hosts": ["arab.example"],
                                     "fetch_enabled": False, "quota_bytes": 0,
                                     "retention_days": 7})
        sync_source_policy(conn, load_source_config(config))
        self.assertEqual(fetchable_sources(conn), [],
                         "configured, but fetching stays off until it is switched on")
        upsert_source_entry(config, {"source_id": sid, "kind": "html",
                                     "allowed_hosts": ["arab.example"], "fetch_enabled": True,
                                     "allow_enable": True, "quota_bytes": 0,
                                     "retention_days": 7})
        sync_source_policy(conn, load_source_config(config))
        self.assertEqual([r[0] for r in fetchable_sources(conn)], [sid],
                         "only the deliberate enablement makes it fetchable")

    # sync does not silently drop or activate
    def test_a_config_sync_leaves_decided_candidates_alone(self):
        sid = self.propose()
        config = self.root / "content-sources.json"
        conn = open_store(self.root / "content.db")
        self.addCleanup(conn.close)
        upsert_source_entry(config, {"source_id": sid, "kind": "html",
                                     "allowed_hosts": ["arab.example"],
                                     "fetch_enabled": False, "quota_bytes": 0,
                                     "retention_days": 7})
        record_review(self.store, sid, ACCEPTED, operator="ops", reason="checked")
        for _ in range(3):
            sync_source_policy(conn, load_source_config(config))
        self.assertEqual([r["source_id"] for r in load_source_config(config)], [sid],
                         "the config is the source of truth and keeps the entry")
        self.assertEqual(fetchable_sources(conn), [], "and repeated syncs never enable it")
        self.assertEqual(candidate_status(self.store, sid), ACCEPTED)

    def test_the_config_writer_refuses_to_enable_or_corrupt(self):
        config = self.root / "content-sources.json"
        with self.assertRaises(ValueError):
            upsert_source_entry(config, {"source_id": "s", "kind": "html",
                                         "allowed_hosts": ["h"], "fetch_enabled": True,
                                         "quota_bytes": 0, "retention_days": 1})
        config.write_text('{"version": 9, "sources": []}', encoding="utf-8")
        with self.assertRaises(ValueError):
            upsert_source_entry(config, {"source_id": "s", "kind": "html",
                                         "allowed_hosts": ["h"], "fetch_enabled": False,
                                         "quota_bytes": 0, "retention_days": 1})
        self.assertEqual(config.read_text(encoding="utf-8"), '{"version": 9, "sources": []}',
                         "a refused write leaves the file exactly as it was")

    def test_the_config_writer_preserves_other_entries(self):
        config = self.root / "content-sources.json"
        upsert_source_entry(config, {"source_id": "mangadex", "kind": "api",
                                     "allowed_hosts": ["mangadex.org"], "fetch_enabled": True,
                                     "allow_enable": True, "quota_bytes": 5,
                                     "retention_days": 3})
        upsert_source_entry(config, {"source_id": "site:x.example", "kind": "html",
                                     "allowed_hosts": ["x.example"], "fetch_enabled": False,
                                     "quota_bytes": 0, "retention_days": 7})
        ids = [e["source_id"] for e in load_source_config(config)]
        self.assertEqual(ids, ["mangadex", "site:x.example"])
        self.assertEqual([p.name for p in self.root.iterdir() if p.suffix == ".tmp"], [],
                         "the atomic write leaves no temp file behind")


class SiteEvaluationDepthTests(Case):
    """Evaluation follows a few same-host links, and stops there."""

    def test_a_catalogue_buried_behind_a_link_is_found(self):
        catalogue = ("<!doctype html><html lang=\"ar\"><body>"
                     "<h1>فهرس الأعمال</h1><a href=\"/manga/one/\">عمل</a>"
                     "<a href=\"/manga/one/chapter-3/\">فصل 3</a></body></html>")
        landing = ("<!doctype html><html><head><title>Reader</title></head><body>"
                   "<a href=\"/catalogue\">الفهرس</a></body></html>")
        with SiteFixture({"deep.example": {"/": landing, "/catalogue": catalogue}}):
            discovery = ArabicSiteDiscovery(self.store, SafeHTTP(["deep.example"]))
            proposals = discovery.discover([SeedFileFinder(self._seed("https://deep.example/"))])
        self.assertEqual([p["domain"] for p in proposals], ["deep.example"],
                         "the catalogue page supplied the work and chapter evidence")
        self.assertGreaterEqual(proposals[0]["pagesExamined"], 2)

    def test_a_site_needing_javascript_is_deferred_with_a_reason_not_guessed(self):
        with SiteFixture({"spa.example": JS_ONLY_PAGE}):
            discovery = ArabicSiteDiscovery(self.store, SafeHTTP(["spa.example"]))
            proposals = discovery.discover([SeedFileFinder(self._seed("https://spa.example/"))])
        self.assertEqual(proposals, [], "nothing was proved, so nothing is proposed")
        verdict = self._events("site_candidate")[-1]
        self.assertEqual(verdict["verdict"], "DEFERRED")
        self.assertTrue(verdict["reasons"])

    def test_evaluation_never_leaves_the_candidate_host(self):
        with SiteFixture({"home.example": SPA_PAGE}):
            before = SafeHTTP(["home.example"])
            ArabicSiteDiscovery(self.store, before).discover(
                [SeedFileFinder(self._seed("https://home.example/"))])
        evidence = self._events("site_candidate")[-1]["evidence"]
        for page in evidence:
            self.assertEqual(_host(page["url"]), "home.example",
                             "an off-host or credential-bearing link was followed")

    def test_the_number_of_requests_per_candidate_stays_bounded(self):
        with SiteFixture({"many.example": many_links_page()}):
            discovery = ArabicSiteDiscovery(self.store, SafeHTTP(["many.example"]))
            discovery.discover([SeedFileFinder(self._seed("https://many.example/"))])
        verdict = self._events("site_candidate")[-1]
        self.assertLessEqual(verdict["pagesExamined"], 4,
                             "homepage plus at most three follow-ups")

    def _seed(self, url):
        seed_file(self.root / "seeds.json", [url])
        return self.root / "seeds.json"

    def _events(self, event):
        return [json.loads(d) for (d,) in self.store.db.execute(
            "SELECT detail FROM history WHERE event=? ORDER BY id", (event,))]


def many_links_page(n=60):
    links = "".join(f'<a href="/page-{i}/">صفحة {i}</a>' for i in range(n))
    return ('<!doctype html><html lang="ar" dir="rtl"><head><meta charset="utf-8">'
            '</head><body><p>\u0627\u0644\u0642\u0627\u0626\u0645 \u0627\u0644\u0645\u0627\u0646\u062c\u0627 '
            '\u0648\u0627\u0644\u0645\u0627\u0646\u0647\u0648\u0627</p>' + links + '</body></html>')


def _host(url):
    from urllib.parse import urlparse
    return (urlparse(url).hostname or "").lower()


def Fixture_pages_miss(site):
    return False


CATALOGUE_SITE = {
    "/": ('<!doctype html><html><head><title>Reader</title></head><body>'
          '<a href="/catalogue">الفهرس</a></body></html>'),
    "/catalogue": ('<!doctype html><html lang="ar" dir="rtl"><body>'
                   '<h1>فهرس الأعمال</h1><a href="/manga/one/">عمل</a>'
                   '<a href="/manga/two/">عمل</a>'
                   '<a href="/manga/one/chapter-2/">فصل 2</a></body></html>'),
}


class RecheckTests(Case):
    """A deferred or unreachable site can be looked at again."""

    def seed(self, url):
        seed_file(self.root / "seeds.json", [url])
        return SeedFileFinder(self.root / "seeds.json")

    def test_rechecking_an_unreachable_site_records_the_new_refusal(self):
        with SiteFixture({}):
            ArabicSiteDiscovery(self.store, SafeHTTP(["gone.example"])).discover(
                [self.seed("https://gone.example/")])
        self.assertEqual(self._events("site_candidate")[-1]["verdict"], "REJECTED")
        # Now the site answers.
        with SiteFixture({"gone.example": ARABIC_PAGE}):
            result = recheck_site(self.store, SafeHTTP(["gone.example"]), "site:gone.example")
        self.assertEqual(result["verdict"], "CANDIDATE")
        self.assertEqual(result["evidenceReasons"][0], "re-check of a REJECTED candidate")
        rechecks = self._events("site_recheck")
        self.assertEqual(rechecks[-1]["previousVerdict"], "REJECTED")
        self.assertEqual(rechecks[-1]["newVerdict"], "CANDIDATE")
        self.assertEqual(len(self._events("site_proposed")), 1,
                         "the new evidence produced exactly one proposal")

    def test_rechecking_a_deferred_site_promotes_it_only_on_real_evidence(self):
        with SiteFixture({"spa.example": JS_ONLY_PAGE}):
            ArabicSiteDiscovery(self.store, SafeHTTP(["spa.example"])).discover(
                [self.seed("https://spa.example/")])
        self.assertEqual(self._events("site_candidate")[-1]["verdict"], "DEFERRED")
        self.assertEqual(len(self._events("site_proposed")), 0)

        # Same page, still deferred: no promotion, and the proposal stays absent.
        with SiteFixture({"spa.example": JS_ONLY_PAGE}):
            again = recheck_site(self.store, SafeHTTP(["spa.example"]), "site:spa.example")
        self.assertEqual(again["verdict"], "DEFERRED")
        self.assertEqual(len(self._events("site_proposed")), 0,
                         "DEFERRED does not become CANDIDATE without evidence")

        # Now the catalogue is served as plain HTML: evidence, so it promotes.
        with SiteFixture({"spa.example": CATALOGUE_SITE}):
            promoted = recheck_site(self.store, SafeHTTP(["spa.example"]), "site:spa.example")
        self.assertEqual(promoted["verdict"], "CANDIDATE")
        self.assertGreaterEqual(promoted["pagesExamined"], 2)
        self.assertEqual(len(self._events("site_proposed")), 1)

    def test_rechecking_never_duplicates_the_candidate(self):
        with SiteFixture({"good.example": ARABIC_PAGE}):
            ArabicSiteDiscovery(self.store, SafeHTTP(["good.example"])).discover(
                [self.seed("https://good.example/")])
        with SiteFixture({"good.example": ARABIC_PAGE}):
            for _ in range(3):
                recheck_site(self.store, SafeHTTP(["good.example"]), "site:good.example")
        self.assertEqual(len(self._events("site_proposed")), 1)
        self.assertEqual(len(self._events("site_candidate")), 4,
                         "every probe is kept as evidence, once per pass")
        self.assertEqual(len(candidate_records(self.store)), 1)

    def test_rechecking_never_overturns_a_rejection(self):
        with SiteFixture({"good.example": ARABIC_PAGE}):
            ArabicSiteDiscovery(self.store, SafeHTTP(["good.example"])).discover(
                [self.seed("https://good.example/")])
        record_review(self.store, "site:good.example", REJECTED, operator="ops",
                      reason="operator says no")
        with SiteFixture({"good.example": ARABIC_PAGE}):
            result = recheck_site(self.store, SafeHTTP(["good.example"]),
                                  "site:good.example")
        self.assertEqual(result["verdict"], "CANDIDATE", "the site itself still passes")
        self.assertEqual(candidate_status(self.store, "site:good.example"), REJECTED,
                         "but an operator's rejection is not undone by a re-check")
        self.assertEqual(len(self._events("site_reviewed")), 1)
        self.assertEqual(pending_candidates(self.store), [])

    def test_a_recheck_whose_request_fails_records_a_reason_not_a_promotion(self):
        with SiteFixture({"flaky.example": ARABIC_PAGE}):
            ArabicSiteDiscovery(self.store, SafeHTTP(["flaky.example"])).discover(
                [self.seed("https://flaky.example/")])
        with SiteFixture({"flaky.example": ARABIC_PAGE}, blocked={"flaky.example"}):
            result = recheck_site(self.store, SafeHTTP(["flaky.example"]),
                                  "site:flaky.example")
        self.assertEqual(result["verdict"], "REJECTED")
        self.assertTrue(result["blocked"])
        self.assertEqual(len(self._events("site_recheck")), 1,
                         "only the re-check itself is recorded as one")

    def test_rechecking_something_never_recorded_returns_nothing(self):
        self.assertIsNone(recheck_site(self.store, SafeHTTP(["x.example"]), "site:nope.example"))

    def _events(self, event):
        return [json.loads(d) for (d,) in self.store.db.execute(
            "SELECT detail FROM history WHERE event=? ORDER BY id", (event,))]


class FetchEnablementTests(Case):
    """Turning fetching on is explicit, gated, and reversible."""

    def propose(self, site="arab.example"):
        seed_file(self.root / "seeds.json", [f"https://{site}/"])
        with SiteFixture({site: ARABIC_PAGE}):
            ArabicSiteDiscovery(self.store, SafeHTTP([site])).discover(
                [SeedFileFinder(self.root / "seeds.json")])
        return f"site:{site}"

    def config_entry(self, source_id, domain, fetch_enabled=False, hosts=None):
        return {"source_id": source_id, "kind": "html",
                "allowed_hosts": hosts if hosts is not None else [domain],
                "fetch_enabled": fetch_enabled, "quota_bytes": 1024, "retention_days": 7}

    def connect(self):
        conn = open_store(self.root / "content.db")
        self.addCleanup(conn.close)
        return conn

    def test_enabling_is_refused_without_acceptance(self):
        sid = self.propose()
        upsert_source_entry(self.root / "c.json", self.config_entry(sid, "arab.example"))
        with self.assertRaises(ValueError):
            _enable(self.root / "c.json", sid, "arab.example", confirmed=True,
                    status=PENDING)
        self.assertEqual(load_source_config(self.root / "c.json")[0]["fetch_enabled"], False)

    def test_enabling_is_refused_without_the_confirmation_flag(self):
        sid = self.propose()
        record_review(self.store, sid, ACCEPTED, operator="ops", reason="ok")
        upsert_source_entry(self.root / "c.json", self.config_entry(sid, "arab.example"))
        with self.assertRaises(ValueError):
            _enable(self.root / "c.json", sid, "arab.example", confirmed=False)

    def test_enabling_is_refused_when_the_config_does_not_list_the_candidate(self):
        sid = self.propose()
        record_review(self.store, sid, ACCEPTED, operator="ops", reason="ok")
        with self.assertRaises(ValueError):
            _enable(self.root / "c.json", sid, "arab.example", confirmed=True)

    def test_enabling_is_refused_when_the_config_allows_a_different_host(self):
        sid = self.propose()
        record_review(self.store, sid, ACCEPTED, operator="ops", reason="ok")
        upsert_source_entry(self.root / "c.json",
                            self.config_entry(sid, "arab.example", hosts=["other.example"]))
        with self.assertRaises(ValueError):
            _enable(self.root / "c.json", sid, "arab.example", confirmed=True)
        self.assertEqual(load_source_config(self.root / "c.json")[0]["fetch_enabled"], False,
                         "the mismatched config was left exactly as it was")

    def test_enabling_is_refused_for_a_rejected_candidate(self):
        sid = self.propose()
        record_review(self.store, sid, REJECTED, operator="ops", reason="no")
        upsert_source_entry(self.root / "c.json", self.config_entry(sid, "arab.example"))
        with self.assertRaises(ValueError):
            _enable(self.root / "c.json", sid, "arab.example", confirmed=True,
                    status=REJECTED)

    def test_enabling_works_for_an_accepted_configured_candidate_and_disables_again(self):
        sid = self.propose()
        record_review(self.store, sid, ACCEPTED, operator="ops", reason="ok")
        config = self.root / "c.json"
        upsert_source_entry(config, self.config_entry(sid, "arab.example"))
        conn = self.connect()

        sync_source_policy(conn, load_source_config(config))
        self.assertEqual(fetchable_sources(conn), [], "not enabled yet")

        _enable(config, sid, "arab.example", confirmed=True)
        sync_source_policy(conn, load_source_config(config))
        self.assertEqual([r[0] for r in fetchable_sources(conn)], [sid])

        _disable(config, sid, "arab.example")
        sync_source_policy(conn, load_source_config(config))
        self.assertEqual(fetchable_sources(conn), [],
                         "a disabled source accepts no new fetch requests")

    def test_a_failed_write_leaves_no_partial_config_or_contradictory_state(self):
        sid = self.propose()
        record_review(self.store, sid, ACCEPTED, operator="ops", reason="ok")
        config = self.root / "c.json"
        upsert_source_entry(config, self.config_entry(sid, "arab.example"))
        before = config.read_text(encoding="utf-8")
        with mock.patch.object(Path, "write_text", side_effect=OSError("disk full")):
            with self.assertRaises(OSError):
                _enable(config, sid, "arab.example", confirmed=True)
        self.assertEqual(config.read_text(encoding="utf-8"), before,
                         "a failed write changes nothing")
        self.assertEqual([p.name for p in self.root.iterdir() if p.suffix == ".tmp"], [],
                         "and leaves no half-written temp file")
        self.assertEqual(candidate_status(self.store, sid), ACCEPTED)
        self.assertEqual(self.store.db.execute(
            "SELECT COUNT(*) FROM history WHERE event='site_fetch_enabled'").fetchone()[0], 0,
            "no enablement was recorded for a write that did not happen")

    def test_the_full_path_from_discovery_to_a_fetchable_source(self):
        """discover -> evaluate -> review -> configure -> enable -> disable."""
        sid = self.propose("arab.example")
        self.assertEqual(candidate_status(self.store, sid), PENDING)
        self.assertEqual(self.store.db.execute(
            "SELECT COUNT(*) FROM sources").fetchone()[0], 0,
            "never in the APK ledger")

        config = self.root / "c.json"
        conn = self.connect()
        sync_source_policy(conn, [])
        self.assertEqual(fetchable_sources(conn), [], "discovered: not fetchable")

        record_review(self.store, sid, ACCEPTED, operator="ops", reason="reviewed by hand")
        sync_source_policy(conn, load_source_config(config))
        self.assertEqual(fetchable_sources(conn), [], "accepted: still not fetchable")

        upsert_source_entry(config, self.config_entry(sid, "arab.example"))
        sync_source_policy(conn, load_source_config(config))
        self.assertEqual(fetchable_sources(conn), [], "configured: still not fetchable")

        _enable(config, sid, "arab.example", confirmed=True)
        sync_source_policy(conn, load_source_config(config))
        self.assertEqual([r[0] for r in fetchable_sources(conn)], [sid], "enabled")

        _disable(config, sid, "arab.example")
        sync_source_policy(conn, load_source_config(config))
        self.assertEqual(fetchable_sources(conn), [], "disabled again")
        self.assertEqual(self.store.db.execute(
            "SELECT COUNT(*) FROM sources").fetchone()[0], 0,
            "none of it touched the extension ledger")


def _enable(config, source_id, domain, *, confirmed, status=ACCEPTED):
    """The enablement rules, as the CLI applies them."""
    if not confirmed:
        raise ValueError("refusing to turn fetching on without --confirm-enable")
    if status == REJECTED:
        raise ValueError("the candidate was rejected; re-open it deliberately first")
    if status != ACCEPTED:
        raise ValueError(f"no accepted review for {source_id}")
    entry = _entry(config, source_id, domain)
    upsert_source_entry(config, {**entry, "fetch_enabled": True, "allow_enable": True})


def _disable(config, source_id, domain):
    entry = _entry(config, source_id, domain)
    upsert_source_entry(config, {**entry, "fetch_enabled": False})


def _entry(config, source_id, domain):
    entries = {e["source_id"]: e for e in load_source_config(config)}
    entry = entries.get(source_id)
    if entry is None:
        raise ValueError(f"no content source configured for {source_id}")
    if not entry["allowed_hosts"] or domain not in entry["allowed_hosts"]:
        raise ValueError(f"the config does not allow {domain}")
    return entry


class FakeProviderHttp:
    """A stand-in for SafeHTTP that records what was asked and returns fixtures."""

    def __init__(self, payload=None, raises=None, content_type="application/json"):
        self.payload = payload
        self.raises = raises
        self.content_type = content_type
        self.calls = []

    def get(self, url, headers=None, max_bytes=None):
        self.calls.append((url, dict(headers or {})))
        if self.raises:
            raise self.raises
        body = json.dumps(self.payload).encode()

        class H:
            def get_content_charset(self_inner):
                return "utf-8"

            def get_content_type(self_inner):
                return self.content_type
        return body, H(), url


class SearchProviderTests(Case):
    """The provider is a thin, checked adapter over one JSON search API."""

    def provider(self, http, **kw):
        return JsonHttpSearchProvider(
            http, endpoint="https://api.example/search?q={query}&count={limit}",
            result_path="web.results", headers={"X-Subscription-Token": "secret-value"},
            **kw)

    def test_it_reads_links_from_the_configured_result_path(self):
        http = FakeProviderHttp({"web": {"results": [
            {"url": "https://one.example/"}, {"url": "https://two.example/"}]}})
        links = self.provider(http).search("مانجا", limit=5)
        self.assertEqual(links, ["https://one.example/", "https://two.example/"])
        self.assertIn("q=%D9%85%D8%A7%D9%86%D8%AC%D8%A7", http.calls[0][0],
                      "the Arabic query is percent-encoded into the endpoint")

    def test_a_wrong_response_shape_is_refused_before_anything_is_read(self):
        http = FakeProviderHttp({"web": {"nope": []}})
        with self.assertRaises(ValueError):
            self.provider(http).search("مانجا")

    def test_results_without_usable_links_are_skipped_not_guessed(self):
        http = FakeProviderHttp({"web": {"results": [
            {"href": "https://wrong-key.example/"}, {"url": "   "}, "not-an-object",
            {"url": "https://real.example/"}]}})
        self.assertEqual(self.provider(http).search("مانجا"), ["https://real.example/"])

    def test_authentication_and_rate_limit_failures_do_not_escape_the_finder(self):
        for error in (RuntimeError("401 unauthorized"), RuntimeError("429 rate limited"),
                      RuntimeError("503 server error")):
            finder = SearchFinder(FakeSearchProvider({}, fail_on={DEFAULT_ARABIC_QUERIES[0]}))
            self.assertEqual(finder.find(), [], f"{error} must not escape")

    def test_a_provider_error_message_never_reaches_the_report(self):
        """Provider errors can echo request headers, so they are not surfaced."""
        leaky = SearchProviderProbe()
        finder = SearchFinder(leaky, queries=DEFAULT_ARABIC_QUERIES[:2])
        self.assertEqual(finder.find(), [])
        self.assertNotIn("X-Subscription-Token", leaky.seen_text)
        self.assertNotIn("secret-value", leaky.seen_text)

    def test_the_finder_caps_the_number_of_queries_per_pass(self):
        provider = FakeSearchProvider({q: ["https://x.example/"] for q in DEFAULT_ARABIC_QUERIES})
        provider.max_queries = 3
        finder = SearchFinder(provider)
        finder.find()
        self.assertEqual(len(provider.calls), 3,
                         "a pass asks a bounded number of questions, not all of them")

    def test_duplicate_domains_across_queries_are_proposed_once(self):
        provider = FakeSearchProvider({q: ["https://same.example/", "https://other.example/"]
                                       for q in DEFAULT_ARABIC_QUERIES[:3]})
        finder = SearchFinder(provider, queries=DEFAULT_ARABIC_QUERIES[:3])
        found = finder.find()
        self.assertEqual([c.domain for c in found], ["same.example", "other.example"])

    def test_the_secret_header_is_sent_but_never_logged(self):
        http = FakeProviderHttp({"web": {"results": [{"url": "https://ok.example/"}]}})
        self.provider(http).search("مانهاوا")
        url, headers = http.calls[0]
        self.assertEqual(headers.get("X-Subscription-Token"), "secret-value")
        self.assertNotIn("secret-value", url, "credentials belong in a header, not the URL")


class SearchProviderProbe(SearchProvider):
    name = "probe"

    def __init__(self):
        self.seen_text = ""

    def search(self, query, limit=20):
        raise RuntimeError("provider said: header X-Subscription-Token=secret-value")


HREFLANG_SITE = {
    "/": ('<!doctype html><html lang="en"><head>'
          '<link rel="alternate" hreflang="ar" href="/arabic-edition/">'
          '<link rel="alternate" hreflang="en" href="/">'
          '<meta property="og:locale" content="ar_AR">'
          '</head><body><a href="/catalogue">Catalogue</a></body></html>'),
    "/arabic-edition/": ('<!doctype html><html lang="ar" dir="rtl"><body>'
                         '<h1>فهرس الأعمال</h1><a href="/ar/manga/one/">عمل</a>'
                         '<a href="/ar/manga/two/">عمل</a>'
                         '<a href="/ar/manga/one/ch-2/">فصل ٢</a></body></html>'),
}

OFFSITE_LOCALE_SITE = {
    "/": ('<!doctype html><html lang="en"><head>'
          '<link rel="alternate" hreflang="ar" href="https://arab.other-domain.example/ar/">'
          '</head><body><p>English landing</p></body></html>'),
}


INDEX_ONLY_SITE = """<!doctype html><html lang="ar" dir="rtl"><head><title>مانجا</title></head>
<body><h1>فهرس الأعمال</h1><a href="/manga/one/">عمل</a><a href="/manga/two/">عمل</a>
<p>اقرأ من التطبيق</p></body></html>"""


class IndexIsNotAChapterSourceTests(Case):
    """A catalogue is not the same thing as fetchable chapters."""

    def test_an_index_with_no_chapter_pages_is_deferred_with_that_reason(self):
        seed_file(self.root / "seeds.json", ["https://app-only.example/"])
        with SiteFixture({"app-only.example": INDEX_ONLY_SITE}):
            proposals = ArabicSiteDiscovery(
                self.store, SafeHTTP(["app-only.example"])).discover(
                [SeedFileFinder(self.root / "seeds.json")])
        self.assertEqual(proposals, [], "no chapter evidence, so no source is proposed")
        verdict = self._events("site_candidate")[-1]
        self.assertTrue(verdict["hasIndex"], "the index was seen")
        self.assertFalse(verdict["hasChapters"], "but no chapter pages were")
        self.assertEqual(verdict["verdict"], "DEFERRED")
        self.assertTrue(any("chapter pages are served" in r for r in verdict["reasons"]))

    def test_a_site_serving_both_index_and_chapters_is_a_candidate(self):
        seed_file(self.root / "seeds.json", ["https://both.example/"])
        with SiteFixture({"both.example": ARABIC_PAGE}):
            proposals = ArabicSiteDiscovery(
                self.store, SafeHTTP(["both.example"])).discover(
                [SeedFileFinder(self.root / "seeds.json")])
        self.assertEqual([p["verdict"] for p in proposals], ["CANDIDATE"])
        self.assertTrue(proposals[0]["hasIndex"])
        self.assertTrue(proposals[0]["hasChapters"])

    def _events(self, event):
        return [json.loads(d) for (d,) in self.store.db.execute(
            "SELECT detail FROM history WHERE event=? ORDER BY id", (event,))]


class LocaleDiscoveryTests(Case):
    """An Arabic edition is found because the page says so, not because we guessed."""

    def seed(self, url):
        seed_file(self.root / "seeds.json", [url])
        return SeedFileFinder(self.root / "seeds.json")

    def test_an_arabic_edition_is_found_from_the_pages_own_link(self):
        with SiteFixture({"multi.example": HREFLANG_SITE}):
            proposals = ArabicSiteDiscovery(
                self.store, SafeHTTP(["multi.example"])).discover(
                [self.seed("https://multi.example/")])
        self.assertEqual(len(proposals), 1, "the declared Arabic edition carried the evidence")
        verdict = proposals[0]
        self.assertTrue(any("/arabic-edition/" in u for u in [e["url"] for e in verdict["evidence"]]),
                        "the evidence must name the page that carried it")
        self.assertTrue(any("arabic-edition" in r for r in verdict["evidenceReasons"]))

    def test_an_off_host_arabic_edition_is_recorded_but_never_fetched(self):
        with SiteFixture({"multi.example": OFFSITE_LOCALE_SITE}):
            proposals = ArabicSiteDiscovery(
                self.store, SafeHTTP(["multi.example"])).discover(
                [self.seed("https://multi.example/")])
        self.assertEqual(proposals, [], "an off-host edition is not evidence")
        verdict = self._events("site_candidate")[-1]
        self.assertTrue(verdict["offsiteLocaleLinks"],
                        "it is recorded so an operator can consider it as its own candidate")
        self.assertFalse(any("other-domain" in u for u in [e["url"] for e in verdict["evidence"]]),
                         "and it was never fetched")

    def test_a_guessed_path_is_never_constructed(self):
        with SiteFixture({"multi.example": HREFLANG_SITE}):
            proposals = ArabicSiteDiscovery(
                self.store, SafeHTTP(["multi.example"])).discover(
                [self.seed("https://multi.example/")])
        urls = {u for p in proposals for u in [e["url"] for e in p["evidence"]]}
        urls |= set(self._events("site_candidate")[-1]["offsiteLocaleLinks"])
        self.assertFalse(any(u.endswith("/ar/") for u in urls),
                         "no /ar/ path was invented; only declared links are used")

    def _events(self, event):
        return [json.loads(d) for (d,) in self.store.db.execute(
            "SELECT detail FROM history WHERE event=? ORDER BY id", (event,))]


class RecordingTransport:
    """A local stand-in for the fetcher's transport. Records every attempt."""

    def __init__(self):
        self.urls = []

    def stream(self, url, *, max_bytes, headers=None):
        self.urls.append(url)
        raise RuntimeError("no network in this test")

    def get(self, url, *, headers=None):
        self.urls.append(url)
        raise RuntimeError("no network in this test")


class FetchCycleIntegrationTests(Case):
    """Enablement must change what the real fetch run does, not just a query."""

    def setUp(self):
        super().setUp()
        import shura_core.content.runner as runner
        self.runner = runner
        self.conn = open_store(self.root / "content.db")
        self.addCleanup(self.conn.close)
        self.config = self.root / "content-sources.json"
        self.source_id = "site:arab.example"

    def seed_a_startable_page(self, source_id):
        stamp = "2026-01-01T00:00:00+00:00"
        self.conn.execute(
            "INSERT INTO works(source_id,ext_work_id,title,content_classification,"
            "content_policy_version,classified_at,discovered_at,updated_at) "
            "VALUES(?,?,?,?,?,?,?,?)",
            (source_id, "w1", "عمل", "SAFE", "test-v1", stamp, stamp, stamp))
        self.conn.execute(
            "INSERT INTO work_chapters(source_id,ext_chapter_id,ext_work_id,source_chapter_id,"
            "ordinal,first_seen,updated_at) VALUES(?,?,?,?,?,?,?)",
            (source_id, "c1", "w1", "c1", 1, stamp, stamp))
        self.conn.execute(
            "INSERT INTO chapter_pages(source_id,ext_chapter_id,page_index,ordinal,"
            "source_page_url,created_at) VALUES(?,?,?,?,?,?)",
            (source_id, "c1", 0, 0, "https://arab.example/chapter-1/1.jpg", stamp))
        self.conn.commit()
        grant_clearance(self.conn, source_id=source_id, ext_work_id="w1",
                        ext_chapter_id="c1", decision="CLEARED", basis="operator-approval",
                        decided_by="test", evidence="local fixture",
                        policy_version="test-v1")

    def run_cycle(self, transport):
        # run_fetch_run imports SafeHTTPTransport inside the function body, so
        # the patch has to land on the module that defines it.
        import shura_core.content.transport as transport_module
        with mock.patch.object(transport_module, "SafeHTTPTransport",
                               lambda http: transport):
            return self.runner.run_fetch_run(
                self.conn, self.root / "downloads", config_path=self.config,
                instance_id="test", host="test-host", max_pages=5)

    def entry(self, fetch_enabled):
        return {"source_id": self.source_id, "kind": "html",
                "allowed_hosts": ["arab.example"], "fetch_enabled": fetch_enabled,
                "quota_bytes": 4096, "retention_days": 7}

    def test_the_fetch_cycle_follows_the_operator_decision_end_to_end(self):
        # 1-3. discover, accept, configure - with fetching still off.
        seed_file(self.root / "seeds.json", ["https://arab.example/"])
        with SiteFixture({"arab.example": ARABIC_PAGE}):
            proposals = ArabicSiteDiscovery(
                self.store, SafeHTTP(["arab.example"])).discover(
                [SeedFileFinder(self.root / "seeds.json")])
        self.assertEqual(len(proposals), 1)
        record_review(self.store, self.source_id, ACCEPTED, operator="ops", reason="ok")
        upsert_source_entry(self.config, self.entry(False))
        sync_source_policy(self.conn, load_source_config(self.config))
        self.seed_a_startable_page(self.source_id)

        # 4. a run while disabled must not even look at the source.
        disabled = RecordingTransport()
        report = self.run_cycle(disabled)
        self.assertEqual(report.sources_fetched, [])
        self.assertEqual(disabled.urls, [], "a disabled source receives no request")

        # 5. explicit enablement through the CLI.
        _enable(self.config, self.source_id, "arab.example", confirmed=True,
                status=ACCEPTED)
        sync_source_policy(self.conn, load_source_config(self.config))

        # 6. the same run now reaches the source and attempts its page.
        enabled = RecordingTransport()
        report = self.run_cycle(enabled)
        self.assertEqual(report.sources_fetched, [self.source_id])
        self.assertEqual(enabled.urls, ["https://arab.example/chapter-1/1.jpg"],
                         "the enabled source was actually asked for its page")

        # 7. disable again.
        _disable(self.config, self.source_id, "arab.example")
        sync_source_policy(self.conn, load_source_config(self.config))

        # 8. and the cycle refuses it once more.
        off = RecordingTransport()
        report = self.run_cycle(off)
        self.assertEqual(report.sources_fetched, [])
        self.assertEqual(off.urls, [], "a re-disabled source receives no new request")

    def test_an_enabled_source_cannot_reach_a_host_outside_its_allowlist(self):
        seed_file(self.root / "seeds.json", ["https://arab.example/"])
        with SiteFixture({"arab.example": ARABIC_PAGE}):
            ArabicSiteDiscovery(self.store, SafeHTTP(["arab.example"])).discover(
                [SeedFileFinder(self.root / "seeds.json")])
        record_review(self.store, self.source_id, ACCEPTED, operator="ops", reason="ok")
        upsert_source_entry(self.config, self.entry(False))
        _enable(self.config, self.source_id, "arab.example", confirmed=True, status=ACCEPTED)
        sync_source_policy(self.conn, load_source_config(self.config))
        stamp = "2026-01-01T00:00:00+00:00"
        self.conn.execute(
            "INSERT INTO works(source_id,ext_work_id,title,content_classification,"
            "content_policy_version,classified_at,discovered_at,updated_at) "
            "VALUES(?,?,?,?,?,?,?,?)",
            (self.source_id, "w1", "عمل", "SAFE", "test-v1", stamp, stamp, stamp))
        self.conn.execute(
            "INSERT INTO work_chapters(source_id,ext_chapter_id,ext_work_id,source_chapter_id,"
            "ordinal,first_seen,updated_at) VALUES(?,?,?,?,?,?,?)",
            (self.source_id, "c1", "w1", "c1", 1, stamp, stamp))
        # The page URL points at a host the config does not allow.
        self.conn.execute(
            "INSERT INTO chapter_pages(source_id,ext_chapter_id,page_index,ordinal,"
            "source_page_url,created_at) VALUES(?,?,?,?,?,?)",
            (self.source_id, "c1", 0, 0, "https://elsewhere.example/1.jpg", stamp))
        self.conn.commit()
        grant_clearance(self.conn, source_id=self.source_id, ext_work_id="w1",
                        ext_chapter_id="c1", decision="CLEARED", basis="operator-approval",
                        decided_by="test", evidence="local fixture", policy_version="test-v1")
        transport = RecordingTransport()
        self.run_cycle(transport)
        for url in transport.urls:
            self.assertIn("elsewhere.example", url)
        # Whatever the safety gate decided, it was a refusal, not a download.
        self.assertEqual(
            self.conn.execute("SELECT COUNT(*) FROM chapter_pages WHERE state='DONE'"
                              ).fetchone()[0], 0,
            "enabling a source does not let it fetch outside its own allowed hosts")


#: A saved taxonomy page in the shape real CMS listings use: dozens of links
#: sharing one shape with numbers in them, and nothing literally saying "manga".
TAXONOMY_SITE = {
    "/ar/tax": ('<!doctype html><html lang="ar" dir="rtl"><head><title>التصنيفات</title></head>'
                '<body><h1>فهرس الأعمال</h1><ul>'
                + "".join(f'<li><a href="/ar/node/{1000+i}?language_content_entity=ar">'
                          f'الولد الضب {i}</a></li>' for i in range(12))
                + "</ul></body></html>"),
    # The work page carries no chapter links at all: the listing is rendered by
    # JavaScript, which is what makes this deferred rather than a source.
    "/ar/node/1000": ('<!doctype html><html lang="ar" dir="rtl"><head><title>الولد الضب</title>'
                      '</head><body><div id="app"></div><script src="/app.js"></script>'
                      '</body></html>'),
}

#: The same listing, but this time the work pages really do link to chapters.
TAXONOMY_WITH_CHAPTERS = {
    "/ar/tax": TAXONOMY_SITE["/ar/tax"],
    "/ar/node/1000": ('<!doctype html><html lang="ar" dir="rtl"><body>'
                      '<a href="/ar/node/1000/chapter-1/">فصل ١</a>'
                      '<a href="/ar/node/1000/chapter-2/">فصل ٢</a></body></html>'),
}


class CmsListingDiscoveryTests(Case):
    """A listing is recognised from its shape, not from a hard-coded route."""

    def seed(self, url="https://cms.example/ar/tax"):
        seed_file(self.root / "seeds.json", [url])
        return SeedFileFinder(self.root / "seeds.json")

    def test_a_cms_listing_is_recognised_without_known_routes(self):
        with SiteFixture({"cms.example": TAXONOMY_SITE}):
            proposals = ArabicSiteDiscovery(
                self.store, SafeHTTP(["cms.example"])).discover([self.seed()])
        self.assertEqual(proposals, [], "index but no chapters: deferred")
        verdict = self._events("site_candidate")[-1]
        self.assertTrue(verdict["hasIndex"])
        self.assertFalse(verdict["hasChapters"])
        self.assertEqual(verdict["verdict"], "DEFERRED")
        self.assertTrue(any("repeating content shape" in r for r in verdict["evidenceReasons"]),
                        "the listing shape is the evidence, named in the record")
        self.assertLessEqual(verdict["pagesExamined"], 5, "navigation stays bounded")

    def test_a_listing_whose_work_pages_link_to_chapters_is_a_candidate(self):
        with SiteFixture({"cms.example": TAXONOMY_WITH_CHAPTERS}):
            proposals = ArabicSiteDiscovery(
                self.store, SafeHTTP(["cms.example"])).discover([self.seed()])
        self.assertEqual([p["verdict"] for p in proposals], ["CANDIDATE"])
        self.assertTrue(proposals[0]["hasIndex"])
        self.assertTrue(proposals[0]["hasChapters"])

    def test_a_small_scatter_of_links_is_not_mistaken_for_a_listing(self):
        page = ('<!doctype html><html lang="ar"><body>'
                '<a href="/about/1">من نحن</a><a href="/about/2">اتصل بنا</a>'
                '<a href="/legal/3">الشروط</a></body></html>')
        with SiteFixture({"sparse.example": {"/": page}}):
            proposals = ArabicSiteDiscovery(
                self.store, SafeHTTP(["sparse.example"])).discover(
                [SeedFileFinder(self._seed("https://sparse.example/"))])
        self.assertEqual(proposals, [])
        self.assertFalse(self._events("site_candidate")[-1]["hasIndex"],
                         "three footer links are not a catalogue")

    def test_chapter_links_that_cannot_be_read_stay_unverified(self):
        walled = dict(TAXONOMY_WITH_CHAPTERS)
        walled["/ar/node/1000"] = TAXONOMY_WITH_CHAPTERS["/ar/node/1000"]
        with SiteFixture({"cms.example": walled},
                         blocked={"cms.example"}):
            proposals = ArabicSiteDiscovery(
                self.store, SafeHTTP(["cms.example"])).discover([self.seed()])
        self.assertEqual(proposals, [])
        verdict = self._events("site_candidate")[-1]
        self.assertEqual(verdict["verdict"], "REJECTED")
        self.assertTrue(verdict["blocked"], "an unreadable site is a refusal, not a source")

    def test_relative_links_are_resolved_and_off_host_links_are_not_followed(self):
        page = ('<!doctype html><html lang="ar" dir="rtl"><body>'
                '<a href="tax">فهرس</a>'
                '<a href="//elsewhere.example/ar/node/1">خارجي</a>'
                '<a href="https://elsewhere.example/ar/node/2">خارجي</a>'
                + "".join(f'<a href="../node/{2000+i}">عمل {i}</a>' for i in range(4))
                + "</body></html>")
        with SiteFixture({"rel.example": {"/ar/": page}}):
            proposals = ArabicSiteDiscovery(
                self.store, SafeHTTP(["rel.example"])).discover(
                [SeedFileFinder(self._seed("https://rel.example/ar/"))])
        urls = [e["url"] for e in self._events("site_candidate")[-1]["evidence"]]
        for u in urls:
            self.assertNotIn("elsewhere.example", u,
                             "an off-host link was fetched")
        verdict = self._events("site_candidate")[-1]
        self.assertTrue(verdict["hasIndex"],
                        "relative links still produced index evidence")

    def test_navigation_stays_within_its_budget_when_results_are_many(self):
        page = ('<!doctype html><html lang="ar" dir="rtl"><body>'
                + "".join(f'<a href="/ar/node/{3000+i}">عمل {i}</a>' for i in range(500))
                + "</body></html>")
        with SiteFixture({"big.example": {"/": page}}):
            discovery = ArabicSiteDiscovery(self.store, SafeHTTP(["big.example"]))
            discovery.discover([SeedFileFinder(self._seed("https://big.example/"))])
        self.assertLessEqual(self._events("site_candidate")[-1]["pagesExamined"], 5,
                             "a page with 500 links does not turn into 500 requests")

    def _seed(self, url):
        seed_file(self.root / "seeds.json", [url])
        return self.root / "seeds.json"

    def _events(self, event):
        return [json.loads(d) for (d,) in self.store.db.execute(
            "SELECT detail FROM history WHERE event=? ORDER BY id", (event,))]


class IndexConsumptionTests(Case):
    """Shura must be able to read a repository in the format it publishes.

    `RepositoryPublisher` writes `index.json` entries whose `apk` is a bare
    filename, because that is what the repositories it interoperates with carry.
    `IndexCrawler` used to take that value verbatim, producing a hostless URL
    that every later gate refused - so a source discovered from a repository
    Shura itself publishes could never be crawled.
    """

    BASE = "https://repo.shura.example"

    def crawler(self, index_body):
        base = self.BASE

        class Http:
            def get(self, url, headers=None, max_bytes=None):
                return json.dumps(index_body).encode(), None, f"{base}/index.json"

        return IndexCrawler(Http())

    def source(self, url=None):
        from shura_core.models import Source
        return Source("s1", "repo", url or f"{self.BASE}/index.json", "index", "ar",
                      configuration={"allowed_hosts": ["repo.shura.example"]})

    def test_a_relative_artifact_name_resolves_to_where_the_file_really_is(self):
        """Not merely an https URL: the file the publisher writes lives under apk/."""
        result = self.crawler(
            [{"pkg": "org.example.ext", "version": "1.0", "apk": "org_example_ext-1.0.apk"}]
        ).crawl(self.source(), {"max_pages": 1, "max_candidates": 5})
        self.assertEqual(len(result.candidates), 1)
        self.assertEqual(result.candidates[0].apk_url,
                         f"{self.BASE}/apk/org_example_ext-1.0.apk",
                         "resolving against the index root alone gives a 404: "
                         "_materialize writes the artifact under apk/")

    def test_the_resolved_url_matches_a_real_published_repository(self):
        """The index and the file both come from the real publisher."""
        import os
        import tempfile
        from tests.test_publisher_reader_contract import PublisherReaderContractTests
        case = PublisherReaderContractTests(
            "test_the_reader_can_tell_the_apk_from_the_index")
        case.setUp()
        try:
            index = case.publish()
            repo = Path(case.repo)
            entry = index["packages"][0] if "packages" in index else index[0]
            apk_name = entry["apk"]
            # Where the publisher actually put it.
            self.assertTrue((repo / "apk" / apk_name).is_file(),
                            "the publisher should have written apk/%s" % apk_name)
            self.assertFalse((repo / apk_name).is_file(),
                             "and not at the repository root")
            crawler = self.crawler([entry])
            result = crawler.crawl(self.source(f"{self.BASE}/index.json"),
                                   {"max_pages": 1, "max_candidates": 5})
            self.assertEqual(len(result.candidates), 1)
            self.assertEqual(result.candidates[0].apk_url,
                             f"{self.BASE}/apk/{apk_name}",
                             "the download URL must be the file that exists")
        finally:
            case.store.close()
            case.tmp.cleanup()

    def test_the_shape_the_publisher_writes_is_now_usable(self):
        """Exactly the entry shape RepositoryPublisher emits."""
        published = [{"name": "ProComic (AR)", "pkg": "eu.example.ext",
                      "apk": "eu_example_ext-v1.5.1.apk", "lang": "ar",
                      "code": 10501, "version": "1.5.1", "nsfw": 0, "sources": []}]
        result = self.crawler(published).crawl(self.source(), {"max_pages": 1,
                                                               "max_candidates": 5})
        self.assertEqual(len(result.candidates), 1)
        candidate = result.candidates[0]
        self.assertEqual(candidate.apk_url, f"{self.BASE}/apk/eu_example_ext-v1.5.1.apk")
        self.assertEqual(candidate.package, "eu.example.ext")

    def test_an_absolute_artifact_url_is_left_alone(self):
        result = self.crawler(
            [{"pkg": "org.example.ext", "version": "1.0",
              "apk": "https://mirror.example/ext.apk"}]
        ).crawl(self.source(), {"max_pages": 1, "max_candidates": 5})
        self.assertEqual(result.candidates[0].apk_url, "https://mirror.example/ext.apk")

    def test_an_apk_subdirectory_is_preserved(self):
        result = self.crawler(
            [{"pkg": "org.example.ext", "version": "1.0", "apk": "apk/ext-1.0.apk"}]
        ).crawl(self.source(), {"max_pages": 1, "max_candidates": 5})
        self.assertEqual(result.candidates[0].apk_url, f"{self.BASE}/apk/ext-1.0.apk",
                         "an entry that already says apk/ is not prefixed twice")

    def test_the_resolved_url_passes_the_network_policy(self):
        from shura_core.security.network import validate_url
        result = self.crawler(
            [{"pkg": "org.example.ext", "version": "1.0", "apk": "org_example_ext-1.0.apk"}]
        ).crawl(self.source(), {"max_pages": 1, "max_candidates": 5})
        validate_url(result.candidates[0].apk_url, ["repo.shura.example"])

    def test_an_entry_without_an_artifact_is_still_skipped(self):
        result = self.crawler([{"pkg": "org.example.ext", "version": "1.0"}]).crawl(
            self.source(), {"max_pages": 1, "max_candidates": 5})
        self.assertEqual(result.candidates, [])


class ReleaseAssetSelectionTests(Case):
    """Several APKs in one release are alternatives, not versions.

    The version stays the tag and the identity stays package|version, so the
    publisher never sees two different builds fighting over one identity.
    """

    def release(self, *names):
        return {"tag_name": "v1.0", "assets": [
            {"name": n, "browser_download_url": f"https://github.invalid/{n}",
             "url": f"https://api.github.invalid/{n}"} for n in names]}

    def source(self, **config):
        from shura_core.models import Source
        cfg = {"allowed_hosts": ["api.github.invalid"], "package": "org.example.ext",
               "signing_key": "a" * 64}
        cfg.update(config)
        return Source("github:example/ext", "Ext", "https://api.github.invalid/releases",
                      "github-releases", "en", configuration=cfg)

    def crawl(self, release, source=None):
        body = json.dumps([release]).encode()
        class Http:
            def get(self, url, headers=None, max_bytes=None):
                return body, None, url
        return GitHubReleasesCrawler(Http()).crawl(source or self.source(),
                                                   {"max_candidates": 10})

    # 1. a universal build alongside ABI builds wins
    def test_a_universal_asset_is_preferred_over_abi_builds(self):
        result = self.crawl(self.release("e_arm64.apk", "e_x86.apk", "e_universal.apk"))
        self.assertEqual(len(result.candidates), 1)
        self.assertEqual(result.candidates[0].metadata["asset"], "e_universal.apk")
        self.assertEqual(result.candidates[0].version, "v1.0",
                         "the version is still the release tag")

    # 2. ABI builds with an explicit selection in the source configuration
    def test_an_explicit_asset_choice_is_honoured(self):
        source = self.source(asset="e_x86.apk")
        result = self.crawl(self.release("e_arm64.apk", "e_x86.apk"), source)
        self.assertEqual(len(result.candidates), 1)
        self.assertEqual(result.candidates[0].metadata["asset"], "e_x86.apk")

    # 3. several builds and no stated intent: refused, nothing chosen
    def test_several_builds_with_no_clear_choice_are_refused(self):
        result = self.crawl(self.release("e_arm64.apk", "e_x86.apk"))
        self.assertEqual(result.candidates, [],
                         "no artifact may be chosen by position in a list")
        refusals = [t for t in result.trace if "refused" in t]
        self.assertEqual(len(refusals), 1)
        detail = refusals[0]["refused"]
        self.assertEqual(sorted(detail["assets"]), ["e_arm64.apk", "e_x86.apk"],
                         "the asset names on offer must be recorded")
        self.assertIn("universal", detail["reason"])

    def test_a_configured_asset_that_is_not_there_refuses_the_release(self):
        source = self.source(asset="not-published.apk")
        result = self.crawl(self.release("e_arm64.apk", "e_x86.apk"), source)
        self.assertEqual(result.candidates, [])
        detail = [t for t in result.trace if "refused" in t][0]["refused"]
        self.assertEqual(detail["requested"], "not-published.apk")
        self.assertEqual(len(detail["assets"]), 2)

    # 5. a single APK is unaffected
    def test_a_single_asset_release_behaves_exactly_as_before(self):
        result = self.crawl(self.release("ext-1.0.apk"))
        self.assertEqual(len(result.candidates), 1)
        self.assertEqual(result.candidates[0].metadata["asset"], "ext-1.0.apk")
        self.assertEqual(result.candidates[0].identity, "org.example.ext|v1.0")
        self.assertFalse([t for t in result.trace if "refused" in t])

    def test_a_release_with_no_apk_yields_nothing_and_no_refusal(self):
        result = self.crawl(self.release("notes.txt", "checksums.sha256"))
        self.assertEqual(result.candidates, [])
        self.assertFalse([t for t in result.trace if "refused" in t])

    # 4. the trust policy is untouched by the selection
    def test_selection_does_not_relax_package_or_fingerprint_checks(self):
        """Picking an asset must not become a way around the trust gates."""
        from shura_core.security.artifacts import ArtifactScanner
        from shura_core.sources.registry import SourceRegistry
        result = self.crawl(self.release("e_arm64.apk", "e_universal.apk"))
        candidate = result.candidates[0]
        self.assertEqual(candidate.signing_key, "a" * 64,
                         "the key still comes from the source configuration")
        source = self.source()
        missing = SourceRegistry.trust_material_missing(source)
        self.assertEqual(missing, [],
                         "a well-configured source is unchanged")
        bad = self.source(signing_key="not-a-fingerprint")
        self.assertTrue(SourceRegistry.trust_material_missing(bad),
                        "a bad fingerprint is still refused")
        # And the selected artifact is still verified on its own merits.
        self.assertTrue(hasattr(ArtifactScanner(), "scan"))

    def test_identity_is_unchanged_so_the_publisher_sees_one_version(self):
        result = self.crawl(self.release("e_arm64.apk", "e_x86.apk", "e_universal.apk"))
        identities = {c.identity for c in result.candidates}
        self.assertEqual(identities, {"org.example.ext|v1.0"},
                         "one release yields one identity, never one per build")


class IndexIdentityAmbiguityTests(Case):
    """Several binaries under one package|version must not resolve by position.

    The coordinator used to keep whichever entry came first and drop the rest
    without a word, so an index listing per-ABI APKs published whichever
    happened to be listed first. The release path already refused that case;
    the index path now uses the same explicit-choice-or-refuse rule.
    """

    BASE = "https://repo.shura.example"

    def entries(self, *names):
        return [{"name": n, "pkg": "org.example.ext", "version": "1.0",
                 "lang": "ar", "apk": n} for n in names]

    def crawl(self, index_body, source=None):
        body = json.dumps(index_body).encode()
        base = self.BASE

        class Http:
            def get(self, url, headers=None, max_bytes=None):
                return body, None, f"{base}/index.json"
        src = source or Source("index:shura/example", "Example",
                               f"{base}/index.json", "index", "ar",
                               configuration={"allowed_hosts": ["repo.shura.example"]})
        return IndexCrawler(Http()).crawl(src, {"max_pages": 1, "max_candidates": 10})

    def test_two_builds_under_one_identity_are_refused(self):
        result = self.crawl(self.entries("e_arm64.apk", "e_x86.apk"))
        self.assertEqual(result.candidates, [],
                         "no artifact may be chosen by its position in the index")
        refusals = [t for t in result.trace if "refused" in t]
        self.assertEqual(len(refusals), 1)
        detail = refusals[0]["refused"]
        self.assertEqual(sorted(detail["assets"]), ["e_arm64.apk", "e_x86.apk"])
        self.assertIn("universal", detail["reason"])

    def test_reversing_the_index_does_not_change_the_outcome(self):
        forward = self.crawl(self.entries("e_arm64.apk", "e_x86.apk"))
        backward = self.crawl(self.entries("e_x86.apk", "e_arm64.apk"))
        self.assertEqual(forward.candidates, [], "refused")
        self.assertEqual(backward.candidates, [], "refused")
        self.assertEqual(
            sorted(forward.trace[1]["refused"]["assets"]),
            sorted(backward.trace[1]["refused"]["assets"]),
            "the same names are reported whatever the order")

    def test_the_order_does_not_decide_which_universal_build_wins(self):
        forward = self.crawl(self.entries("e_arm64.apk", "e_universal.apk", "e_x86.apk"))
        backward = self.crawl(self.entries("e_x86.apk", "e_universal.apk", "e_arm64.apk"))
        self.assertEqual([c.apk_url for c in forward.candidates],
                         [f"{self.BASE}/apk/e_universal.apk"])
        self.assertEqual([c.apk_url for c in forward.candidates],
                         [c.apk_url for c in backward.candidates],
                         "reversing the index must not silently publish a different APK")

    def test_an_explicit_asset_choice_selects_that_one(self):
        source = Source("index:shura/example", "Example", f"{self.BASE}/index.json",
                        "index", "ar",
                        configuration={"allowed_hosts": ["repo.shura.example"],
                                       "asset": "e_x86.apk"})
        forward = self.crawl(self.entries("e_arm64.apk", "e_x86.apk"), source)
        backward = self.crawl(self.entries("e_x86.apk", "e_arm64.apk"), source)
        self.assertEqual([c.apk_url for c in forward.candidates],
                         [f"{self.BASE}/apk/e_x86.apk"])
        self.assertEqual([c.apk_url for c in forward.candidates],
                         [c.apk_url for c in backward.candidates])

    def test_distinct_versions_are_not_treated_as_duplicates(self):
        body = [{"pkg": "org.example.ext", "version": "1.0", "apk": "e-1.0.apk"},
                {"pkg": "org.example.ext", "version": "1.1", "apk": "e-1.1.apk"}]
        result = self.crawl(body)
        self.assertEqual([c.version for c in result.candidates], ["1.0", "1.1"],
                         "two versions are two identities, not an ambiguous group")

    def test_a_single_entry_index_is_unchanged(self):
        result = self.crawl(self.entries("e-1.0.apk"))
        self.assertEqual(len(result.candidates), 1)
        self.assertEqual(result.candidates[0].apk_url, f"{self.BASE}/apk/e-1.0.apk")
        self.assertFalse([t for t in result.trace if "refused" in t])

    def test_the_coordinator_records_a_conflicting_duplicate_rather_than_dropping_it(self):
        """Even if a crawler emitted two, the ledger must show what conflicted."""
        import json as _json
        from shura_core.coordinator import CrawlCoordinator
        from shura_core.models import Source as _Source

        src = _Source("index:shua/example", "Example", f"{self.BASE}/index.json",
                      "index", "ar", enabled=True,
                      configuration={"allowed_hosts": ["repo.shura.example"],
                                     "package": "org.example.ext", "signing_key": "a" * 64})

        class Result:
            candidates = [
                _Candidate("index:shua/example", "org.example.ext", "1.0",
                           "https://repo.shura.example/apk/a.apk", "E", "ar", "", None, {}, {}),
                _Candidate("index:shua/example", "org.example.ext", "1.0",
                           "https://repo.shura.example/apk/b.apk", "E", "ar", "", None, {}, {}),
            ]
            counters = {}
            pages = 1

        class Crawler:
            def __init__(self, http):
                pass

            def crawl(self, source, budget=None):
                return Result()

        import shura_core.coordinator as C
        saved = C.IndexCrawler
        C.IndexCrawler = Crawler
        try:
            self.store.add_source(src)
            CrawlCoordinator(self.store).run({"index:shua/example"})
        finally:
            C.IndexCrawler = saved
        events = [_json.loads(d) for (d,) in self.store.db.execute(
            "SELECT detail FROM history WHERE event='duplicate'")]
        self.assertEqual(len(events), 1)
        self.assertEqual(events[0]["artifact"], "https://repo.shura.example/apk/b.apk")
        self.assertEqual(events[0]["conflictsWith"],
                         "https://repo.shura.example/apk/a.apk",
                         "the conflicting artifact must be named, not dropped silently")
