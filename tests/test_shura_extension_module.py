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

    def test_its_source_references_no_foreign_types(self):
        sources = list((MODULE / "src/main/java").rglob("*.kt"))
        self.assertTrue(sources, "the module has no Kotlin source")
        for path in sources:
            text = self.code_only(path.read_text(encoding="utf-8")).lower()
            for token in FORBIDDEN:
                self.assertNotIn(token, text,
                                 f"{path.name} references {token}; the extension must "
                                 "implement Shura's interface in its own terms")

    def test_it_implements_the_shura_source_contract(self):
        source = self.read(
            MODULE, "src/main/java/app/shura/extension/mangadex/MangaDexSource.kt")
        self.assertIn("import app.shura.source.ShuraSource", source)
        self.assertIn(": ShuraSource", source)
        for member in ("val id", "val name", "val language",
                       "fun search", "fun chapters", "fun pages"):
            self.assertIn(member, source,
                          f"the contract declares {member} and it is not implemented")
        # Only the three operations the interface states. Nothing extra claimed.
        overrides = re.findall(r"override\s+suspend\s+fun\s+(\w+)", source)
        self.assertEqual(sorted(overrides), ["chapters", "pages", "search"])

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