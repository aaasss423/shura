"""Regressions found in review, before push.

Each test here corresponds to a defect that reached a working tree and would
have shipped: two in code added this session, one in code it touched. They are
written against the behaviour that was wrong, so they fail on the old code.
"""
import unittest
from pathlib import Path

from shura_core.content.htmlsite import HtmlCatalogueSource, SiteDescriptor
from shura_core.extension.converter import build_plan
from shura_core.security.artifacts import _resolved
from shura_core.security.network import is_private_host


def _reader(html, host="site.example", catalogue="https://site.example/c"):
    class F:
        def get(self, url, max_bytes=None):
            return (html, {"Content-Type": "text/html"}, url)
    return HtmlCatalogueSource(
        SiteDescriptor("s", "n", catalogue_url=catalogue, host=host), F())


class PrivateHostTests(unittest.TestCase):
    """A URL can spell a private address without spelling it directly."""

    def test_an_ipv4_address_wearing_an_ipv6_hat_is_private(self):
        # ::ffff:127.0.0.1 is loopback, and urlparse hands it back whole.
        self.assertTrue(is_private_host("::ffff:127.0.0.1"))
        self.assertTrue(is_private_host("::ffff:169.254.169.254"))
        self.assertTrue(is_private_host("::ffff:10.0.0.1"))

    def test_a_whole_number_ip_is_private(self):
        self.assertTrue(is_private_host("2130706433"))     # 127.0.0.1
        self.assertTrue(is_private_host("0x7f000001"))      # 127.0.0.1
        self.assertTrue(is_private_host("017700000001"))    # 127.0.0.1

    def test_cloud_metadata_is_private_by_name(self):
        self.assertTrue(is_private_host("metadata.google.internal"))

    def test_an_ordinary_host_is_not_private(self):
        for host in ("cdn.other.net", "images.site.com", "8.8.8.8",
                     "2606:4700::1111"):
            self.assertFalse(is_private_host(host), host)


class PageImageTests(unittest.TestCase):
    """Site chrome is not a reading page."""

    HTML = (b'<script src="https://site.example/js/reader.js"></script>'
            b'<img src="https://site.example/static/logo.png">'
            b'<img data-src="https://cdn.other.net/ar/img/1-aaa.jpg">'
            b'<img data-src="https://cdn.other.net/ar/img/2-bbb.jpg">'
            b'<a href="/ar/img/3-ccc.jpg">3</a>')

    def test_a_script_is_never_a_reading_page(self):
        # The src-family detector had no extension filter, so every <script src>
        # on a chapter page came back as page 1.
        pages = _reader(self.HTML).pages({"url": "https://site.example/c"})
        self.assertNotIn("https://site.example/js/reader.js",
                         [p["url"] for p in pages])

    def test_a_lazily_loaded_page_on_another_host_is_still_a_page(self):
        pages = _reader(self.HTML).pages({"url": "https://site.example/c"})
        self.assertIn("https://cdn.other.net/ar/img/1-aaa.jpg",
                      [p["url"] for p in pages])

    def test_a_private_address_is_refused_whatever_it_is_spelled_as(self):
        html = (b'<img data-src="http://[::ffff:127.0.0.1]/a.jpg">'
                b'<img data-src="http://169.254.169.254/latest/meta-data/x.jpg">'
                b'<img data-src="https://cdn.other.net/ar/img/1-aaa.jpg">')
        urls = [p["url"] for p in _reader(html).pages({"url": "https://site.example/c"})]
        self.assertEqual(urls, ["https://cdn.other.net/ar/img/1-aaa.jpg"])

    def test_the_image_host_is_the_one_serving_the_pages_not_the_logo(self):
        # The runtime rejects any page whose host differs from imageHost, so
        # pinning it to the logo's host would make the extension read nothing.
        pages = ["https://site.example/static/logo.png",
                 "https://cdn.other.net/ar/img/1-aaa.jpg",
                 "https://cdn.other.net/ar/img/2-bbb.jpg",
                 "https://cdn.other.net/ar/img/3-ccc.jpg"]
        plan = build_plan(
            _descriptor(), observed_hrefs=["/ar/manga/alpha/", "/ar/manga/beta/",
                                          "/ar/manga/gamma/", "/ar/manga/delta/"],
            observed_page_images=pages, page_images_observed=True,
            work_url="https://site.example/ar/manga/alpha/", observed_chapter_urls=[])
        self.assertEqual(plan.page_image_prefix, "/ar/img")
        self.assertEqual(plan.image_host, "cdn.other.net")


def _descriptor():
    return SiteDescriptor("site:site.example", "E",
                          catalogue_url="https://site.example/ar/manga/alpha/",
                          host="site.example")


class ToolResolutionTests(unittest.TestCase):
    def test_a_given_build_tools_still_resolves_the_jdk(self):
        # The CLI always passes a build-tools literal. An early return here lost
        # the JDK, and apksigner then failed on every APK as a bad signature.
        tools, jdk = _resolved("/opt/android-sdk/build-tools/35.0.0", None)
        self.assertEqual(tools, "/opt/android-sdk/build-tools/35.0.0")
        self.assertIsNotNone(jdk, "JDK discovery must not be skipped")


if __name__ == "__main__":
    unittest.main()