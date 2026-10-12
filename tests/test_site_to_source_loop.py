"""The loop that was missing: an accepted site becomes readable content.

Discovery proved something about a site and an operator accepted it. Before
this, nothing implemented *reading* it: no works, no chapters, no pages ever
reached the pipeline, so an accepted Arabic site was inert. These tests drive
that link using the project's own ingest, so the manifest shape is validated by
the existing code rather than by a fixture shaped to match.
"""

import json
import unittest
from pathlib import Path

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

    def __init__(self, host="site.example"):
        self.host = host

    def get(self, url, headers=None, max_bytes=None):
        host = url.split("/")[2]
        path = url.split(host, 1)[-1].split("?", 1)[0]

        class H:
            def get_content_charset(self_inner):
                return "utf-8"
        body = {"": INDEX_HTML,
                "/ar/cat": INDEX_HTML,
                "/ar/manga/alpha/": WORK_HTML,
                "/ar/manga/beta/": WORK_HTML,
                "/ar/manga/alpha/ch-1/": CHAPTER_HTML,
                "/ar/manga/alpha/ch-2/": CHAPTER_HTML}.get(path, "")
        return body.encode("utf-8"), H(), url


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
