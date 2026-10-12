"""An accepted site becomes a real Shura extension.

The loop this file covers was missing: discovery proved a site, an operator
accepted it, and the content pipeline could read it - but nothing produced an
artifact Shura's app can load. These tests drive the converter end to end
against the real contract: a manifest the loader's gates accept, a class in the
ABi the loader calls reflectively, and a refusal with a reason when the site was
not actually proven.
"""

import re
import tempfile
import unittest
from pathlib import Path

from shura_core.content import SiteDescriptor
from shura_core.extension.builder import BuildFailure, manifest_metadata
from shura_core.extension.converter import (ExtensionPlan, build_plan,
                                            derive_entry_shape, register_module,
                                            render_module, source_number)

# Observed on www.mangaarabia.com/index.php/ar/node, the live Arabic catalogue.
OBSERVED = [f"/index.php/ar/node/{n}" for n in (13026, 13101, 13604, 13671, 13916)]


def _descriptor(**overrides) -> SiteDescriptor:
    fields = dict(
        source_id="site:www.mangaarabia.com", name="MangaArabia", language="ar",
        catalogue_url="https://www.mangaarabia.com/index.php/ar/node",
        host="www.mangaarabia.com", origin="https://www.mangaarabia.com")
    fields.update(overrides)
    return SiteDescriptor(**fields)


def _plan(**overrides) -> ExtensionPlan:
    kwargs = {"observed_hrefs": OBSERVED}
    kwargs.update(overrides)
    return build_plan(_descriptor(**overrides.pop("descriptor", {}) or {}), **kwargs) \
        if "descriptor" in overrides else build_plan(_descriptor(), **kwargs)


class TheShapeComesFromEvidence(unittest.TestCase):

    def test_the_entry_shape_is_the_directory_the_entries_share(self):
        """Not a digit-masked path.

        A slug carrying an id suffix masks to a different string per work, so a
        masked-path shape turns an index of forty works into forty groups of one
        and the site looks like navigation.
        """
        self.assertEqual(derive_entry_shape(OBSERVED), r"/index\.php/ar/node")
        slugs = [f"/comics/{name}-1bb35e56" for name in
                 ("pick-up-a-grimoire", "solo-swordmaster", "overgeared", "my-dragon")]
        self.assertEqual(derive_entry_shape(slugs), r"/comics")

    def test_the_shape_matches_every_entry_under_that_directory(self):
        shape = derive_entry_shape(["/x/1", "/x/2", "/x/3", "/x/4", "/y/5"])
        self.assertEqual(shape, r"/x")
        self.assertRegex("/x/99", shape)

    def test_two_links_that_happen_to_share_a_parent_are_not_a_listing(self):
        self.assertEqual(derive_entry_shape(["/x/1", "/x/2", "/y/3", "/y/4"]), "")

    def test_links_that_share_no_shape_yield_nothing_rather_than_a_guess(self):
        scattered = ["/a/1", "/b/2", "/c/3", "/d/4"]
        self.assertEqual(derive_entry_shape(scattered), "")

    def test_a_menu_is_not_mistaken_for_a_listing(self):
        # Five links of five different shapes is navigation, not an index.
        plan = build_plan(_descriptor(), observed_hrefs=["/a", "/b", "/c", "/d", "/e"])
        self.assertFalse(plan.publishable)
        self.assertIn("not a listing", plan.reason())

    def test_a_site_with_no_observed_links_is_refused(self):
        plan = build_plan(_descriptor(), observed_hrefs=[])
        self.assertFalse(plan.publishable)
        self.assertIn("no entry link was observed", plan.reason())


class RefusalIsARefusal(unittest.TestCase):

    def test_a_site_the_descriptor_rejects_never_reaches_generation(self):
        plan = build_plan(_descriptor(catalogue_url="http://insecure.example/node"),
                          observed_hrefs=OBSERVED)
        self.assertFalse(plan.publishable)
        with self.assertRaises(ValueError) as caught:
            render_module(plan)
        self.assertIn("refusing", str(caught.exception))

    def test_a_site_with_no_catalogue_is_refused(self):
        plan = build_plan(_descriptor(catalogue_url=""), observed_hrefs=OBSERVED)
        self.assertFalse(plan.publishable)

    def test_a_site_that_serves_no_reading_images_is_refused(self):
        """The gate that matters most for this site.

        The Arabic catalogue was read and its chapter page served a cover and
        two site logos, and no reading pages. An extension for it would search
        and list chapters perfectly and then show an empty chapter, which the
        reader sees as a broken manga rather than an unsupported site.
        """
        plan = build_plan(_descriptor(), observed_hrefs=OBSERVED, page_images_observed=False)
        self.assertFalse(plan.publishable)
        self.assertIn("needs an extraction mechanism", plan.reason())

    def test_images_that_do_not_match_the_pattern_are_refused(self):
        plan = build_plan(_descriptor(), observed_hrefs=OBSERVED,
                          observed_page_images=["https://site.example/page.php?id=9"])
        self.assertFalse(plan.publishable)
        self.assertIn("did not match", plan.reason())

    def test_images_that_are_only_cover_and_logo_do_not_become_a_site(self):
        """Why the explicit gate exists rather than the pattern doing the work.

        Every one of these matches the image pattern and none is a reading page.
        A chapter that served only these has no reading sequence, so the site is
        refused rather than published as an extension that opens onto a cover.
        """
        from shura_core.extension.converter import PAGE_IMAGE, build_plan as plan_for
        chrome = ["https://site.example/covers/work.abc-400.jpg",
                  "https://site.example/covers/work.def-400.jpg",
                  "https://site.example/assets/youth_logo.png"]
        for src in chrome:
            self.assertRegex(src, PAGE_IMAGE)
        plan = plan_for(_descriptor(), observed_hrefs=OBSERVED, observed_page_images=chrome)
        self.assertFalse(plan.publishable)
        self.assertIn("share no path that identifies the chapter", plan.reason())

    def test_pages_are_found_by_the_directory_they_share_not_by_extension(self):
        from shura_core.extension.converter import PAGE_IMAGE, build_plan as plan_for
        work = "https://site.example/comics/a-work-1bb35e56"
        pages = [f"https://cdn.site.example/img/chapters/a-work/7/{h}.webp"
                 for h in ("a", "b", "c", "d", "e")]
        covers = ["https://cdn.site.example/img/covers/a-work.abc-400.webp"]
        plan = plan_for(_descriptor(), observed_hrefs=OBSERVED,
                        observed_page_images=pages + covers,
                        chapter_url=work + "/chapter/7", work_url=work)
        self.assertTrue(plan.publishable, plan.reason())
        self.assertEqual(plan.page_image_prefix, r"/img/chapters/[^/]+/\d+")
        self.assertEqual(plan.image_host, "cdn.site.example")
        for cover in covers:
            self.assertRegex(cover, PAGE_IMAGE)

    def test_a_site_with_no_page_image_pattern_is_refused(self):
        plan = build_plan(_descriptor(), observed_hrefs=OBSERVED, page_image="")
        self.assertFalse(plan.publishable)
        self.assertIn("no reading pages", plan.reason())


class TheGeneratedModuleIsReal(unittest.TestCase):

    def setUp(self):
        self.plan = build_plan(_descriptor(), observed_hrefs=OBSERVED)
        self.files = render_module(self.plan)

    def test_it_generates_the_three_files_a_module_needs(self):
        self.assertIn("build.gradle", self.files)
        self.assertIn("src/main/AndroidManifest.xml", self.files)
        sources = [k for k in self.files if k.endswith(".kt")]
        self.assertEqual(len(sources), 1, sources)

    def test_the_generated_class_implements_the_abi_the_loader_calls_reflectively(self):
        source = next(v for k, v in self.files.items() if k.endswith(".kt"))
        # The loader rejects anything that is not a CatalogueSource and calls
        # eight methods by name. Declaring ShuraSource instead is what made the
        # first hand-written extension unloadable, so the generated class must
        # extend the engine that implements the ABI.
        self.assertIn("SiteCatalogueSource()", source)
        self.assertNotIn("ShuraSource", source)
        self.assertIn("override val baseUrl", source)

    def test_the_engine_is_what_declares_the_eight_abi_methods(self):
        """The reusable half, checked in the module that actually carries it."""
        engine = (Path(__file__).resolve().parents[1] / "android" / "shura-source-site"
                  / "src/main/java/app/shura/source/site/SiteCatalogueSource.kt")
        text = engine.read_text(encoding="utf-8")
        self.assertIn("HttpSource()", text)
        for method in ("searchMangaRequest", "searchMangaParse", "mangaDetailsRequest",
                       "mangaDetailsParse", "chapterListRequest", "chapterListParse",
                       "pageListRequest", "pageListParse"):
            self.assertIn(f"fun {method}(", text, method)
            # None of them may be `override`: the transitional ABI declares
            # none of them, and the loader reaches them by reflection.
            self.assertNotIn(f"override fun {method}(", text, method)

    def test_the_manifest_declares_both_keys_the_loader_resolves(self):
        manifest = self.files["src/main/AndroidManifest.xml"]
        self.assertIn("tachiyomi.extension.class", manifest)
        self.assertIn("tachiyomix.extensionLib", manifest)
        self.assertIn(self.plan.namespace + "." + self.plan.class_name, manifest)

    def test_the_extension_lib_is_a_decimal_literal_not_a_boolean(self):
        manifest = self.files["src/main/AndroidManifest.xml"]
        value = re.search(r'extensionLib" android:value="([^"]+)"', manifest).group(1)
        self.assertEqual(value, "1.0")
        # A bare 1 is encoded TYPE_INT_BOOLEAN and the reader is handed "true".
        self.assertNotEqual(value, "1")

    def test_the_package_is_a_java_identifier(self):
        self.assertNotIn("-", self.plan.package)
        for segment in self.plan.package.split("."):
            self.assertRegex(segment, r"^[a-z][a-z0-9]*$")

    def test_the_site_specifics_are_data_and_the_logic_is_not(self):
        source = next(v for k, v in self.files.items() if k.endswith(".kt"))
        self.assertIn("entryHref", source)
        self.assertIn("chapterMarker", source)
        self.assertIn("pageImage", source)
        # The reading rules live in android/shura-source-site and are not
        # regenerated per site; a generated file that carried them would be a
        # hand-written extension wearing a generated name. It is a spec, not
        # code: no parsing, no requests, no page handling of its own.
        body = source.split("SiteSpec(")[-1]
        for forbidden in ("findAll", "substring", "indexOf", "okhttp", "Request(",
                          "while (", "for ("):
            self.assertNotIn(forbidden, body, forbidden)
        for required in ("entryHref", "chapterHref", "chapterMarker",
                         "pageImagePrefix", "imageHost", "pageImage"):
            self.assertIn(required, source, required)

    def test_the_source_id_is_a_stable_number_not_a_string(self):
        source = next(v for k, v in self.files.items() if k.endswith(".kt"))
        self.assertRegex(source, r"sourceId = \d+")
        self.assertEqual(source_number("site:a"), source_number("site:a"))
        self.assertNotEqual(source_number("site:a"), source_number("site:b"))
        self.assertGreater(source_number("site:a"), 0)


class RegistrationIsIdempotent(unittest.TestCase):

    def test_registering_twice_changes_nothing_the_second_time(self):
        with tempfile.TemporaryDirectory() as tmp:
            settings = Path(tmp) / "settings.gradle"
            settings.write_text(
                "pluginManagement { repositories { google() } }\n"
                "rootProject.name = 'Shura'\n"
                "include ':app', ':shura-extension-mangadex'\n"
                "project(':app').projectDir = file('app')\n", encoding="utf-8")
            self.assertTrue(register_module(settings, "shura-extension-generated"))
            once = settings.read_text(encoding="utf-8")
            self.assertFalse(register_module(settings, "shura-extension-generated"))
            self.assertEqual(once, settings.read_text(encoding="utf-8"))
            self.assertIn(":shura-extension-generated'", once)
            self.assertIn("project(':shura-extension-generated')", once)


class TheLoadGates(unittest.TestCase):
    """The two ExtensionLoader failures that can be ruled out off-device.

    ``load()`` resolves the manifest's class through a DexClassLoader and then
    refuses anything that is not a CatalogueSource. Both need the dex, so a
    manifest that names a class nobody packaged only fails when it reaches a
    phone.
    """

    @staticmethod
    def _apk(descriptor: str) -> bytes:
        """A dex carrying one class descriptor and nothing else.

        Hand-built to the minimum ``_check_dex`` reads: a string_ids table of
        one entry pointing at a single string_data_item. Nothing else in the
        dex is validated, and nothing else needs to be.
        """
        import struct

        def uleb(value: int) -> bytes:
            out = bytearray()
            while True:
                byte = value & 0x7F
                value >>= 7
                out.append(byte | (0x80 if value else 0))
                if not value:
                    return bytes(out)

        # The header is a fixed 112 bytes and the parser reads string_ids_size
        # and string_ids_off from byte 56 of it, so they have to be written
        # there rather than left as padding.
        header_size = 112
        table_offset = header_size
        header = (b"dex\n038\x00" + b"\x00" * (56 - 8)
                  + struct.pack("<II", 1, table_offset)
                  + b"\x00" * (header_size - 64))
        table = struct.pack("<I", table_offset + 4)    # the one string offset
        data = uleb(len(descriptor)) + descriptor.encode("utf-8") + b"\x00"
        return header + table + data

    def _write(self, descriptors: list[str]) -> Path:
        import io
        import zipfile
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as archive:
            for index, descriptor in enumerate(descriptors):
                archive.writestr(f"classes{index}.dex", self._apk(descriptor))
        path = Path(self.tmp.name) / "a.apk"
        path.write_bytes(buffer.getvalue())
        return path

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)

    def test_the_named_class_must_be_in_the_dex(self):
        from shura_core.extension.builder import BuildFailure, _check_dex
        path = self._write(["Lapp/shura/extension/other/OtherSource;"])
        with self.assertRaises(BuildFailure) as caught:
            _check_dex(path, "app.shura.extension.mangaarabia.MangaArabiaSource")
        self.assertIn("no dex", str(caught.exception))

    def test_packaged_abi_types_are_reported_rather_than_assumed_absent(self):
        """The Android Gradle Plugin packages a compileOnly *library*.

        An extension that implements the ABI needs those types at compile time,
        and they end up in the APK. Parent-first delegation means the host's
        copies still win, so this is bloat and a latent coupling rather than a
        failure - but it is reported, not passed over in silence.
        """
        from shura_core.extension.builder import _check_dex
        path = self._write([
            "Lapp/shura/extension/mangadex/MangaDexSource;",
            "Leu/kanade/tachiyomi/source/online/HttpSource;",
            "Leu/kanade/tachiyomi/source/model/SManga;",
        ])
        self.assertEqual(
            2, _check_dex(path, "app.shura.extension.mangadex.MangaDexSource"))

    def test_the_shape_a_correctly_built_extension_has_passes(self):
        from shura_core.extension.builder import _check_dex
        path = self._write([
            "Lapp/shura/extension/mangaarabia/MangaArabiaSource;",
            "Lapp/shura/source/site/SiteCatalogueSource;",
        ])
        self.assertEqual(
            0, _check_dex(path, "app.shura.extension.mangaarabia.MangaArabiaSource"))


class ReadingTheCompiledManifest(unittest.TestCase):
    """The type check aapt2's own dump cannot make.

    ``aapt2 dump xmltree`` prints a float and a boolean the same way, so a
    manifest whose extensionLib was encoded as TYPE_INT_BOOLEAN looks identical
    to one that is a proper float. Only the compiled bytes tell them apart.
    """

    def test_a_manifest_that_is_not_a_compiled_binary_manifest_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            apk = Path(tmp) / "fake.apk"
            import zipfile
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("AndroidManifest.xml", "<manifest/>")
            with self.assertRaises(BuildFailure):
                manifest_metadata(apk)


if __name__ == "__main__":
    unittest.main()