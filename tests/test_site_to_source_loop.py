"""The loop that was missing: an accepted site becomes readable content.

Discovery proved something about a site and an operator accepted it. Before
this, nothing implemented *reading* it: no works, no chapters, no pages ever
reached the pipeline, so an accepted Arabic site was inert. These tests drive
that link using the project's own ingest, so the manifest shape is validated by
the existing code rather than by a fixture shaped to match.
"""

import json
import unittest
from unittest import mock
from pathlib import Path

from shura_core.content import htmlsite
from shura_core.content import HtmlCatalogueSource, SiteDescriptor, open_store
from shura_core.content.fetcher import load_source_config, sync_source_policy, upsert_source_entry
from shura_core.content.ingest import import_payload
from shura_core.security.network import SafeHTTP


INDEX_HTML = """<!doctype html><html lang="ar" dir="rtl"><body>
<h1>فهرس الأعمال</h1>
<a href="/ar/manga/alpha/">عمل ألفا</a>
<a href="/ar/manga/beta/">عمل بيتا</a>
</body></html>"""

WORK_HTML = """<!doctype html><html lang="ar" dir="rtl"><body>
<a href="/ar/manga/alpha/ch-1/">فصل ١</a>
<a href="/ar/manga/alpha/ch-2/">فصل ٢</a>
</body></html>"""

CHAPTER_HTML = """<!doctype html><html><body>
<a href="/ar/img/1-aaa.jpg">1</a>
<a href="/ar/img/10-bbb.jpg">10</a>
<a href="/ar/img/2-ccc.jpg">2</a>
</body></html>"""


class FakeHttp:
    """Serves the three page shapes by path. Opens no socket."""

    #: The pages every test can rely on. A test adds to this rather than
    #: replacing it, so extending the fixture cannot unbreak the others.
    DEFAULT = {"": INDEX_HTML,
               "/ar/cat": INDEX_HTML,
               "/ar/manga/alpha/": WORK_HTML,
               "/ar/manga/beta/": WORK_HTML,
               "/ar/manga/alpha/ch-1/": CHAPTER_HTML,
               "/ar/manga/alpha/ch-2/": CHAPTER_HTML}

    def __init__(self, host="site.example", pages=None):
        self.host = host
        self.pages = {**self.DEFAULT, **(pages or {})}

    def get(self, url, headers=None, max_bytes=None):
        host = url.split("/")[2]
        path = url.split(host, 1)[-1].split("?", 1)[0]

        class H:
            def get_content_charset(self_inner):
                return "utf-8"
        return self.pages.get(path, "").encode("utf-8"), H(), url


def _descriptor():
    return SiteDescriptor.from_record({
        "source_id": "site:site.example", "domain": "site.example",
        "url": "https://site.example/ar/cat", "language": "ar",
        "verdict": "CANDIDATE", "hasIndex": True, "hasChapters": True,
        "origin": "search:fake",
    })


class SiteToSourceLoopTests(unittest.TestCase):

    def test_an_accepted_site_yields_works_chapters_and_pages(self):
        source = HtmlCatalogueSource(_descriptor(), FakeHttp())
        works = source.works()
        self.assertEqual([w["title"] for w in works], ["عمل ألفا", "عمل بيتا"])
        self.assertTrue(all(w["id"] and w["url"].startswith("https://") for w in works))

        chapters = source.chapters(works[0])
        self.assertEqual(len(chapters), 2)
        self.assertEqual([c["number"] for c in chapters], [2.0, 1.0],
                         "chapters come back newest first, as the contract expects")

        pages = source.pages(chapters[0])
        self.assertEqual([p["index"] for p in pages], [0, 1, 2],
                         "reading pages are ordered, not in document order")
        self.assertTrue(all(p["url"].startswith("https://site.example/") for p in pages))

    def test_the_manifest_is_accepted_by_the_projects_own_ingest(self):
        import tempfile
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        config = Path(tmp.name) / "content-sources.json"
        upsert_source_entry(config, {
            "source_id": "site:site.example", "kind": "html",
            "allowed_hosts": ["site.example"], "fetch_enabled": False,
            "quota_bytes": 0, "retention_days": 7})
        conn = open_store(Path(tmp.name) / "content.db")
        self.addCleanup(conn.close)
        sync_source_policy(conn, load_source_config(config))

        source = HtmlCatalogueSource(_descriptor(), FakeHttp())
        manifest = source.manifest(works=2, chapters_per_work=2, pages_per_chapter=3)
        self.assertEqual(manifest["version"], 1)
        self.assertEqual(manifest["source_id"], "site:site.example")

        result = import_payload(conn, manifest)      # the real validator
        self.assertEqual(result["source_id"], "site:site.example")
        self.assertEqual(result.get("works"), 2, result)
        self.assertEqual(result.get("work_chapters"), 4, result)
        self.assertEqual(result.get("chapter_pages"), 12, result)
        self.assertEqual(conn.execute("SELECT COUNT(*) FROM works").fetchone()[0], 2)
        self.assertEqual(conn.execute("SELECT COUNT(*) FROM chapter_pages").fetchone()[0], 12)

    def test_a_site_without_evidence_is_refused_with_a_reason(self):
        for record, reason in (
            ({"source_id": "site:x.example", "domain": "x.example",
              "url": "http://x.example/cat"}, "not HTTPS"),
            ({"source_id": "site:x.example", "domain": "x.example"}, "no catalogue URL"),
            ({"source_id": "site:x.example", "domain": "x.example",
              "url": "https://x.example/cat", "language": "arabic!"}, "not a language tag"),
        ):
            ok, why = SiteDescriptor.from_record(record).usable()
            self.assertFalse(ok, record)
            self.assertIn(reason, why)

    def test_the_source_never_leaves_the_proven_host(self):
        off_host = ('<!doctype html><html><body>'
                    '<a href="https://elsewhere.example/ar/manga/gamma/">خارجي</a>'
                    '<a href="/ar/manga/delta/">داخلي</a></body></html>')
        http = FakeHttp()

        def get(url, headers=None, max_bytes=None):
            if "gamma" in url:
                raise AssertionError("fetched a link on another host")
            return INDEX_HTML.encode(), type("H", (), {"get_content_charset": lambda s: "utf-8"})(), url
        source = HtmlCatalogueSource(_descriptor(), get)
        works = source.works()
        self.assertTrue(all("elsewhere.example" not in w["url"] for w in works))

    def test_the_number_of_requests_per_pass_is_bounded(self):
        source = HtmlCatalogueSource(_descriptor(), FakeHttp())
        for _ in range(10):
            source.works()
            source.pages({"url": "https://site.example/ar/manga/alpha/ch-1/"})
        self.assertLessEqual(source.pages_fetched, MAX_PAGES)

    def test_a_refusing_site_yields_nothing_rather_than_guessing(self):
        class Refusing:
            def get(self, url, headers=None, max_bytes=None):
                raise OSError("403 Forbidden")
        source = HtmlCatalogueSource(_descriptor(), Refusing())
        self.assertEqual(source.works(), [])
        self.assertEqual(source.manifest(), {"version": 1, "source_id": "site:site.example",
                                            "works": [], "chapters": [], "pages": []})


MAX_PAGES = HtmlCatalogueSource(  # mirror the module's bound for the assertion
    SiteDescriptor(source_id="s", name="n", host="h", catalogue_url="https://h/"), None).MAX_PAGES \
    if hasattr(HtmlCatalogueSource, "MAX_PAGES") else 12


if __name__ == "__main__":
    unittest.main()


# The shapes a real Arabic catalogue actually served, taken from
# www.mangaarabia.com/index.php/ar/node. Its index links to *chapter* nodes
# whose text names the work, so taking that text as a title produced works
# called "الولد الضب - الفصل 24" and every chapter looked like a new manga.
MIXED_INDEX_HTML = """<!doctype html><html lang="ar" dir="rtl"><body>
<a href="/index.php/ar/node/11994">الطريق إلى 2034</a>
<a href="/index.php/ar/node/10000">الولد الضب</a>
<a href="/index.php/ar/node/13026">الولد الضب - الفصل 32</a>
<a href="/index.php/ar/node/13101">الولد الضب - الفصل 33</a>
<a href="/index.php/ar/node/13604">الولد&nbsp;الضب - الفصل 37</a>
<a href="/index.php/ar/node/13671">الولد الضب - الفصل 37.5</a>
</body></html>"""

TITLED_WORK_HTML = """<!doctype html><html lang="ar" dir="rtl"><body>
<h1>الولد الضب</h1>
</body></html>"""


class WorkAndChapterAreDifferentThings(unittest.TestCase):
    """A catalogue link is not a work, and a chapter page is not a work page."""

    def _source(self, work_html=TITLED_WORK_HTML):
        descriptor = SiteDescriptor(source_id="site:site.example", name="Site",
                                    language="ar", catalogue_url="https://site.example/ar/node",
                                    host="site.example", origin="https://site.example")
        return HtmlCatalogueSource(descriptor, FakeHttp(pages={
            "/ar/node": MIXED_INDEX_HTML,
            "/index.php/ar/node/10000": work_html}))

    def test_chapter_links_are_grouped_under_their_work_not_listed_as_works(self):
        titles = [w["title"] for w in self._source().works(limit=10)]
        self.assertIn("الولد الضب", titles)
        self.assertNotIn("الولد الضب - الفصل 32", titles)
        self.assertFalse([t for t in titles if "الفصل" in t], titles)

    def test_a_non_breaking_space_does_not_fork_a_work_into_two(self):
        """The live index ships one work under two spellings of its own title."""
        titles = [w["title"] for w in self._source().works(limit=10)]
        self.assertEqual(len(titles), len(set(titles)), titles)
        self.assertEqual(len(titles), 2, titles)

    def test_chapters_are_collected_under_the_work_they_belong_to(self):
        work = [w for w in self._source().works(limit=10)
                if w["title"] == "الولد الضب"][0]
        self.assertEqual([c["number"] for c in work["chapters"]], [37.5, 37.0, 33.0, 32.0])

    def test_the_page_own_heading_outranks_the_index_text(self):
        """The index is a claim; the page is the evidence."""
        work = [w for w in self._source(work_html="<!doctype html><html><body>"
                                               "<h1>اسم مختلف</h1></body></html>").works(limit=10)
                if w["chapters"]][0]
        self.assertEqual(work["title"], "اسم مختلف")
        self.assertTrue(work["verified"])

    def test_a_chapter_url_is_never_passed_off_as_the_work_page(self):
        work = [w for w in self._source().works(limit=10) if w["chapters"]][0]
        # The index also links the work's own page, so that is the one used.
        self.assertTrue(work["url"].endswith("/node/10000"), work["url"])
        for chapter in work["chapters"]:
            self.assertNotIn(work["url"], chapter["url"])

    def test_a_work_with_only_chapter_links_reports_why_it_has_no_page(self):
        """A work the catalogue never links directly cannot be read, and says so."""
        source = self._source()
        only_chapters = SiteDescriptor(source_id="s", name="S", language="ar",
                                       catalogue_url="https://site.example/ar/node",
                                       host="site.example")
        works = HtmlCatalogueSource(only_chapters, FakeHttp(pages={
            "/ar/node": """<html><body>
<a href="/index.php/ar/node/2">عمل - الفصل 1</a>
<a href="/index.php/ar/node/3">عمل - الفصل 2</a>
<a href="/index.php/ar/node/4">عمل - الفصل 3</a>
<a href="/index.php/ar/node/5">عمل - الفصل 4</a>
</body></html>"""})).works(limit=10)
        self.assertEqual(len(works), 1, works)
        self.assertEqual(works[0].get("unresolved", ""),
                         "the catalogue lists chapters of this work but no page for the "
                         "work itself, so its details cannot be read")
        self.assertEqual(works[0]["url"], "")

    def test_a_work_left_unopened_by_the_page_budget_says_so(self):
        """An unverified work must not read as a refuted one."""
        descriptor = SiteDescriptor(source_id="s", name="S", language="ar",
                                    catalogue_url="https://site.example/ar/node",
                                    host="site.example", origin="https://site.example")
        http = FakeHttp(pages={"/ar/node": MIXED_INDEX_HTML,
                               "/index.php/ar/node/10000": TITLED_WORK_HTML})
        with mock.patch.object(htmlsite, "MAX_PAGES", 1):
            works = HtmlCatalogueSource(descriptor, http).works(limit=5)
        unverified = [w for w in works if not w.get("verified")]
        self.assertTrue(unverified, works)
        self.assertEqual(unverified[0].get("unverified"),
                         "not opened: this pass reached its page budget")
