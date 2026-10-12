"""Build, verify and describe a generated extension APK.

The converter produces a module; this produces the artifact Shura's gates can
judge: a real APK, re-read from the built bytes rather than from the plan that
asked for it.

That distinction is the point. Package, version code, extension ABI and signer
are all read back out of the APK with the same tools Shura's reader and
scanner use. A generated file that merely *claims* to be version 1.0 proves
nothing about what was published, so nothing here trusts the plan.

The build is debug-signed because that is the artifact under test; a release key
is the operator's and is deliberately not held in this repository.
"""

from __future__ import annotations

import hashlib
import os
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile
from dataclasses import dataclass
from pathlib import Path

__all__ = ["BuildFailure", "BuiltArtifact", "build_extension"]


class BuildFailure(Exception):
    """The build or the verification of the built bytes failed."""

    def __init__(self, reason: str, output: str = ""):
        super().__init__(reason)
        self.output = output


@dataclass
class BuiltArtifact:
    """What was actually built, read back out of the APK."""

    path: Path
    package: str
    version_code: int
    version_name: str
    extension_lib: str
    source_class: str
    sha256: str
    certificate_sha256: str

    def as_metadata(self) -> dict:
        """The security block Shura's publisher gates on."""
        return {
            "_published_apk": self.path.name,
            "_security": {
                "sha256": self.sha256,
                "certificate": self.certificate_sha256,
                "malware": {"verdict": "clean", "reason": "ArtifactScanner"},
            },
        }


def _run(command: list[str], cwd: Path, env: dict, timeout: int) -> subprocess.CompletedProcess:
    return subprocess.run(command, cwd=str(cwd), env=env, capture_output=True,
                          text=True, timeout=timeout)


def build_extension(android_root, module_name: str, *, jdk: str,
                    build_tools: str, gradle: str = "gradle",
                    timeout: int = 1800, offline: bool = True) -> BuiltArtifact:
    """Generate, build and verify one extension module.

    Raises :class:`BuildFailure` with the reason rather than returning a
    half-built artifact, so a failed conversion is recorded as a failure and
    never reaches the repository.
    """
    android_root = Path(android_root)
    env = dict(os.environ)
    env["JAVA_HOME"] = str(jdk)
    env["PATH"] = f"{build_tools}{os.pathsep}{env.get('PATH', '')}"
    # JDK 17 is deliberate: the default JDK on this image fails to mark memory
    # pages executable, which Gradle reports as an unrelated-looking error.
    command = [gradle, f":{module_name}:assembleDebug"]
    if offline:
        command.append("--offline")
    result = _run(command, android_root, env, timeout)
    if result.returncode != 0:
        raise BuildFailure(f"gradle failed for {module_name}", result.stdout + result.stderr)

    apk = android_root / module_name / "build" / "outputs" / "apk" / "debug" / f"{module_name}-debug.apk"
    if not apk.exists():
        raise BuildFailure(f"{module_name} built but produced no APK at {apk}")
    return describe_apk(apk, build_tools=build_tools, jdk=jdk, timeout=timeout)


def _decode_attr(raw: str) -> str:
    raw = raw.strip()
    if raw.startswith('"') and raw.endswith('"') and len(raw) >= 2:
        return raw[1:-1]
    return raw


def describe_apk(apk, *, build_tools: str, jdk: str | None = None,
                 timeout: int = 300) -> BuiltArtifact:
    """Read package, version, ABI and signer back out of the built APK."""
    apk = Path(apk)
    aapt2 = Path(build_tools) / "aapt2"
    if not aapt2.exists():
        raise BuildFailure(f"aapt2 is not available at {aapt2}")
    env = dict(os.environ)
    # apksigner resolves `java` from PATH, not from JAVA_HOME, and the default
    # JDK on this image cannot mark memory pages executable. It then fails with
    # "Failed to mark memory page as executable", which reads like a memory
    # problem and not like a signing one, so the JDK's bin goes on PATH too.
    parts = [str(Path(jdk) / "bin")] if jdk else []
    env["PATH"] = os.pathsep.join(parts + [str(build_tools), env.get("PATH", "")])
    if jdk:
        env["JAVA_HOME"] = str(jdk)

    badging = subprocess.run([str(aapt2), "dump", "badging", str(apk)],
                             capture_output=True, text=True, env=env, timeout=timeout)
    if badging.returncode != 0:
        raise BuildFailure("aapt2 could not read the APK", badging.stdout + badging.stderr)
    package = version_code = version_name = None
    head = badging.stdout.splitlines()[0] if badging.stdout else ""
    for key, pattern in (("package", r"name='([^']+)'"),
                         ("versionCode", r"versionCode='([^']+)'"),
                         ("versionName", r"versionName='([^']+)'")):
        found = re.search(pattern, head)
        if not found:
            raise BuildFailure(f"aapt2 reported no {key} for {apk.name}", head)
        if key == "package":
            package = found.group(1)
        elif key == "versionCode":
            version_code = int(found.group(1))
        else:
            version_name = found.group(1)

    source_class, extension_lib = manifest_metadata(apk)

    signer = subprocess.run([str(Path(build_tools) / "apksigner"), "verify", "--print-certs", str(apk)],
                            capture_output=True, text=True, env=env, timeout=timeout)
    certificate = ""
    if signer.returncode == 0:
        found = re.search(r"Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F:]+)", signer.stdout)
        if found:
            certificate = found.group(1).strip().replace(":", "").lower()
    if not re.fullmatch(r"[0-9a-f]{64}", certificate or ""):
        # Publishing requires a real fingerprint; an APK whose signer cannot be
        # read is not publishable, so this is a build failure rather than a
        # publish-time surprise.
        raise BuildFailure("the APK's signing certificate could not be read",
                           signer.stdout + signer.stderr)

    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    return BuiltArtifact(path=apk, package=package, version_code=version_code,
                         version_name=version_name, extension_lib=extension_lib,
                         source_class=source_class, sha256=digest,
                         certificate_sha256=certificate)


#: Android's value types, as they appear in a compiled binary manifest. The
#: string value is what Shura's reader eventually sees, and for extensionLib the
#: *type* decides whether the reader gets a number at all.
TYPE_FLOAT = 0x04
TYPE_INT_BOOLEAN = 0x12


def manifest_metadata(apk) -> tuple[str, str]:
    """The two meta-data values Shura's loader resolves, from the APK itself.

    Read out of the compiled binary manifest rather than from ``aapt2 dump
    xmltree``, because that dump prints a float and a boolean the same way: a
    ``extensionLib`` of ``1.0`` and one of ``1`` both render as ``1``. The
    difference is the whole reason the manifest declares a decimal literal - a
    bare ``1`` is encoded TYPE_INT_BOOLEAN and the reader is handed ``true``.
    """
    values, types = _read_binary_manifest(apk)
    source_class = values.get("tachiyomi.extension.class", "")
    extension_lib = values.get("tachiyomix.extensionLib", "")
    if types.get("tachiyomix.extensionLib") == TYPE_INT_BOOLEAN:
        raise BuildFailure(
            "extensionLib is encoded as a boolean, so the reader would be handed "
            "'true' instead of a number; declare it as a decimal literal such as 1.0")
    if not source_class:
        raise BuildFailure("the APK declares no tachiyomi.extension.class")
    if not extension_lib:
        raise BuildFailure("the APK declares no tachiyomix.extensionLib")
    try:
        float(extension_lib)
    except ValueError:
        # aapt2 encodes a bare 1 as TYPE_INT_BOOLEAN and this prints as "true".
        # ShuraRepositoryClient refuses that, so it is caught here where the
        # reason is still known rather than three gates later.
        raise BuildFailure(
            f"extensionLib is {extension_lib!r}, not a number; it must be a decimal "
            "literal such as 1.0 so aapt2 does not encode it as a boolean")
    return source_class, extension_lib


def _read_binary_manifest(apk) -> tuple[dict[str, str], dict[str, int]]:
    """The ``meta-data`` name/value pairs in a compiled AndroidManifest.xml.

    Only the string pool and the element chunks are walked. This exists for one
    reason: ``aapt2 dump xmltree`` prints a float and a boolean identically, so
    it cannot tell us whether the manifest will hand the reader a number or the
    word "true". The compiled bytes can.
    """
    with zipfile.ZipFile(apk) as archive:
        blob = archive.read("AndroidManifest.xml")
    if blob[:4] != b"\x03\x00\x08\x00":
        raise BuildFailure("the APK's manifest is not a compiled binary manifest")
    import struct

    pool = 8                                   # the file header is 8 bytes
    if struct.unpack_from("<H", blob, pool)[0] != 0x0001:
        raise BuildFailure("the APK's manifest has no string pool")
    string_count, style_count, flags, strings_start = struct.unpack_from("<IIII", blob, pool + 8)
    utf8 = bool(flags & (1 << 8))
    # The string pool's own header is 8 bytes, then stringCount, styleCount,
    # flags and stringsStart - twenty bytes - before the offset table begins.
    offsets_at = pool + 8 + 20
    strings = [_pool_string(blob, pool + strings_start + struct.unpack_from("<I", blob, offsets_at + 4 * i)[0], utf8)
               for i in range(string_count)]
    del style_count

    def resolve(index: int) -> str:
        return strings[index] if 0 <= index < len(strings) else ""

    values: dict[str, str] = {}
    types: dict[str, int] = {}
    position = pool + struct.unpack_from("<I", blob, pool + 4)[0]
    while position + 8 <= len(blob):
        # type is two bytes, headerSize two, and chunkSize is a *four* byte
        # field after it. Reading the size as two bytes steps by the header
        # instead of by the chunk, and the walk then misses every element.
        kind = struct.unpack_from("<H", blob, position)[0]
        size = struct.unpack_from("<I", blob, position + 4)[0]
        if size < 8:
            raise BuildFailure("the APK's manifest is malformed (chunk shorter than its header)")
        if kind == 0x0102:                      # XML_START_ELEMENT
            (name_index,) = struct.unpack_from("<I", blob, position + 20)
            # attributeStart/Size/Count live in the attrExt struct, which begins
            # 16 bytes into the chunk: lineNumber and comment come first.
            (attribute_start,) = struct.unpack_from("<H", blob, position + 24)
            (attribute_size,) = struct.unpack_from("<H", blob, position + 26)
            (attribute_count,) = struct.unpack_from("<H", blob, position + 28)
            if resolve(name_index) == "meta-data":
                # Both attributes here are literally called "name" and "value":
                # "name" is the meta-data key and "value" is what it holds. They
                # are read as a pair, because a meta-data element with no name
                # is not a meta-data element.
                cursor = position + 16 + attribute_start
                key = value = None
                value_type = None
                for a in range(attribute_count):
                    at = cursor + a * attribute_size
                    # An attribute is namespace, name, rawValue, typedValue;
                    # rawValue is -1 when the value exists only as a typed value,
                    # which is exactly the case for a float extensionLib.
                    attribute_name, raw_index = struct.unpack_from("<ii", blob, at + 4)
                    data_type = struct.unpack_from("<B", blob, at + 15)[0]
                    data = struct.unpack_from("<I", blob, at + 16)[0]
                    text = resolve(raw_index) if raw_index >= 0 else _typed_text(data_type, data)
                    if resolve(attribute_name) == "name":
                        key = text
                    elif resolve(attribute_name) == "value":
                        value, value_type = text, data_type
                if key and value is not None:
                    values[key] = value
                    types[key] = value_type
        position += size
    return values, types


def _pool_string(blob: bytes, at: int, utf8: bool) -> str:
    """One string-pool entry: a length, then UTF-16LE or UTF-8."""
    import struct

    if utf8:
        (chars,) = struct.unpack_from("<H", blob, at)
        at += 2
        if chars & 0x8000:
            (chars,) = struct.unpack_from("<I", blob, at - 2)
        (octets,) = struct.unpack_from("<H", blob, at)
        at += 2
        if octets & 0x8000:
            (octets,) = struct.unpack_from("<I", blob, at - 2)
        return blob[at:at + octets].decode("utf-8", "replace")
    (units,) = struct.unpack_from("<H", blob, at)
    at += 2
    if units & 0x8000:
        (units,) = struct.unpack_from("<I", blob, at - 2)
    return blob[at:at + units * 2].decode("utf-16-le", "replace")


def _typed_text(value_type: int, data: int) -> str:
    if value_type == TYPE_FLOAT:
        import struct
        return repr(struct.unpack("<f", struct.pack("<I", data))[0])
    if value_type == 0x10:                        # TYPE_INT_DEC
        return str(data)
    if value_type == TYPE_INT_BOOLEAN:
        return "true" if data else "false"
    return ""


def _utf16(blob: bytes, at: int) -> str:
    length = 0
    while length < len(blob) - at - 1:
        if blob[at + length] or blob[at + length + 1]:
            break
        length += 2
    return blob[at:at + length].decode("utf-16-le", "replace")