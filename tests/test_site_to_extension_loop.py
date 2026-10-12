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

    def test_the_entry_shape_is_derived_from_the_links_that_were_seen(self):
        self.assertEqual(derive_entry_shape(OBSERVED), r"/index\.php/ar/node/\d+")

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

    def test_a_site_with_no_page_images_is_refused(self):
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
        # hand-written extension wearing a generated name.
        self.assertLess(len(source.splitlines()), 45, source)

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