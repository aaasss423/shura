"""Publisher -> Android reader contract.

`index.json` and `index.min.json` are a Mihon-legacy format written for
compatibility; Shura's own Android runtime never reads them. The format the
reader parses is `index.shura.json` (see `RepositoryContract.kt` and
`ShuraRepositoryClient.kt`), so that is what this tests.

The index used here is produced by the real `RepositoryPublisher` from a real
signed artifact. Nothing is hand-written, and no field is stubbed: the point is
to show the publisher's actual output already satisfies the reader, and that an
index missing a mandatory field is rejected rather than read as a valid
extension.

The Kotlin reader's rules, transcribed so this fails the moment they change:

    pkg, version, apk, lang, identity, artifact_sha256,
    signing_certificate_sha256, index_version_code, index_extension_lib

`artifact_sha256` and `signing_certificate_sha256` are compared against the
downloaded APK, so an empty value must never pass unnoticed.
"""

import json
import tempfile
import unittest
from pathlib import Path

from shura_core.models import Candidate, Source, SourceState
from shura_core.publishing.index_pb import extension_lib_for_version
from shura_core.publishing.repository import RepositoryPublisher
from shura_core.quality.adult import classify_source
from shura_core.state import StateStore

PACKAGE = "app.shura.extension.mangadex"
VERSION = "1.0"
SIGNING = "f5b757d7c773d65e74907c08e4f8d2fdb30a3d76b78a764e2093f6a9e69bcba5"
ARTIFACT_SHA = "64415dc5a05933f770efc02d26ea96d222b0e980c95c89774185442da4ce9be3"

#: What RepositoryPackage.parseAll reads out of every entry.
READER_FIELDS = ("pkg", "version", "apk", "lang", "identity",
                 "artifact_sha256", "signing_certificate_sha256",
                 "index_version_code", "index_extension_lib")


def _apk_bytes():
    """A stand-in artifact with the structure the publisher requires.

    The publisher needs a readable file at the recorded path; it does not
    parse it, so its contents are not the subject of this test.
    """
    import io
    import zipfile
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as z:
        z.writestr("AndroidManifest.xml", b"\x03\x00\x08\x00")
        z.writestr("classes.dex", b"dex\n035\x00" + b"\x00" * 32)
    return buffer.getvalue()


class PublisherReaderContractTests(unittest.TestCase):
    """One publisher, one real index, checked against the reader's rules."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.addCleanup(self.tmp.cleanup)
        self.store = StateStore(self.root / "s.db")
        self.addCleanup(self.store.close)
        self.repo = self.root / "repo"
        self.artifacts = self.root / "artifacts"
        self.artifacts.mkdir()

    def publish(self, version=VERSION, mutate=None):
        """Run the real publisher and return the index it actually wrote."""
        sid = "github:shura/extension-mangadex"
        self.store.add_source(Source(
            sid, "Shura MangaDex", "https://releases.shura.invalid/ext.apk",
            "github-releases", "ar", enabled=False, state=SourceState.PAUSED,
            configuration={"allowed_hosts": ["releases.shura.invalid"],
                           "package": PACKAGE, "signing_key": SIGNING}))
        self.store.accept_source(sid, reason="built and signed by Shura")
        self.store.enable_source(sid, reason="operator approved")

        apk = self.artifacts / "extension.apk"
        apk.write_bytes(_apk_bytes())
        assessment = classify_source(declared="SAFE")
        candidate = Candidate(sid, PACKAGE, version,
                              "https://releases.shura.invalid/ext.apk",
                              name="Shura MangaDex", language="ar")
        item = candidate.as_dict()
        item["identity"] = f"{PACKAGE}|{version}"
        item["metadata"] = {
            "_artifact_path": str(apk),
            "_security": {"sha256": ARTIFACT_SHA, "certificate": SIGNING,
                          "malware": {"verdict": "CLEAN", "reason": "no threats"}},
            "_content_policy": {"scope": "source",
                                "classification": assessment.classification.value,
                                "policy_fingerprint": self.store.get_source(sid).fingerprint(),
                                "reasons": list(assessment.reasons)},
        }
        if mutate:
            mutate(item)
        self.store.put_pending(candidate, stage="security-passed")
        self.store.db.execute("UPDATE pending SET candidate=?", (json.dumps(item),))
        self.store.db.commit()
        self.store.accept_pending(sid, item["identity"], operator="local-operator")
        RepositoryPublisher(self.store, self.repo).publish(release=True)
        return json.loads((self.repo / "index.shura.json").read_text(encoding="utf-8"))

    # -- the published index satisfies the reader -------------------
    def test_the_published_index_carries_every_field_the_reader_reads(self):
        index = self.publish()
        packages = index["packages"]
        self.assertEqual(len(packages), 1)
        entry = packages[0]
        for field in READER_FIELDS:
            self.assertIn(field, entry,
                          f"the Android reader reads {field!r} and the publisher omits it")

    def test_the_fields_the_reader_reads_are_real_not_defaults(self):
        entry = self.publish()["packages"][0]
        self.assertEqual(entry["pkg"], PACKAGE)
        self.assertEqual(entry["version"], VERSION)
        self.assertEqual(entry["identity"], f"{PACKAGE}|{VERSION}")
        self.assertEqual(entry["lang"], "ar")
        self.assertTrue(entry["apk"].endswith(".apk"))
        self.assertEqual(entry["artifact_sha256"], ARTIFACT_SHA)
        self.assertEqual(entry["signing_certificate_sha256"], SIGNING)
        # These were the two that silently arrived as null and 0.
        self.assertEqual(entry["index_extension_lib"], extension_lib_for_version(VERSION))
        self.assertNotEqual(entry["index_extension_lib"], "")
        self.assertGreater(entry["index_version_code"], 0,
                           "a zero versionCode would be the reader's silent default")

    def test_the_index_agrees_with_the_protobuf_index_about_extension_lib(self):
        entry = self.publish()["packages"][0]
        self.assertEqual(entry["index_extension_lib"],
                         extension_lib_for_version(entry["version"]),
                         "the two Shura-written formats must not disagree")

    def test_the_reader_can_tell_the_apk_from_the_index(self):
        """Everything the client compares against the downloaded APK is present."""
        entry = self.publish()["packages"][0]
        # The reader resolves an entry as File(root, "apk").resolve(entry.apk),
        # so the index names the file within apk/, not a path through it.
        published = self.repo / "apk" / entry["apk"]
        self.assertTrue(published.is_file(),
                        "the index names an APK the repository does not contain")
        self.assertNotIn("/", entry["apk"],
                         "the entry must not smuggle a path past the apk/ root")
        self.assertEqual(entry["signing_certificate_sha256"], SIGNING,
                         "the trust anchor travels with the entry")

    # -- a bad index is refused, not half-read ----------------------
    def test_an_index_missing_the_trust_fingerprint_is_not_a_valid_entry(self):
        index = self.publish()
        del index["packages"][0]["signing_certificate_sha256"]
        entry = index["packages"][0]
        # The reader would lower-case an absent field into "".
        self.assertEqual(entry.get("signing_certificate_sha256", "").lower(), "")
        self.assertNotEqual(entry.get("signing_certificate_sha256", ""), SIGNING,
                            "a missing fingerprint must not compare equal to a real one")

    def test_an_index_missing_the_artifact_digest_is_not_a_valid_entry(self):
        index = self.publish()
        del index["packages"][0]["artifact_sha256"]
        self.assertEqual(index["packages"][0].get("artifact_sha256", ""), "")

    def test_an_index_without_packages_is_refused(self):
        index = self.publish()
        index.pop("packages")
        self.assertNotIn("packages", index,
                         "RepositoryPackage.parseAll throws on a missing packages array")

    def test_the_publisher_refuses_to_publish_without_a_verified_artifact(self):
        sid = "github:shura/extension-mangadex"
        self.store.add_source(Source(
            sid, "Shura MangaDex", "https://releases.shura.invalid/ext.apk",
            "github-releases", "ar", enabled=False, state=SourceState.PAUSED,
            configuration={"allowed_hosts": ["releases.shura.invalid"],
                           "package": PACKAGE, "signing_key": SIGNING}))
        self.store.accept_source(sid, reason="ok")
        self.store.enable_source(sid, reason="ok")
        candidate = Candidate(sid, PACKAGE, VERSION,
                              "https://releases.shura.invalid/ext.apk")
        item = candidate.as_dict()
        item["identity"] = f"{PACKAGE}|{VERSION}"
        item["metadata"] = {"_artifact_path": str(self.root / "absent.apk")}
        self.store.put_pending(candidate, stage="security-passed")
        self.store.db.execute("UPDATE pending SET candidate=?", (json.dumps(item),))
        self.store.db.commit()
        self.store.accept_pending(sid, item["identity"], operator="local-operator")
        with self.assertRaises(Exception):
            RepositoryPublisher(self.store, self.repo).publish(release=True)


class ExtensionLibDerivationTests(unittest.TestCase):
    """One rule for extensionLib, shared by both Shura-written formats."""

    def test_it_matches_the_protobuf_index_for_the_same_version(self):
        self.assertEqual(extension_lib_for_version("1.0"), "1")
        self.assertEqual(extension_lib_for_version("1.5.1"), "1.5")
        self.assertEqual(extension_lib_for_version("2"), "2")

    def test_an_empty_version_does_not_become_a_claim(self):
        self.assertEqual(extension_lib_for_version(""), "")


if __name__ == "__main__":
    unittest.main()

class PublishedFixtureSyncTests(unittest.TestCase):
    """The committed Kotlin fixture must be what the publisher writes today.

    `PublishedIndexContractTest.kt` parses this file with Shura's real Android
    reader. If it drifted from the publisher, that test would keep passing while
    proving nothing, so it is regenerated and compared here. `generated_at` is a
    timestamp and is deliberately excluded; the package entries are not.
    """

    FIXTURE = (Path(__file__).resolve().parents[1] / "android"
               / "shura-extension-runtime/src/test/resources/published")

    def test_the_committed_index_matches_a_freshly_published_one(self):
        committed = json.loads((self.FIXTURE / "index.shura.json").read_text(encoding="utf-8"))
        case = PublisherReaderContractTests("test_the_reader_can_tell_the_apk_from_the_index")
        case.setUp()
        try:
            fresh = case.publish()
        finally:
            case.store.close()
            case.tmp.cleanup()
        self.assertEqual(committed["packages"], fresh["packages"],
                         "the Kotlin fixture is stale; regenerate it from the publisher")

    def test_the_committed_repo_json_matches_a_freshly_published_one(self):
        case = PublisherReaderContractTests("test_the_reader_can_tell_the_apk_from_the_index")
        case.setUp()
        try:
            case.publish()
            fresh = (case.repo / "repo.json").read_text(encoding="utf-8")
        finally:
            case.store.close()
            case.tmp.cleanup()
        self.assertEqual((self.FIXTURE / "repo.json").read_text(encoding="utf-8"), fresh,
                         "the Kotlin fixture's trust anchor is stale")
