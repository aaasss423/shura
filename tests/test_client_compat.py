"""Step 6 — repository signing + Mihon/Keiyoushi client index compatibility.

The assertions here mirror what Mihon actually reads from an extension repo
(mihonapp/mihon ``data/src/main/java/mihon/data/extension/model/*``):

* ``repo.json``: ``NetworkLegacyExtensionRepo`` -> ``meta.{name, website,
  signingKeyFingerprint}`` (+ optional ``index_v2``).
* ``index.json``/``index.min.json``: a JSON *array* of ``NetworkLegacyExtension``
  entries whose ``apk`` is a filename resolved under ``<base>/apk/``.
* ``index.pb``: gzip-compressed protobuf ``NetworkExtensionStore`` whose field
  numbers are decoded here independently ("Mihon-side") from the exact
  ``@ProtoNumber`` mapping, so a tag mismatch is caught even if the piggyback
  ``decode_index`` were wrong.

Signing: Mihon/Keiyoushi trust *one* SHA-256 certificate fingerprint (64 hex)
per repository; Shura must derive it from the cert that actually signs the
published APKs and fail closed on absence, disagreement, or a mismatch with the
configured trusted ``signing_key``.
"""
import json
import os
import tempfile
import unittest
import zipfile
from pathlib import Path

from shura_core.models import Candidate, Source
from shura_core.state import StateStore
from shura_core.publishing import protobuf as pb
from shura_core.publishing.repository import (
    RepositoryPublisher,
    PublishRefused,
    _version_code,
    _normalize_version,
)

CERT = "efd9a7f5cd66f110df33289dbf8bafc89275e00b5434ce489f1d8a2dc209bbe7"
CERT_OTHER = "a" * 64

# Mihon NetworkExtensionStore @ProtoNumber tables (authoritative reference).
MHON_INDEX = {1: "name", 2: "badgeLabel", 3: "signingKey", 4: "contact", 5: "extensionList"}
MHON_CONTACT = {1: "website", 2: "discord"}
MHON_EXTENSION = {1: "name", 2: "packageName", 3: "resources", 4: "extensionLib",
                  5: "versionCode", 6: "versionName", 7: "contentWarning", 8: "sources"}
MHON_RESOURCES = {1: "apkUrl", 2: "iconUrl"}
MHON_SOURCE = {1: "id", 2: "name", 3: "language", 4: "homeUrl", 5: "mirrorUrls", 7: "message"}


def mihon_decode_store(data: bytes) -> dict:
    """Re-implementation of Mihon's protobuf parse (tags from the source)."""
    assert data[:2] == b"\x1f\x8b", "Mihon decompresses gzip index.pb before decoding"
    import gzip
    top = pb.decode(gzip.decompress(data))
    store = {"name": _s(top, 1), "badgeLabel": _s(top, 2), "signingKey": _s(top, 3)}
    for raw in top.get(4, []):
        store["contact"] = {MHON_CONTACT[k]: v for k, v in _scalars(raw).items()}
    extensions = []
    for raw in top.get(5, []):
        for ext_raw in pb.decode(raw).get(1, []):
            fields = pb.decode(ext_raw)
            ext = {
                "name": _s(fields, 1),
                "packageName": _s(fields, 2),
                "extensionLib": _s(fields, 4),
                "versionCode": int(fields[5][0]) if fields.get(5) else 0,
                "versionName": _s(fields, 6),
                "contentWarning": int(fields[7][0]) if fields.get(7) else 0,
            }
            if fields.get(3):
                ext["resources"] = {MHON_RESOURCES[k]: v for k, v in _scalars(fields[3][0]).items()}
            ext["sources"] = []
            for src_raw in fields.get(8, []):
                src = pb.decode(src_raw)
                source = {k: v for k, v in _scalars(src).items() if k != 5}
                source["mirrorUrls"] = [x.decode("utf-8", "replace") for x in src.get(5, [])]
                ext["sources"].append(source)
            extensions.append(ext)
    store["extensions"] = extensions
    return store


def _s(fields: dict, number: int) -> str:
    return fields[number][0].decode("utf-8", "replace") if fields.get(number) else ""


def _scalars(raw: bytes) -> dict:
    out = {}
    for k, values in pb.decode(raw).items():
        if not values:
            continue
        first = values[0]
        out[k] = first if isinstance(first, int) else first.decode("utf-8", "replace")
    return out


def signer_cert_digests(apk_path: str) -> set[str]:
    """SHA-256 digests of every certificate that actually verifies an APK
    install (v1 JAR META-INF + APK Signature Scheme v2/v3 signing block),
    derived without apksigner (which cannot run in this sandbox)."""
    import gzip  # noqa:F401
    from hashlib import sha256
    import struct

    def parse_tlv(data):
        nodes, off = [], 0
        while off < len(data):
            t = data[off]; off += 1
            if (t & 0x1F) == 0x1F:
                t = (t << 8) | data[off]; off += 1
            l = data[off]; off += 1
            if l & 0x80:
                n = l & 0x7F; l = int.from_bytes(data[off:off + n], "big"); off += n
            nodes.append((t, data[off:off + l])); off += l
        return nodes

    def is_cert_value(blob: bytes) -> bool:
        return len(blob) >= 4 and blob[:1] == b"\x02" and blob[1:2] in (b"\x01", b"\x02", b"\x03")

    digests: set[str] = set()

    try:
        zf = zipfile.ZipFile(apk_path)
    except (zipfile.BadZipFile, OSError, RuntimeError):
        zf = None
    if zf is not None:
        with zf:
            for infra in ("META-INF/PROCOMIC.RSA", "META-INF/CERT.RSA", "META-INF/SIGNER.RSA"):
                try:
                    seq = parse_tlv(zf.read(infra))
                except (KeyError, OSError, RuntimeError):
                    continue
                try:
                    top_children = parse_tlv(seq[0][1])
                    a0 = [v for t, v in top_children if t == 0xA0][0]
                    sd = parse_tlv(a0)[0]
                    for t, v in parse_tlv(sd[1]):
                        if t in (0xA0, 0xA1):
                            for ct, cv in parse_tlv(v):
                                if ct == 0x30:
                                    digests.add(sha256(cv).hexdigest())
                except (IndexError, KeyError, struct.error, ValueError):
                    pass

    blob = open(apk_path, "rb").read()
    eocd = blob.rfind(b"PK\x05\x06")
    if eocd != -1 and blob[eocd + 20:eocd + 22] == b"\x00\x00":
        cd = struct.unpack_from("<I", blob, eocd + 16)[0]
        magic_start = cd - 16
        if blob[magic_start:cd] == b"APK Sig Block 42":
            size = struct.unpack_from("<Q", blob, cd - 24)[0]
            area = blob[cd - size:cd - 24]
            i = 0
            while i < len(area) - 4:
                if area[i] == 0x30 and area[i + 1] == 0x82:
                    ln = (area[i + 2] << 8) | area[i + 3]
                    if 600 <= ln <= 8192 and i + 4 + ln <= len(area) and is_cert_value(area[i + 4:i + 4 + ln]):
                        digests.add(sha256(area[i + 4:i + 4 + ln]).hexdigest())
                        i += ln
                        continue
                i += 1

    return digests


class SigningGateTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = StateStore(Path(self.tmp.name) / "s.db")
        self.source = Source("s1", "fixture", "https://example.org/index.json", "index", "ar",
                             configuration={"allowed_hosts": ["example.org"], "signing_key": CERT})
        self.store.add_source(self.source)
        self.repo = Path(self.tmp.name) / "repo"

    def tearDown(self):
        self.store.close()
        self.tmp.cleanup()

    def candidate(self, version: str, source_id: str = "s1",
                  certificate: str | None = CERT) -> Candidate:
        artifact = Path(self.tmp.name) / f"{source_id}-{version}.apk"
        artifact.write_bytes(b"apk-content")
        security = {"sha256": "c" * 64}
        if certificate:
            security["certificate"] = certificate
        return Candidate(source_id, "org.example.ext", version, "https://example.org/x.apk",
                         name="Example", language="ar", provenance={"page": "p"},
                         metadata={"_artifact_path": str(artifact), "_security": security})

    def accept_and_publish(self, *candidates: Candidate):
        for c in candidates:
            self.store.put_pending(c, "security-passed")
            self.store.accept_pending(c.source_id, c.identity)
        return RepositoryPublisher(self.store, self.repo).publish(release=True)

    def test_missing_signing_certificate_fails_closed(self):
        with self.assertRaises(PublishRefused) as ctx:
            self.accept_and_publish(self.candidate("1.0", certificate=None))
        self.assertIn("signing certificate fingerprint", str(ctx.exception))
        self.assertFalse((self.repo / "index.json").exists())

    def test_multiple_signing_keys_fail_closed(self):
        second = Source("s2", "other", "https://example.org/other.json", "index", "ar",
                        configuration={"allowed_hosts": ["example.org"], "signing_key": CERT_OTHER})
        self.store.add_source(second)
        with self.assertRaises(PublishRefused) as ctx:
            self.accept_and_publish(
                self.candidate("1.0", source_id="s1", certificate=CERT),
                self.candidate("2.0", source_id="s2", certificate=CERT_OTHER),
            )
        self.assertIn("multiple keys", str(ctx.exception))
        self.assertFalse((self.repo / "index.json").exists())

    def test_signing_key_mismatch_with_configured_key_fails_closed(self):
        with self.assertRaises(PublishRefused) as ctx:
            self.accept_and_publish(self.candidate("1.0", certificate=CERT_OTHER))
        self.assertIn("signing key mismatch", str(ctx.exception))
        self.assertFalse((self.repo / "index.json").exists())

    def test_empty_repository_never_writes(self):
        result = RepositoryPublisher(self.store, self.repo).publish(release=True)
        self.assertTrue(result["noop"])
        self.assertFalse(self.repo.exists())


class LegacyIndexCompatibilityTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = StateStore(Path(self.tmp.name) / "s.db")
        self.repo = Path(self.tmp.name) / "repo"
        artifact = Path(self.tmp.name) / "x.apk"
        artifact.write_bytes(b"apk")
        c = Candidate("s1", "eu.kanade.tachiyomi.extension.ar.procomic", "v1.5.1",
                      "https://example.org/x.apk", name="ProComic (AR)", language="ar",
                      provenance={"page": "p"},
                      metadata={"_artifact_path": str(artifact),
                                "_security": {"sha256": "c" * 64, "certificate": CERT}})
        self.store.add_source(Source("s1", "fixture", "https://example.org/index.json", "index", "ar",
                                     configuration={"allowed_hosts": ["example.org"], "signing_key": CERT}))
        self.store.put_pending(c, "security-passed")
        self.store.accept_pending("s1", c.identity)
        RepositoryPublisher(self.store, self.repo).publish(release=True)

    def tearDown(self):
        self.store.close()
        self.tmp.cleanup()

    def test_legacy_index_is_an_array_with_exact_mihon_fields(self):
        index = json.loads((self.repo / "index.min.json").read_text())
        self.assertIsInstance(index, list)
        self.assertEqual(len(index), 1)
        entry = index[0]
        self.assertEqual(
            set(entry.keys()),
            {"name", "pkg", "apk", "lang", "code", "version", "nsfw", "sources"},
        )
        self.assertEqual(entry["pkg"], "eu.kanade.tachiyomi.extension.ar.procomic")
        self.assertEqual(entry["version"], "1.5.1")  # no "v" prefix: toDouble() must not throw
        self.assertEqual(entry["code"], _version_code("v1.5.1"))
        self.assertEqual(entry["apk"], "eu_kanade_tachiyomi_extension_ar_procomic-v1.5.1.apk")
        self.assertTrue((self.repo / "apk" / entry["apk"]).is_file())
        self.assertEqual(entry["nsfw"], 0)
        self.assertEqual(entry["sources"], [])

    def test_index_json_pretty_matches_min(self):
        full = json.loads((self.repo / "index.json").read_text())
        mini = json.loads((self.repo / "index.min.json").read_text())
        self.assertEqual(full, mini)

    def test_repo_json_matches_keiyoushi_meta_shape(self):
        repo_doc = json.loads((self.repo / "repo.json").read_text())
        self.assertEqual(set(repo_doc["meta"].keys()), {"name", "website", "signingKeyFingerprint"})
        self.assertEqual(repo_doc["meta"]["signingKeyFingerprint"], CERT)
        self.assertEqual(repo_doc["meta"]["name"], "Shura")
        self.assertNotIn("index_v2", repo_doc)  # no base URL configured -> legacy flow only

    def test_repo_index_v2_present_when_base_url_configured(self):
        import os
        os.environ["SHURA_REPO_BASE_URL"] = "https://repo.example/shura"
        try:
            # Force a materialization run (no pending) so repo.json is rewritten.
            RepositoryPublisher(self.store, self.repo).publish(release=True)
            repo_doc = json.loads((self.repo / "repo.json").read_text())
            self.assertEqual(repo_doc["index_v2"], "https://repo.example/shura/index.pb")
            index = json.loads((self.repo / "index.min.json").read_text())
            self.assertEqual(index[0]["version"], "1.5.1")
        finally:
            del os.environ["SHURA_REPO_BASE_URL"]


class ProtoV2CompatibilityTests(LegacyIndexCompatibilityTests):
    def test_index_pb_decodes_with_mihon_proto_numbering(self):
        data = (self.repo / "index.pb").read_bytes()
        store = mihon_decode_store(data)
        self.assertEqual(store["name"], "Shura")
        self.assertEqual(store["signingKey"], CERT)
        self.assertEqual(store["contact"]["website"], "")
        ext = store["extensions"][0]
        self.assertEqual(ext["packageName"], "eu.kanade.tachiyomi.extension.ar.procomic")
        self.assertEqual(ext["extensionLib"], "1.5")  # <base>.toDouble() must parse
        self.assertEqual(ext["versionCode"], _version_code("v1.5.1"))
        self.assertEqual(ext["versionName"], "1.5.1")
        self.assertEqual(ext["contentWarning"], 1)  # SAFE
        self.assertEqual(ext["resources"]["apkUrl"], "apk/eu_kanade_tachiyomi_extension_ar_procomic-v1.5.1.apk")
        self.assertEqual(ext["sources"], [])

    def test_index_pb_reports_absolute_urls_when_base_configured(self):
        import os
        os.environ["SHURA_REPO_BASE_URL"] = "https://repo.example/shura"
        try:
            RepositoryPublisher(self.store, self.repo).publish(release=True)
        finally:
            del os.environ["SHURA_REPO_BASE_URL"]
        store = mihon_decode_store((self.repo / "index.pb").read_bytes())
        self.assertEqual(
            store["extensions"][0]["resources"]["apkUrl"],
            "https://repo.example/shura/apk/eu_kanade_tachiyomi_extension_ar_procomic-v1.5.1.apk",
        )
        self.assertEqual(
            store["extensions"][0]["resources"]["iconUrl"],
            "https://repo.example/shura/icon/eu.kanade.tachiyomi.extension.ar.procomic.png",
        )


class RealArtifactSigningTests(unittest.TestCase):
    """The published fingerprint must be the digest of the certificate that
    actually signs the shipped APKs. Regression for the stale ``b655a474...``
    pin that matched no certificate in the artifacts."""

    APK_DIR = Path(os.environ.get("SHURA_TEST_APK_DIR", "/workspace/repo/apk"))

    def test_published_fingerprint_matches_every_apk_signer(self):
        if not self.APK_DIR.is_dir():
            self.skipTest(f"no real artifacts at {self.APK_DIR}")
        apks = sorted(self.APK_DIR.glob("*.apk"))
        self.assertTrue(apks, f"no APKs under {self.APK_DIR}")
        for apk in apks:
            digests = signer_cert_digests(str(apk))
            self.assertEqual(
                len(digests), 1,
                f"{apk.name} must be signed by exactly one certificate, got {sorted(digests)}",
            )
            digest = digests.pop()
            self.assertEqual(
                digest, CERT,
                f"{apk.name} install-time signer {digest} != published pin {CERT}",
            )

    def test_signer_cert_digest_helper_rejects_nonsense(self):
        tmp = tempfile.NamedTemporaryFile(suffix=".apk", delete=False)
        try:
            tmp.write(b"not an apk at all")
            tmp.close()
            self.assertEqual(signer_cert_digests(tmp.name), set())
        finally:
            os.unlink(tmp.name)


class MaterializationTests(unittest.TestCase):
    def test_publish_materializes_all_published_packages(self):
        tmp = tempfile.TemporaryDirectory()
        store = StateStore(Path(tmp.name) / "s.db")
        repo = Path(tmp.name) / "repo"
        store.add_source(Source("s1", "fixture", "https://example.org/index.json", "index", "ar",
                                configuration={"allowed_hosts": ["example.org"], "signing_key": CERT}))
        try:
            for version in ("1.0", "1.1"):
                artifact = Path(tmp.name) / f"{version}.apk"
                artifact.write_bytes(version.encode())
                c = Candidate("s1", "org.example.ext", version, "https://example.org/x.apk",
                              name="X", language="ar", provenance={"page": "p"},
                              metadata={"_artifact_path": str(artifact),
                                        "_security": {"sha256": "c" * 64, "certificate": CERT}})
                store.put_pending(c, "security-passed")
                store.accept_pending("s1", c.identity)
                result = RepositoryPublisher(store, repo).publish(release=True)
                self.assertEqual(result["published"], 1)
            files = sorted(p.name for p in (repo / "apk").iterdir())
            self.assertEqual(files, ["org_example_ext-1.0.apk", "org_example_ext-1.1.apk"])
            index = json.loads((repo / "index.min.json").read_text())
            self.assertEqual([e["version"] for e in index], ["1.0", "1.1"])
        finally:
            store.close()
            tmp.cleanup()

    def test_audit_index_keeps_provenance_and_malware(self):
        tmp = tempfile.TemporaryDirectory()
        store = StateStore(Path(tmp.name) / "s.db")
        repo = Path(tmp.name) / "repo"
        store.add_source(Source("s1", "fixture", "https://example.org/index.json", "index", "ar",
                                configuration={"allowed_hosts": ["example.org"], "signing_key": CERT}))
        try:
            artifact = Path(tmp.name) / "x.apk"
            artifact.write_bytes(b"apk")
            c = Candidate("s1", "org.example.ext", "1.0", "https://example.org/x.apk",
                          name="X", language="ar", provenance={"page": "https://example.org/list"},
                          metadata={"_artifact_path": str(artifact),
                                    "_security": {"sha256": "c" * 64, "certificate": CERT,
                                                  "malware": {"engine": "MalwareScanner", "verdict": "CLEAN",
                                                              "detail": "no threats detected"}}})
            store.put_pending(c, "security-passed")
            store.accept_pending("s1", c.identity)
            RepositoryPublisher(store, repo).publish(release=True)
            audit = json.loads((repo / "index.shura.json").read_text())
            self.assertEqual(audit["signing_key_fingerprint"], CERT)
            entry = audit["packages"][0]
            self.assertEqual(entry["identity"], "org.example.ext|1.0")
            self.assertEqual(entry["artifact_sha256"], "c" * 64)
            self.assertEqual(entry["malware"]["verdict"], "CLEAN")
            self.assertEqual(entry["provenance"], {"page": "https://example.org/list"})
        finally:
            store.close()
            tmp.cleanup()


if __name__ == "__main__":
    unittest.main()