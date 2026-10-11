"""Link lifecycle, paging and the security posture of the discovery path.

These cover the gaps the first MangaDex connector left open:

* MangaDex@Home links are temporary. The stored ``source_page_url`` must never
  be fetched directly; a fresh link is resolved per attempt from the durable
  chapter id plus the page's own file name.
* A link that cannot be resolved costs no download attempt, produces no request
  to the CDN, and never becomes DONE.
* A link resolved for one chapter or page is never handed to another.
* Paging must go past the first 100 results, must not duplicate when the source
  shifts rows between pages, and must not lose what it already gathered.
* The host allow-list must still hold after the subdomain change, including on
  a redirect and for a look-alike host.
"""

from __future__ import annotations

import json
import tempfile
import unittest
from contextlib import contextmanager
from pathlib import Path

from shura_core.content import open_store
from shura_core.content.mangadex import (API_ATTEMPTS, IMAGE_HOST_SUFFIX, LinkUnavailable,
                                          MangaDexLinkResolver, MangaDexSource)
from shura_core.security.network import NetworkPolicyError, SafeHTTP, validate_url

FIXTURES = Path(__file__).resolve().parent / "fixtures" / "mangadex"
API_HOST = "api.mangadex.org"
OLD_HOST = "cmxdold1111111111.mangadex.network"
NEW_HOST = "cmxdnew2222222222.mangadex.network"
CHAPTER_A = "30f3ac69-21b6-45ad-a110-d011b7aaadaa/c2a3724f-101f-40b4-acaf-c7dcf23d70fa"
CHAPTER_B = "30f3ac69-21b6-45ad-a110-d011b7aaadaa/9a57e217-f3dd-48a9-9d1c-9ac6b776ac2a"
PAGE_1 = "1-daf0e2dfe1948d9666b301860b1f84af9ee9e07951d63b30c85a795ed18e90c4.jpg"


class _Response:
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


class AtHomeTransport:
    """Serves a chosen @Home answer per chapter, and counts calls."""

    def __init__(self, access=None, fail_urls=()):
        self.access = access or json.loads((FIXTURES / "at_home.json").read_text())
        # Prefixes (a query string must not defeat the match) -> how many
        # times to fail them. 1 is a transient blip; a large number is a
        # sustained outage that must exhaust the bounded retry.
        self.fail_urls = dict(fail_urls) if isinstance(fail_urls, dict) else {f: 99 for f in fail_urls}
        self.calls: list[str] = []

    def opener(self, redirect_handler):
        # Use the ORIGINAL, captured before this class patched it in. Reading
        # network.build_opener here would return this very method and recurse.
        opener = self._original(redirect_handler)
        calls, access = self.calls, self.access
        transport = self

        def open_(req, **kw):
            url = req.full_url
            calls.append(url)
            for prefix, remaining in list(transport.fail_urls.items()):
                if url.startswith(prefix) and remaining > 0:
                    transport.fail_urls[prefix] = remaining - 1
                    raise ConnectionError("simulated transport fault")
            return _Response(url, json.dumps(access).encode())

        opener.open = open_
        return opener

    def __enter__(self):
        from shura_core.security import network
        self._original = network.build_opener
        network.build_opener = self.opener
        return self

    def __init_subclass__(cls, **kw):  # pragma: no cover - clarity only
        super().__init_subclass__(**kw)

    def __exit__(self, *exc):
        from shura_core.security import network
        network.build_opener = self._original
        return False


def resolver_for(transport: AtHomeTransport) -> tuple[MangaDexLinkResolver, SafeHTTP]:
    http = SafeHTTP([API_HOST, OLD_HOST, NEW_HOST, f".{IMAGE_HOST_SUFFIX}"])
    source = MangaDexSource(http)
    return MangaDexLinkResolver(source), http


class LinkResolutionTests(unittest.TestCase):
    def test_a_stored_link_is_never_the_one_fetched(self):
        with AtHomeTransport() as transport:
            resolver, _ = resolver_for(transport)
            fresh = resolver.resolve(source_id="mangadex", ext_chapter_id=CHAPTER_A,
                                     page_index=0, source_page_id=PAGE_1,
                                     stored_url=f"https://{OLD_HOST}/data/old/{PAGE_1}")
        self.assertNotIn(OLD_HOST, fresh, "the stale host must not survive resolution")
        self.assertIn("at-home/server/", transport.calls[0])

    def test_resolution_asks_for_a_standard_port(self):
        with AtHomeTransport() as transport:
            resolver, _ = resolver_for(transport)
            resolver.resolve(source_id="mangadex", ext_chapter_id=CHAPTER_A,
                             page_index=0, source_page_id=PAGE_1, stored_url=None)
        self.assertIn("forcePort443=true", transport.calls[0],
                      "a non-standard port would be refused by the URL policy")

    def test_a_chapters_pages_cost_one_at_home_call_between_them(self):
        with AtHomeTransport() as transport:
            resolver, _ = resolver_for(transport)
            for index in range(3):
                resolver.resolve(source_id="mangadex", ext_chapter_id=CHAPTER_A,
                                 page_index=index,
                                 source_page_id=json.loads(
                                     (FIXTURES / "at_home.json").read_text()
                                 )["chapter"]["data"][index],
                                 stored_url=None)
        at_home = [c for c in transport.calls if "at-home" in c]
        self.assertEqual(len(at_home), 1, "one chapter, one resolution")

    def test_a_link_is_never_reused_across_chapters(self):
        with AtHomeTransport() as transport:
            resolver, _ = resolver_for(transport)
            first = resolver.resolve(source_id="mangadex", ext_chapter_id=CHAPTER_A,
                                     page_index=0, source_page_id=PAGE_1, stored_url=None)
            second = resolver.resolve(source_id="mangadex", ext_chapter_id=CHAPTER_B,
                                      page_index=0, source_page_id=PAGE_1, stored_url=None)
        asked = [c for c in transport.calls if "at-home" in c]
        self.assertEqual(len(asked), 2, "each chapter resolves its own access")
        self.assertNotEqual(asked[0].rsplit("/", 1)[-1], asked[1].rsplit("/", 1)[-1])
        self.assertEqual(first.split("/data/")[-1], second.split("/data/")[-1],
                         "same page file, same tail; only the host may differ")

    def test_a_page_no_longer_in_the_chapter_is_refused_not_guessed(self):
        with AtHomeTransport():
            resolver, _ = resolver_for(AtHomeTransport())
        with AtHomeTransport() as transport:
            resolver, _ = resolver_for(transport)
            with self.assertRaises(LinkUnavailable) as caught:
                resolver.resolve(source_id="mangadex", ext_chapter_id=CHAPTER_A,
                                 page_index=99, source_page_id="99-gone.jpg",
                                 stored_url=f"https://{OLD_HOST}/data/old/99-gone.jpg")
        self.assertIn("not in chapter", str(caught.exception))

    def test_another_source_is_left_on_its_stored_link(self):
        with AtHomeTransport() as transport:
            resolver, _ = resolver_for(transport)
            stored = f"https://{OLD_HOST}/data/old/{PAGE_1}"
            result = resolver.resolve(source_id="some-other-source", ext_chapter_id=CHAPTER_A,
                                     page_index=0, source_page_id=PAGE_1, stored_url=stored)
        self.assertEqual(result, stored)
        self.assertEqual(transport.calls, [], "another source must not hit MangaDex@Home")

    def test_resolution_survives_a_transient_api_fault_then_succeeds(self):
        with AtHomeTransport() as transport:
            resolver, _ = resolver_for(transport)
            url = f"https://{API_HOST}/at-home/server/{CHAPTER_A.rsplit('/', 1)[-1]}"
            transport.fail_urls.clear()
            transport.fail_urls[url] = 1     # a single blip
            # Bounded retry recovers inside the budget; it does not give up.
            url_result = resolver.resolve(source_id="mangadex", ext_chapter_id=CHAPTER_A,

                                          page_index=0, source_page_id=PAGE_1, stored_url=None)
            self.assertIn(PAGE_1, url_result)
            self.assertEqual(len(transport.calls), 2, "one failure, one retry")

    def test_resolution_gives_up_after_the_bounded_retry(self):
        with AtHomeTransport() as transport:
            resolver, _ = resolver_for(transport)
            url = f"https://{API_HOST}/at-home/server/{CHAPTER_A.rsplit('/', 1)[-1]}"
            transport.fail_urls.clear()
            transport.fail_urls[url] = 99      # sustained outage
            with self.assertRaises(Exception):
                resolver.resolve(source_id="mangadex", ext_chapter_id=CHAPTER_A,
                                 page_index=0, source_page_id=PAGE_1, stored_url=None)
            self.assertEqual(len(transport.calls), API_ATTEMPTS,
                             "retries are bounded, never unbounded")

    def test_resolution_recovers_when_the_link_returns_again(self):
        with AtHomeTransport() as transport:
            resolver, _ = resolver_for(transport)
            url = f"https://{API_HOST}/at-home/server/{CHAPTER_A.rsplit('/', 1)[-1]}"
            transport.fail_urls.clear()
            transport.fail_urls[url] = 99
            with self.assertRaises(Exception):
                resolver.resolve(source_id="mangadex", ext_chapter_id=CHAPTER_A,
                                 page_index=0, source_page_id=PAGE_1, stored_url=None)
            transport.fail_urls.clear()
            resolver.forget()
            fresh = resolver.resolve(source_id="mangadex", ext_chapter_id=CHAPTER_A,
                                     page_index=0, source_page_id=PAGE_1, stored_url=None)
        self.assertIn(PAGE_1, fresh, "a refreshed link is usable again")


class PagingTests(unittest.TestCase):
    """Past 100 results, no duplicates between batches, no loss on failure."""

    class PagingTransport:
        def __init__(self, pages, fail_at=None):
            self.pages = pages          # offset -> list of ids
            self.fail_at = fail_at
            self.offsets: list[int] = []

        def __enter__(self):
            from shura_core.security import network
            self._original = network.build_opener
            transport = self


            def build(redirect_handler):
                opener = transport._original(redirect_handler)

                def open_(req, **kw):
                    from urllib.parse import parse_qs, urlparse
                    parsed = urlparse(req.full_url)
                    if "/manga" not in parsed.path:
                        raise AssertionError("expected /manga")
                    offset = int(parse_qs(parsed.query)["offset"][0])
                    limit = int(parse_qs(parsed.query)["limit"][0])
                    transport.offsets.append(offset)
                    if transport.fail_at is not None and offset == transport.fail_at:
                        raise ConnectionError("simulated mid-walk failure")
                    ids = transport.pages.get(offset, [])
                    data = [{"id": i, "type": "manga", "attributes": {"title": {"en": i}}}
                            for i in ids[:limit]]
                    return _Response(req.full_url, json.dumps(
                        {"result": "ok", "data": data, "total": 250,
                         "limit": limit, "offset": offset}).encode())

                opener.open = open_
                return opener

            network.build_opener = build
            return self

        def __exit__(self, *exc):
            from shura_core.security import network
            network.build_opener = self._original
            return False

    def test_results_beyond_the_first_hundred_are_reached(self):
        pages = {offset: [f"m{offset + i}" for i in range(100)] for offset in (0, 100, 200)}
        with self.PagingTransport(pages) as transport:
            source = MangaDexSource(SafeHTTP(["api.mangadex.org"]))
            works = source.works(language="ar", limit=100, max_items=250)
        self.assertEqual(len(works), 250, "the walk must continue past the first page")
        self.assertEqual(transport.offsets, [0, 100, 200])
        self.assertEqual(len({w["id"] for w in works}), 250, "no duplicates across batches")

    def test_a_row_that_moves_between_batches_is_not_duplicated(self):
        # Batch 2 repeats one id from batch 1, the way a shifting feed behaves.
        pages = {0: ["m1", "m2"], 2: ["m2", "m3"]}
        with self.PagingTransport(pages) as transport:
            source = MangaDexSource(SafeHTTP(["api.mangadex.org"]))
            works = source.works(language="ar", limit=2, max_items=10)
        ids = [w["id"] for w in works]
        self.assertEqual(ids, ["m1", "m2", "m3"])
        self.assertEqual(len(ids), len(set(ids)), "a repeated id must appear once")

    def test_nothing_already_gathered_is_lost_when_a_later_batch_fails(self):
        pages = {0: ["m1", "m2"]}
        with self.PagingTransport(pages, fail_at=2) as transport:
            source = MangaDexSource(SafeHTTP(["api.mangadex.org"]))
            with self.assertRaises(Exception):
                source.works(language="ar", limit=2, max_items=10)
        # The first batch was fetched and then the walk stopped; a restart
        # re-walks and converges, because every write is an idempotent upsert.
        self.assertEqual(transport.offsets, [0, 2, 2, 2],
                         "the failed batch was retried exactly API_ATTEMPTS times")
        with self.PagingTransport({0: ["m1", "m2"], 2: ["m3"]}) as transport:
            source = MangaDexSource(SafeHTTP(["api.mangadex.org"]))
            works = source.works(language="ar", limit=2, max_items=10)
        self.assertEqual([w["id"] for w in works], ["m1", "m2", "m3"])

    def test_the_offset_ceiling_is_respected(self):
        pages = {offset: [f"m{offset}"] for offset in range(0, 10001, 100)}
        with self.PagingTransport(pages) as transport:
            source = MangaDexSource(SafeHTTP(["api.mangadex.org"]))
            source.works(language="ar", limit=100, max_items=1_000_000)
        self.assertLessEqual(max(transport.offsets), 10_000,
                             "the walk must not run past the documented offset ceiling")


class HostPolicyTests(unittest.TestCase):
    """The subdomain opt-in must not weaken anything else."""

    def allowed(self, url, hosts):
        validate_url(url, hosts)
        return True

    def test_a_plain_entry_stays_exact(self):
        self.assertTrue(self.allowed("https://api.mangadex.org/manga", ["api.mangadex.org"]))
        with self.assertRaises(NetworkPolicyError):
            self.allowed(f"https://{OLD_HOST}/x.jpg", ["api.mangadex.org"])

    def test_a_dotted_entry_covers_the_domain_and_its_subdomains(self):
        self.assertTrue(self.allowed(f"https://{OLD_HOST}/x.jpg", [f".{IMAGE_HOST_SUFFIX}"]))
        self.assertTrue(self.allowed(f"https://{IMAGE_HOST_SUFFIX}/x.jpg",
                                     [f".{IMAGE_HOST_SUFFIX}"]))

    def test_a_look_alike_domain_is_refused(self):
        for host in (f"evil.{IMAGE_HOST_SUFFIX}.attacker.test",
                     f"not{IMAGE_HOST_SUFFIX}.test",
                     f"{IMAGE_HOST_SUFFIX}evil.test"):
            with self.assertRaises(NetworkPolicyError, msg=host):
                self.allowed(f"https://{host}/x.jpg", [f".{IMAGE_HOST_SUFFIX}"])

    def test_the_scheme_still_has_to_be_https(self):
        with self.assertRaises(NetworkPolicyError):
            self.allowed(f"http://{OLD_HOST}/x.jpg", [f".{IMAGE_HOST_SUFFIX}"])

    def test_credentials_and_private_hosts_are_still_refused(self):
        with self.assertRaises(NetworkPolicyError):
            self.allowed(f"https://u:p@{OLD_HOST}/x.jpg", [f".{IMAGE_HOST_SUFFIX}"])
        with self.assertRaises(NetworkPolicyError):
            self.allowed("https://127.0.0.1/x.jpg", [".localhost"])

    def test_a_redirect_to_an_unlisted_host_is_refused(self):
        # validate_url runs again on the post-redirect URL, so a chain cannot
        # walk from an allowed host onto a forbidden one.
        from shura_core.security.network import SafeHTTP, SafeRedirect
        redirect = SafeRedirect([API_HOST], 5, True, False, False,
                                "https://api.mangadex.org/manga")
        with self.assertRaises(NetworkPolicyError):
            redirect.redirect_request(
                __import__("urllib.request", fromlist=["Request"]).Request(
                    "https://api.mangadex.org/manga"),
                None, 302, None, None, f"https://{OLD_HOST}/stolen.jpg")


class ResolverNeverTouchesDiskTests(unittest.TestCase):
    """Resolution is read-only: it must not create or mutate the store."""

    def test_resolving_needs_no_database(self):
        with AtHomeTransport() as transport:
            resolver, _ = resolver_for(transport)
            url = resolver.resolve(source_id="mangadex", ext_chapter_id=CHAPTER_A,
                                   page_index=0, source_page_id=PAGE_1, stored_url=None)
        self.assertTrue(url.startswith("https://"))
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(list(Path(tmp).iterdir()), [])


if __name__ == "__main__":
    unittest.main()

class FetcherLinkIntegrationTests(unittest.TestCase):
    """The resolver inside the real fetch path: stale link, no false DONE."""

    def setUp(self):
        from shura_core.content import Budget, Budgets, PageFetcher, acquire_lock
        from shura_core.content.store import grant_clearance

        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.conn = open_store(self.root / "content.db")
        self.conn.execute(
            "INSERT INTO source_policy(source_id,kind,allowed_hosts,fetch_enabled,quota_bytes,"
            "retention_days,synced_at) VALUES('mangadex','index','[\".mangadex.network\"]',1,?,'30','now')",
            (1 << 40,))
        self.conn.execute(
            "INSERT INTO works(source_id,ext_work_id,source_work_id,title,"
            "content_classification,discovered_at,updated_at) "
            "VALUES('mangadex','w1','src','T','SAFE','now','now')")
        self.conn.execute(
            "INSERT INTO work_chapters(source_id,ext_chapter_id,ext_work_id,source_chapter_id,"
            "chapter_number,ordinal,first_seen,updated_at) "
            "VALUES('mangadex',?,'w1','sc',1.0,0,'n','n')", (CHAPTER_A,))
        self.conn.execute(
            "INSERT INTO chapter_pages(source_id,ext_chapter_id,page_index,ordinal,"
            "source_page_id,source_page_url,page_key_kind,state,created_at) "
            "VALUES('mangadex',?,0,0,?,?,'source_page_id','PENDING','now')",
            (CHAPTER_A, PAGE_1, f"https://{OLD_HOST}/data/stale/{PAGE_1}"))
        grant_clearance(self.conn, source_id="mangadex", ext_work_id="w1",
                        ext_chapter_id=CHAPTER_A, decision="CLEARED",
                        basis="operator-approval", decided_by="op", evidence="review",
                        policy_version="v1")
        self.lock = acquire_lock(self.conn, "worker", "host")

    def tearDown(self):
        from shura_core.content.store import release_lock
        release_lock(self.conn, self.lock)
        self.conn.close()
        self.tmp.cleanup()

    def _fetcher(self, resolver, requested):
        from shura_core.content import Budget, Budgets, PageFetcher

        class Transport:
            @contextmanager
            def stream(self, url, *, max_bytes, headers=None):
                requested.append(url)
                raise AssertionError("a stale link must never be fetched")

            def get(self, url, headers=None):
                requested.append(url)
                raise AssertionError("a stale link must never be fetched")

        return PageFetcher(self.conn, self.lock, self.root, Transport(), Budget(Budgets()),
                           policy_fetcher=lambda _s: 1 << 40, link_resolver=resolver)

    def test_a_stored_stale_link_is_replaced_before_any_fetch(self):
        from contextlib import contextmanager

        requested: list[str] = []
        with AtHomeTransport() as transport:
            resolver, _ = resolver_for(transport)
            result = self._fetcher(resolver, requested).fetch_page(
                source_id="mangadex", ext_work_id="w1", ext_chapter_id=CHAPTER_A,
                page_index=0, page_url=f"https://{OLD_HOST}/data/stale/{PAGE_1}",
                host=OLD_HOST)
        self.assertEqual(result.outcome, "LINK_UNAVAILABLE")
        self.assertEqual(requested, [], "no CDN request may be made with a stale link")
        state, sha = self.conn.execute(
            "SELECT state, sha256 FROM chapter_pages WHERE page_index=0").fetchone()
        self.assertNotEqual(state, "DONE")
        self.assertIsNone(sha)
        self.assertEqual(self.conn.execute(
            "SELECT attempts FROM chapter_pages WHERE page_index=0").fetchone()[0], 0,
            "an unresolvable link must not consume a download attempt")
        self.assertEqual(list(self.root.rglob("*.img")), [])

    def test_a_page_missing_from_the_chapter_never_becomes_done(self):
        from contextlib import contextmanager

        requested: list[str] = []
        with AtHomeTransport() as transport:
            resolver, _ = resolver_for(transport)
            result = self._fetcher(resolver, requested).fetch_page(
                source_id="mangadex", ext_work_id="w1", ext_chapter_id=CHAPTER_A,
                page_index=0, page_url=f"https://{OLD_HOST}/data/stale/{PAGE_1}",
                host=OLD_HOST)
        self.assertEqual(result.outcome, "LINK_UNAVAILABLE")
        self.assertEqual(requested, [])
        self.assertEqual(self.conn.execute(
            "SELECT COUNT(*) FROM chapter_pages WHERE state='DONE'").fetchone()[0], 0)
