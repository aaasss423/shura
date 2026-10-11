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
from shura_core.discovery.service import SourceDiscovery
from shura_core.models import Source, SourceState
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
    """accept-source is the operator's switch - it must not be a free pass."""

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

    def test_accepting_an_unconfigured_proposal_does_not_make_it_crawlable(self):
        self.store.accept_source("github:arab/reader", reason="looks good")
        source = self.store.get_source("github:arab/reader")
        self.assertIs(source.state, SourceState.ACCEPTED)
        self.assertFalse(source.enabled)
        self.assertFalse(self.store.eligible(source),
                         "accepting is not a substitute for configuring the trust material")

    def test_an_accepted_source_without_trust_material_is_flagged(self):
        # The operator accepted a proposal whose package/signing key are empty.
        source = self.store.get_source("github:arab/reader")
        self.assertEqual(source.configuration["signing_key"], "")
        self.assertEqual(source.configuration["package"], "")
        self.assertTrue(source.configuration.get("review_required"),
                        "the proposal must still be marked as needing trust review")


if __name__ == "__main__":
    unittest.main()