"""Contract guards for Shura's first standalone extension module.

These do not build anything - Gradle and the Android SDK are not part of the
CI test environment. They assert the things that would silently break the
extension contract if someone edited a Gradle file: that the module builds an
APK rather than an AAR, that it declares only Shura's own API, that it does
not reach for Tachiyomi or Mihon, and that its manifest carries the two keys
`ExtensionLoader` and `ShuraRepositoryClient` resolve at runtime.
"""

import re
import unittest
from pathlib import Path

ANDROID = Path(__file__).resolve().parents[1] / "android"
MODULE = ANDROID / "shura-extension-mangadex"

#: Substrings that would mean the extension leaned on another project's code.
FORBIDDEN = ("tachiyomi", "mihon", "keiyoushi", "eu.kanade")


class ExtensionModuleTests(unittest.TestCase):

    def read(self, *parts):
        return Path(*parts).read_text(encoding="utf-8")

    @staticmethod
    def code_only(text):
        """Strip comments so a guard cannot trip on prose explaining itself."""
        text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
        return re.sub(r"//[^\n]*", " ", text)

    # -- it is wired in, and only it -------------------------------
    def test_the_module_is_registered_in_settings(self):
        settings = self.read(ANDROID, "settings.gradle")
        self.assertIn(":shura-extension-mangadex", settings,
                      "the module is not part of the Gradle build")
        self.assertIn("shura-extension-mangadex'", settings)

    def test_it_builds_a_standalone_apk_not_a_library(self):
        gradle = self.read(MODULE, "build.gradle")
        self.assertIn("com.android.application", gradle,
                      "an extension must be an application so it produces an APK")
        self.assertNotIn("com.android.library", gradle)
        self.assertIn("applicationId 'app.shura.extension.mangadex'", gradle)

    def test_it_declares_its_own_package_identity(self):
        gradle = self.read(MODULE, "build.gradle")
        self.assertIn("namespace 'app.shura.extension.mangadex'", gradle)
        self.assertIn("versionCode", gradle)
        self.assertIn("versionName", gradle)

    # -- it uses Shura's own interfaces, and only those -------------
    def test_it_depends_on_shuras_source_api(self):
        gradle = self.read(MODULE, "build.gradle")
        self.assertIn("project(':shura-source-api')", gradle)

    def test_it_declares_no_other_projects_artifacts(self):
        gradle = self.code_only(self.read(MODULE, "build.gradle")).lower()
        for token in FORBIDDEN:
            self.assertNotIn(token, gradle,
                             f"the extension build must not reference {token}")

    def test_it_implements_the_abi_the_loader_actually_loads(self):
        """What ShuraExtensionLoader instantiates, not what it adapts to.

        This test used to assert the extension implemented `ShuraSource` and
        referenced no other project's types. Both were wrong about what the
        loader does: it loads whatever the manifest names, requires it to be a
        `CatalogueSource`, and only then adapts it onto `ShuraSource`. An
        extension satisfying the old rule could never have loaded, however
        correctly it was built.
        """
        source = self.read(
            MODULE, "src/main/java/app/shura/extension/mangadex/MangaDexSource.kt")
        self.assertIn("HttpSource()", source)
        for member in ("override val id", "override val name", "override val lang",
                       "override val baseUrl"):
            self.assertIn(member, source, member)
        # The eight methods ExtensionSourceAdapter calls reflectively.
        for method in ("searchMangaRequest", "searchMangaParse", "mangaDetailsRequest",
                       "mangaDetailsParse", "chapterListRequest", "chapterListParse",
                       "pageListRequest", "pageListParse"):
            self.assertIn(f"fun {method}(", source, method)
            # The transitional ABI declares none of them, so `override` on any
            # of them is a compile error, not a style preference.
            self.assertNotIn(f"override fun {method}(", source, method)

    def test_it_still_serves_shuras_three_operations(self):
        """The contract the app consumes is still implemented, directly."""
        source = self.read(
            MODULE, "src/main/java/app/shura/extension/mangadex/MangaDexSource.kt")
        for member in ("import app.shura.source.MangaSummary", "import app.shura.source.Chapter",
                       "import app.shura.source.Page",
                       "fun search", "fun chapters", "fun pages"):
            self.assertIn(member, source,
                          f"the contract declares {member} and it is not implemented")
        # Only the three operations the contract states; nothing extra claimed.
        # They are no longer `override`s because the class implements the ABI the
        # loader instantiates and serves these alongside it.
        suspends = re.findall(r"suspend\s+fun\s+(\w+)", source)
        self.assertEqual(sorted(suspends), ["chapters", "pages", "search"])

    def test_it_holds_no_credentials(self):
        """MangaDex's public API needs no key, so the extension must not carry one."""
        # Look for an actual credential being supplied, not the word "token"
        # inside a timeout call.
        assigned = re.compile(
            r"(?i)\b(api[_-]?key|password|token|secret|bearer)\b\s*[:=]\s*[^\s]")
        for path in (MODULE / "src/main/java").rglob("*.kt"):
            text = self.code_only(path.read_text(encoding="utf-8"))
            found = assigned.findall(text)
            self.assertEqual(found, [],
                             f"{path.name} appears to carry a credential: {found}")

    # -- bugs found by running it, guarded so they cannot come back ----
    def test_the_extension_lib_is_declared_as_a_number_aapt2_will_keep_numeric(self):
        """A bare "1" is encoded as a boolean and the reader then refuses it.

        aapt2 stores `android:value="1"` as TYPE_INT_BOOLEAN, which
        ApkManifestReader renders as "true"; ShuraRepositoryClient then finds
        "true" is not a number and rejects the extension. The reader has an
        explicit TYPE_FLOAT branch for this field, so the manifest must supply
        a float.
        """
        manifest = self.read(MODULE, "src/main/AndroidManifest.xml")
        match = re.search(r'tachiyomix\.extensionLib"\s+android:value="([^"]+)"', manifest)
        self.assertIsNotNone(match, "the manifest declares no extensionLib value")
        value = match.group(1)
        self.assertRegex(value, r"^\d+\.\d+$",
                         f"extensionLib '{value}' must be a decimal literal so aapt2 "
                         "encodes TYPE_FLOAT rather than a boolean")

    def test_search_encodes_its_query(self):
        """A bare space in a URL makes URL() throw and the search returns nothing."""
        source = self.read(
            MODULE, "src/main/java/app/shura/extension/mangadex/MangaDexSource.kt")
        self.assertIn("URLEncoder.encode", source,
                      "the title must be percent-encoded before it goes into the URL")
        self.assertNotIn("?title=$query", source,
                         "an unencoded query silently returns no results")

    def test_the_localized_title_map_is_read_as_a_map(self):
        """MangaDex returns title as a per-language map, not a string."""
        source = self.read(
            MODULE, "src/main/java/app/shura/extension/mangadex/MangaDexSource.kt")
        # The title is read through the helper that knows it is a map; a direct
        # string read of it yields blank titles for every result.
        self.assertIn("title(attributes)", source)
        self.assertIn('titles.str(lang)', source)
        self.assertIn("obj(\"title\")", source)

    def test_page_filenames_come_from_the_chapter_object(self):
        """at-home returns chapter as an object holding hash and a data array."""
        source = self.read(
            MODULE, "src/main/java/app/shura/extension/mangadex/MangaDexSource.kt")
        self.assertIn('root.obj("chapter")', source)
        self.assertNotIn('root.arr("chapter")', source,
                         "chapter is an object, not an array of filenames")

    # -- it satisfies what the runtime resolves at load time --------
    def test_the_manifest_declares_the_class_the_loader_resolves(self):
        manifest = self.read(MODULE, "src/main/AndroidManifest.xml")
        self.assertIn("tachiyomi.extension.class", manifest)
        self.assertIn("app.shura.extension.mangadex.MangaDexSource", manifest)

    def test_the_manifest_declares_the_extension_lib_the_client_compares(self):
        manifest = self.read(MODULE, "src/main/AndroidManifest.xml")
        self.assertIn("tachiyomix.extensionLib", manifest)

    def test_the_declared_class_is_one_the_module_actually_defines(self):
        manifest = self.read(MODULE, "src/main/AndroidManifest.xml")
        match = re.search(r'android:value="(app\.shura\.[\w.]+)"', manifest)
        self.assertIsNotNone(match, "the manifest names no concrete source class")
        relative = match.group(1).replace(".", "/") + ".kt"
        self.assertTrue((MODULE / "src/main/java" / relative).is_file(),
                        f"{match.group(1)} is named in the manifest but not defined")

    # -- it leaves the existing modules alone -----------------------
    def test_existing_modules_are_unchanged_by_this_one(self):
        runtime = self.read(ANDROID, "shura-extension-runtime/build.gradle")
        self.assertIn("com.android.library", runtime,
                      "the runtime must still be a library")
        host = self.read(ANDROID, "shura-source-host/build.gradle")
        self.assertIn("com.android.library", host)


if __name__ == "__main__":
    unittest.main()