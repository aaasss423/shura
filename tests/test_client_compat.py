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
import re
import tempfile
import unittest
import zipfile
from pathlib import Path

from shura_core.models import Candidate, Source
from shura_core.state import StateStore
from shura_core.publishing import protobuf as pb
from shura_core.publishing import index_pb
from shura_core.publishing.repository import (
    RepositoryPublisher,
    PublishRefused,
    _version_code,
    _normalize_version,
)

CERT = "efd9a7f5cd66f110df33289dbf8bafc89275e00b5434ce489f1d8a2dc209bbe7"
CERT_OTHER = "a" * 64

# Mihon NetworkExtensionStore @ProtoNumber tables (authoritative reference).
# ``Index.extensionList`` is @ProtoNumber(101) upstream, and Keiyoushi's
# index.proto puts it in the ``extensions`` oneof with extensionListUrl=102.
# Tag 5 is NOT part of the contract; keeping it here is exactly the regression
# this file exists to catch.
MHON_INDEX = {1: "name", 2: "badgeLabel", 3: "signingKey", 4: "contact",
              101: "extensionList", 102: "extensionListUrl"}
#: Field numbers the contract leaves unused inside ``Index``. Shura must never
#: emit any of them; 5 is the historical mistake (the real tag is 101).
MHON_INDEX_RESERVED = {5}
MHON_INDEX_EXTENSION_LIST = 101
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
    for raw in top.get(101, []):
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


def walk_fields(data: bytes) -> list[tuple[int, int, object]]:
    """Decode ``data`` into ``[(field_number, wire_type, value), ...]``.

    ``pb.decode`` deliberately drops wire types, so a schema test that used it
    could not tell a length-delimited string from a varint. Contract tests
    need both the tag *and* the wire type of every field on the wire.
    """
    out: list[tuple[int, int, object]] = []
    offset = 0
    while offset < len(data):
        key, offset = pb.decode_varint(data, offset)
        number, wire_type = key >> 3, key & 0x07
        if wire_type == pb.WIRE_VARINT:
            value, offset = pb.decode_varint(data, offset)
        elif wire_type == pb.WIRE_LENGTH:
            length, offset = pb.decode_varint(data, offset)
            value = data[offset:offset + length]
            offset += length
        else:  # pragma: no cover - would itself be a contract violation
            raise AssertionError(f"field {number}: unexpected wire type {wire_type}")
        out.append((number, wire_type, value))
    return out


def index_tags(data: bytes) -> list[tuple[int, int]]:
    """``[(field_number, wire_type), ...]`` for the top-level Index message."""
    raw = data
    if raw[:2] == b"\x1f\x8b":
        import gzip
        raw = gzip.decompress(raw)
    return [(number, wire) for number, wire, _ in walk_fields(raw)]


def extension_list_payloads(data: bytes) -> list[bytes]:
    """Raw ``ExtensionList`` sub-messages found at the contract tag (101)."""
    raw = data
    if raw[:2] == b"\x1f\x8b":
        import gzip
        raw = gzip.decompress(raw)
    return [value for number, wire, value in walk_fields(raw)
            if number == MHON_INDEX_EXTENSION_LIST and wire == pb.WIRE_LENGTH]


def _gunzip(data: bytes) -> bytes:
    import gzip
    if data[:2] == b"\x1f\x8b":
        return gzip.decompress(data)
    return data


def _only(data: bytes, number: int) -> bytes:
    """The single length-delimited payload at ``number``, else a failure."""
    found = [v for n, w, v in walk_fields(data) if n == number and w == pb.WIRE_LENGTH]
    if len(found) != 1:
        raise AssertionError(f"expected exactly one field {number}, got {len(found)}")
    return found[0]


def _contract_entry() -> dict:
    return {
        "name": "Example", "pkg": "org.example.ext", "apk": "org_example_ext-1.0.apk",
        "version": "1.0.2", "code": 102, "nsfw": 1,
        "apk_url": "apk/org_example_ext-1.0.apk",
        "icon_url": "icon/org.example.ext.png",
        "sources": [{"id": 7, "name": "Fixture", "language": "ar",
                     "home_url": "https://example.org",
                     "mirror_urls": ["https://mirror.example.org"],
                     "message": "hello"}],
    }


def _encode_with_tag_5(entries: list[dict]) -> bytes:
    """Build the *broken* index the encoder used to emit, on purpose.

    Reproduces the pre-fix wire format (extensionList at tag 5) so tests can
    prove the new decoder does not keep accepting it by accident.
    """
    original = index_pb.INDEX_EXTENSION_LIST
    try:
        index_pb.INDEX_EXTENSION_LIST = 5
        return index_pb.encode_index(entries, repo="Shura", signing_key=CERT)
    finally:
        index_pb.INDEX_EXTENSION_LIST = original


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


class ExtensionListField101RegressionTests(LegacyIndexCompatibilityTests):
    """Regression: ``Index.extensionList`` must be field 101, never 5.

    Shura emitted the inline list at tag 5. Mihon declares the field at
    ``@ProtoNumber(101)`` and Keiyoushi declares it inside the ``extensions``
    oneof, so tag 5 is an unknown field to a real client: the store parsed
    cleanly and then reported **zero extensions**. The repository looked
    installed and simply appeared empty, with no error anywhere -- which is
    why the piggyback decoder passed the suite at the time (see
    ``mihon_decode_store``, which had itself been written with tag 5).
    """

    def test_extension_list_is_written_to_field_101(self):
        tags = index_tags((self.repo / "index.pb").read_bytes())
        self.assertIn(
            (MHON_INDEX_EXTENSION_LIST, pb.WIRE_LENGTH), tags,
            f"extensionList (101, wire 2) missing from the Index message: {tags}",
        )

    def test_extension_list_is_never_written_to_field_5(self):
        tags = index_tags((self.repo / "index.pb").read_bytes())
        for number, wire in tags:
            self.assertNotEqual(
                number, 5,
                "extensionList regressed to field 5; Mihon reads 101 "
                "(@ProtoNumber(101) in NetworkExtensionStore.kt)",
            )
        # Belt and braces: 5 is a *reserved* tag in this contract, so nothing
        # at all may appear there -- not even a differently-typed field.
        self.assertFalse([t for t in tags if t[0] in MHON_INDEX_RESERVED])

    def test_index_pb_actually_contains_the_extensions(self):
        """Not a byte-pattern check: decode field 101 and enumerate extensions."""
        data = (self.repo / "index.pb").read_bytes()
        payloads = extension_list_payloads(data)
        self.assertEqual(len(payloads), 1, "exactly one inline ExtensionList is expected")
        decoded = pb.decode(payloads[0])
        raws = decoded.get(1, [])
        self.assertEqual(len(raws), 1, "ExtensionList.extensions must carry the extension")
        fields = pb.decode(raws[0])
        self.assertEqual(fields[1][0].decode(), "ProComic (AR)")
        self.assertEqual(fields[2][0].decode(), "eu.kanade.tachiyomi.extension.ar.procomic")
        self.assertEqual(fields[6][0].decode(), "1.5.1")

    def test_piggyback_decoder_and_mihon_decoder_agree_on_extension_count(self):
        """Both decoders must see the same extensions; a tag drift makes them
        disagree silently rather than raising."""
        data = (self.repo / "index.pb").read_bytes()
        self.assertEqual(len(index_pb.decode_index(data)["extensions"]), 1)
        self.assertEqual(len(mihon_decode_store(data)["extensions"]), 1)

    def test_decoder_rejects_legacy_tag_5_index(self):
        """A field-5 index must decode to *zero* extensions, proving the fix is
        load-bearing: the old encoder's output is not silently still accepted."""
        legacy = _encode_with_tag_5([{
            "name": "X", "pkg": "org.example.ext", "apk": "org_example_ext-1.0.apk",
            "version": "1.0", "code": 100, "nsfw": 0, "sources": [],
            "apk_url": "apk/org_example_ext-1.0.apk", "icon_url": "",
        }])
        self.assertEqual(index_pb.decode_index(legacy)["extensions"], [])
        self.assertEqual(mihon_decode_store(legacy)["extensions"], [])

    def test_contact_discord_stays_inside_the_contact_submessage(self):
        """Regression: Contact was built as ``message_field(4, website)`` and
        then ``+=``'d discord, which appends *after* the length prefix. The
        discord bytes escaped the Contact submessage and landed at Index level
        as a stray field 2 -- a duplicate ``badgeLabel`` to Mihon. Protobuf
        stays structurally valid, so no error surfaced anywhere."""
        data = index_pb.encode_index([], repo="Shura", website="https://w",
                                     discord="https://d", gzip_output=False)
        top = {n: v for n, _, v in walk_fields(data)}
        self.assertEqual({n for n, _, _ in walk_fields(data)}, {1, 2, 3, 4, 101})
        contact = _only(data, 4)
        self.assertEqual({n for n, _, _ in walk_fields(contact)}, {1, 2})
        # And the round-trip decoder must actually recover the discord handle.
        self.assertEqual(index_pb.decode_index(data)["discord"], "https://d")
        # Website-only must not gain a bogus discord.
        website_only = index_pb.encode_index([], website="https://w", gzip_output=False)
        self.assertEqual(index_pb.decode_index(website_only)["discord"], "")


class IndexProtoContractTests(unittest.TestCase):
    """Whole-schema guard: every field number and wire type Shura can emit,
    checked against the upstream Mihon/Keiyoushi tables. The point is to catch
    a *different* wrong tag in the future, not just the 101 one."""

    # message -> {field_number: (name, proto_type)} from upstream.
    CONTRACT = {
        "Index": {1: ("name", "string"), 2: ("badgeLabel", "string"),
                  3: ("signingKey", "string"), 4: ("contact", "message"),
                  101: ("extensionList", "message"), 102: ("extensionListUrl", "string")},
        "Contact": {1: ("website", "string"), 2: ("discord", "string")},
        "ExtensionList": {1: ("extensions", "message")},
        "Extension": {1: ("name", "string"), 2: ("packageName", "string"),
                      3: ("resources", "message"), 4: ("extensionLib", "string"),
                      5: ("versionCode", "int64"), 6: ("versionName", "string"),
                      7: ("contentWarning", "enum"), 8: ("sources", "message")},
        "Resources": {1: ("apkUrl", "string"), 2: ("iconUrl", "string")},
        "Source": {1: ("id", "int64"), 2: ("name", "string"), 3: ("language", "string"),
                   4: ("homeUrl", "string"), 5: ("mirrorUrls", "string"),
                   7: ("message", "string")},
    }
    # proto3 wire types: length-delimited for strings/messages, varint for
    # int64/enum.
    WIRE_BY_TYPE = {"string": pb.WIRE_LENGTH, "message": pb.WIRE_LENGTH,
                    "int64": pb.WIRE_VARINT, "enum": pb.WIRE_VARINT}

    def test_index_proto_declares_the_upstream_field_numbers(self):
        text = Path(__file__).resolve().parents[1].joinpath(
            "shura_core/publishing/index.proto").read_text()
        declared: dict[str, dict[int, str]] = {}
        message = None
        for raw in text.splitlines():
            line = raw.split("//")[0].strip()
            header = re.match(r"^(message|enum)\s+(\w+)\s*\{$", line)
            if header:
                if header.group(1) == "enum":
                    message = None  # enum bodies are not message fields
                else:
                    message = header.group(2)
                    declared.setdefault(message, {})
                continue
            if line == "}":
                message = None
                continue
            field = re.match(
                r"^(?:repeated\s+|optional\s+)?\w[\w.]*\s+(\w+)\s*=\s*(\d+);$", line)
            if field and message:
                declared[message][int(field.group(2))] = field.group(1)
        self.assertEqual(
            declared, {msg: {n: name for n, (name, _) in fields.items()}
                       for msg, fields in self.CONTRACT.items()},
            "index.proto field numbers drifted from the Mihon/Keiyoushi contract",
        )

    def test_every_emitted_field_matches_tag_and_wire_type(self):
        data = index_pb.encode_index([_contract_entry()], repo="Shura", signing_key=CERT,
                                     website="https://example.org",
                                     discord="https://discord.gg/shura")
        # Index level
        self.assertEqual(
            {(n, w) for n, w, _ in walk_fields(_gunzip(data))},
            {(1, pb.WIRE_LENGTH), (2, pb.WIRE_LENGTH), (3, pb.WIRE_LENGTH),
             (4, pb.WIRE_LENGTH), (101, pb.WIRE_LENGTH)},
        )
        # Contact level
        contact = _only(_gunzip(data), 4)
        self.assertEqual({n for n, _, _ in walk_fields(contact)}, {1, 2})
        # ExtensionList level
        ext_list = _only(_gunzip(data), 101)
        self.assertEqual({n for n, _, _ in walk_fields(ext_list)}, {1})
        # Extension level
        extension = _only(ext_list, 1)
        self.assertEqual({n for n, _, _ in walk_fields(extension)}, set(range(1, 9)))
        resources = _only(extension, 3)
        self.assertEqual({n for n, _, _ in walk_fields(resources)}, {1, 2})
        # Source level (note: 6 is intentionally absent upstream)
        source = _only(extension, 8)
        self.assertEqual({n for n, _, _ in walk_fields(source)}, {1, 2, 3, 4, 5, 7})

    def test_scalar_wire_types_are_varint_where_the_contract_says_so(self):
        data = _gunzip(index_pb.encode_index([_contract_entry()], signing_key=CERT))
        extension = _only(_only(data, 101), 1)
        by_number = {n: w for n, w, _ in walk_fields(extension)}
        for number, kind in ((5, "int64"), (7, "enum")):
            self.assertEqual(by_number[number], self.WIRE_BY_TYPE[kind])
        for number, kind in ((1, "string"), (2, "string"), (4, "string"), (6, "string")):
            self.assertEqual(by_number[number], self.WIRE_BY_TYPE[kind])
        self.assertEqual(by_number[3], pb.WIRE_LENGTH)  # Resources message

    def test_source_message_field_is_7_not_6(self):
        """Source.message is @ProtoNumber(7); field 6 was commented out
        upstream and must stay unused."""
        data = _gunzip(index_pb.encode_index([_contract_entry()], signing_key=CERT))
        source = _only(_only(_only(data, 101), 1), 8)
        numbers = [n for n, _, _ in walk_fields(source)]
        self.assertNotIn(6, numbers)
        self.assertIn(7, numbers)


class RepoJsonIndexV2ContractTests(LegacyIndexCompatibilityTests):
    """``repo.json``'s ``index_v2`` is the only handle Mihon uses to find the
    protobuf index, so it must point at the file we actually emit -- and that
    file must be the 101-tag one."""

    def test_index_v2_points_at_an_index_pb_that_decodes_extensions(self):
        import os
        os.environ["SHURA_REPO_BASE_URL"] = "https://repo.example/shura"
        try:
            RepositoryPublisher(self.store, self.repo).publish(release=True)
        finally:
            del os.environ["SHURA_REPO_BASE_URL"]
        repo_doc = json.loads((self.repo / "repo.json").read_text())
        index_v2 = repo_doc["index_v2"]
        self.assertEqual(index_v2, "https://repo.example/shura/index.pb")

        # Resolve the advertised URL the way a client would: the path segment
        # under the repository root.
        advertised = Path(index_v2).name
        self.assertEqual(advertised, "index.pb")
        target = self.repo / advertised
        self.assertTrue(target.is_file(), f"index_v2 advertises a missing file: {index_v2}")

        store = mihon_decode_store(target.read_bytes())
        self.assertEqual(len(store["extensions"]), 1)
        self.assertEqual(store["extensions"][0]["packageName"],
                         "eu.kanade.tachiyomi.extension.ar.procomic")
        self.assertEqual(store["extensions"][0]["resources"]["apkUrl"],
                         "https://repo.example/shura/apk/"
                         "eu_kanade_tachiyomi_extension_ar_procomic-v1.5.1.apk")
        # And the advertised URL's APK must actually exist locally, else the
        # client 404s on install.
        self.assertTrue((self.repo / "apk" / "eu_kanade_tachiyomi_extension_ar_procomic-v1.5.1.apk").is_file())


class DuplicateVersionListingTests(unittest.TestCase):
    """A store may legitimately list several versions of one package, and Shura
    does: v1.5.0 and v1.5.1 of ProComic are both published.

    That is safe because Mihon never reads the listing order. It collapses each
    package to a single entry itself:

        availableExtensionsFlow: groupBy(pkgName to signingKey).values
                                       .map { maxWith(versionCode, libVersion) }
        Extension.Installed.findListing: filter { pkgName == ... }
                                       .maxWithOrNull(versionCode, libVersion)
        Extension.Installed.findUpdate: findListing(...).takeIf { newer }

    So the invariant that actually matters is not "one version per package" but
    that Shura's ``versionCode`` orders the same way the versions do -- otherwise
    ``maxWith`` would hand the client the *older* APK. These tests pin that.

    Deliberately no test asserts the older listing is dropped: removing it would
    be a change with no client-visible benefit.
    """

    ORDERING_VERSIONS = ["0.9.9", "1.0.0", "1.0.1", "1.5.0", "1.5.1",
                         "1.5.2", "1.5.10", "1.6.0", "1.10.0", "2.0.0", "10.0.0"]

    @staticmethod
    def _semver(version):
        return tuple(int(part) for part in version.split("."))

    def _mihon_pick(self, listings):
        """Reproduce Mihon's collapse exactly: maxWith(versionCode, libVersion)."""
        keyed = [(_version_code(v), float(v.rsplit(".", 1)[0]), v) for v in listings]
        return max(keyed)[2]

    def test_mihon_collapse_selects_the_newest_version_not_the_last_listed(self):
        for order in (self.ORDERING_VERSIONS,
                      list(reversed(self.ORDERING_VERSIONS)),
                      ["1.5.0", "1.5.1"], ["1.5.1", "1.5.0"]):
            with self.subTest(order=order[:3]):
                picked = self._mihon_pick(order)
                self.assertEqual(
                    self._semver(picked), max(self._semver(v) for v in order),
                    f"Mihon would offer {picked}, not the newest, for {order}",
                )

    def test_version_code_is_monotonic_along_an_upgrade_path(self):
        codes = [_version_code(v) for v in self.ORDERING_VERSIONS]
        self.assertEqual(codes, sorted(codes),
                         "versionCode must rise with the version or Mihon's maxWith "
                         "would serve a downgrade")

    def test_published_index_resolves_to_the_newest_listing(self):
        live = Path(__file__).resolve().parents[1] / "repo" / "index.json"
        if not live.is_file():
            self.skipTest("no published repo/index.json")
        entries = json.loads(live.read_text())
        by_pkg = {}
        for entry in entries:
            by_pkg.setdefault(entry["pkg"], []).append(entry["version"])
        self.assertTrue(by_pkg, "published index must not be empty")
        for pkg, versions in by_pkg.items():
            with self.subTest(pkg=pkg):
                self.assertEqual(self._mihon_pick(versions),
                                 max(versions, key=self._semver),
                                 f"Mihon would not offer the newest build of {pkg}")

    def test_duplicate_listings_stay_distinct_in_both_indexes(self):
        """Both versions remain individually addressable (each keeps its own APK),
        which is what makes the collapse safe rather than destructive."""
        live = Path(__file__).resolve().parents[1] / "repo"
        index_json = json.loads((live / "index.json").read_text())
        self.assertEqual(
            sorted(e["version"] for e in index_json if e["pkg"].endswith("procomic")),
            ["1.5.0", "1.5.1"],
        )
        apks = {e["apk"] for e in index_json}
        self.assertEqual(len(apks), len(index_json), "each listing needs its own APK file")
        for name in apks:
            self.assertTrue((live / "apk" / name).is_file(), f"missing APK {name}")
        store = mihon_decode_store((live / "index.pb").read_bytes())
        self.assertEqual(len(store["extensions"]), 2)
        self.assertEqual(sorted(e["versionCode"] for e in store["extensions"]),
                         [10500, 10501])


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