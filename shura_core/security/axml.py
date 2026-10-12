"""Read a compiled AndroidManifest.xml without needing aapt on PATH.

Both the trust scanner and the extension builder need to know what an APK says
about itself - its package, its source class, its extension ABI - and neither
should depend on an Android build tool being installed.

That dependency had a real cost. ``ArtifactScanner`` shells out to ``aapt2``;
where it is not on PATH the package comes back unread, and an APK that is
genuinely well-formed is then reported SUSPICIOUS because it *could not be
verified*. A gate that cannot tell "unverified" from "fine" is a gate that
either blocks everything or is switched off.

So the manifest is read here, from the bytes in the APK. It is the same file
either way; this only removes the dependency on a tool being present.

It also settles something aapt2 cannot: ``aapt2 dump xmltree`` prints a float
and a boolean identically, so it cannot tell whether the reader will be handed
``1.0`` or ``true`` for ``extensionLib``. The compiled bytes do say which.
"""

from __future__ import annotations

import struct
import zipfile

__all__ = ["AndroidManifest", "TYPE_FLOAT", "TYPE_INT_BOOLEAN", "TYPE_STRING",
           "manifest_of", "ManifestError"]


class ManifestError(Exception):
    """The manifest is missing, or is not a compiled binary manifest."""


#: Android's value types as they appear in a compiled binary manifest.
TYPE_STRING = 0x03
TYPE_FLOAT = 0x04
TYPE_INT_DEC = 0x10
TYPE_INT_BOOLEAN = 0x12


class AndroidManifest:
    """The few things Shura reads out of a manifest."""

    def __init__(self, package: str = "", meta: dict[str, tuple[str, int]] | None = None):
        self.package = package
        #: name -> (value, value_type)
        self.meta = meta or {}

    def meta_string(self, name: str) -> str:
        found = self.meta.get(name)
        return found[0] if found else ""

    def meta_type(self, name: str) -> int | None:
        found = self.meta.get(name)
        return found[1] if found else None


def _uleb(data: bytes, at: int):
    result = shift = 0
    while True:
        byte = data[at]
        at += 1
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result, at
        shift += 7


def _pool_string(blob: bytes, at: int, utf8: bool) -> str:
    """One string-pool entry.

    The two branches read *different* length encodings, which is the whole
    trap: a UTF-8 entry carries two varints (characters, then bytes), while a
    UTF-16 entry carries a plain little-endian uint16 of code units. Reading the
    UTF-16 length as a varint stops one byte early and every string comes back
    byte-swapped, which turns a correct manifest into an unreadable one.
    """
    if utf8:
        _characters, at = _uleb(blob, at)
        octets, at = _uleb(blob, at)
        return blob[at:at + octets].decode("utf-8", "replace")
    units = struct.unpack_from("<H", blob, at)[0]
    at += 2
    if units & 0x8000:
        units = struct.unpack_from("<I", blob, at - 2)[0]
    return blob[at:at + units * 2].decode("utf-16-le", "replace")


def _typed_text(value_type: int, data: int) -> str:
    if value_type == TYPE_FLOAT:
        return repr(struct.unpack("<f", struct.pack("<I", data))[0])
    if value_type == TYPE_INT_DEC:
        return str(data)
    if value_type == TYPE_INT_BOOLEAN:
        return "true" if data else "false"
    return ""


def _parse(blob: bytes) -> AndroidManifest:
    if len(blob) < 8 or blob[:4] != b"\x03\x00\x08\x00":
        raise ManifestError("not a compiled binary manifest")

    pool = 8
    if struct.unpack_from("<H", blob, pool)[0] != 0x0001:
        raise ManifestError("manifest has no string pool")
    string_count, _styles, flags, strings_start = struct.unpack_from("<IIII", blob, pool + 8)
    utf8 = bool(flags & (1 << 8))
    # The string pool header is 8 bytes plus five 4-byte fields, so the offset
    # table starts twenty bytes in. Reading it four bytes early shifts every
    # string index and makes a correct manifest look corrupt.
    offsets_at = pool + 8 + 20
    try:
        strings = [
            _pool_string(blob, pool + strings_start
                         + struct.unpack_from("<I", blob, offsets_at + 4 * i)[0], utf8)
            for i in range(string_count)
        ]
    except (struct.error, IndexError) as exc:
        raise ManifestError(f"string pool is malformed: {exc}") from exc

    def resolve(index: int) -> str:
        return strings[index] if 0 <= index < len(strings) else ""

    package = ""
    meta: dict[str, tuple[str, int]] = {}
    position = pool + struct.unpack_from("<I", blob, pool + 4)[0]
    while position + 8 <= len(blob):
        # type is two bytes, headerSize two, and chunkSize is a *four* byte
        # field. Reading the size as two steps by the header, not the chunk, and
        # the walk then misses every element in the document.
        kind = struct.unpack_from("<H", blob, position)[0]
        size = struct.unpack_from("<I", blob, position + 4)[0]
        if size < 8:
            raise ManifestError("manifest chunk is shorter than its header")
        if kind == 0x0102:                                  # XML_START_ELEMENT
            try:
                name_index = struct.unpack_from("<I", blob, position + 20)[0]
                attribute_start = struct.unpack_from("<H", blob, position + 24)[0]
                attribute_size = struct.unpack_from("<H", blob, position + 26)[0]
                attribute_count = struct.unpack_from("<H", blob, position + 28)[0]
            except struct.error as exc:
                raise ManifestError(f"element chunk is truncated: {exc}") from exc
            element = resolve(name_index)
            cursor = position + 16 + attribute_start
            pairs: list[tuple[str, str, int]] = []
            for a in range(attribute_count):
                try:
                    at = cursor + a * attribute_size
                    attribute_name, raw_index = struct.unpack_from("<ii", blob, at + 4)
                    value_type = struct.unpack_from("<B", blob, at + 15)[0]
                    value_data = struct.unpack_from("<I", blob, at + 16)[0]
                except struct.error as exc:
                    raise ManifestError(f"attribute {a} is truncated: {exc}") from exc
                text = (resolve(raw_index) if raw_index >= 0
                        else _typed_text(value_type, value_data))
                pairs.append((resolve(attribute_name), text, value_type))
            if element == "manifest":
                for key, value, _type in pairs:
                    if key == "package":
                        package = value
            elif element == "meta-data":
                # Both attributes here are called "name" and "value": "name" is
                # the meta-data key and "value" is what it holds.
                key = next((v for n, v, _t in pairs if n == "name"), None)
                held = next(((v, t) for n, v, t in pairs if n == "value"), None)
                if key and held:
                    meta[key] = held
        position += size
    return AndroidManifest(package=package, meta=meta)


def manifest_of(apk) -> AndroidManifest:
    """The manifest inside an APK, read from the archive."""
    try:
        with zipfile.ZipFile(apk) as archive:
            blob = archive.read("AndroidManifest.xml")
    except KeyError as exc:
        raise ManifestError("AndroidManifest.xml missing") from exc
    except zipfile.BadZipFile as exc:
        raise ManifestError(f"malformed APK archive: {exc}") from exc
    return _parse(blob)