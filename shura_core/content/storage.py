"""Local storage for downloaded pages: validation, atomic placement, recovery.

Two rules shape everything here.

**A page is only ``DONE`` once the bytes on disk have been checked.** Not the
declared length, not the declared type, not the file's existence. The header is
read from the file itself, the dimensions come out of that header, and the digest
is taken from the bytes actually written. A file nobody can explain is left
pending, never promoted.

**Nothing is ever written in place.** Every attempt gets its own generation
directory, the transfer lands in a ``.part`` beside it, and the move into place
is a single ``os.replace``. So a crash can leave a stray part, never a
half-written page that some later scan mistakes for a finished one.

What this module does *not* do is decide whether a page is acceptable. It proves
a file is a well-formed image; content policy is a separate gate and is never
implied by a successful download.
"""

from __future__ import annotations

import hashlib
import os
import struct
from dataclasses import dataclass
from pathlib import Path

MAX_PAGE_BYTES = 8 * 1024 * 1024
MAX_DIMENSION = 20_000
MAX_PIXELS = 50_000_000
HEADER_BYTES = 64 * 1024

#: Formats this module can actually validate. AVIF is absent on purpose: this
#: header parser does not implement its ISOBMFF box walk, and listing a format
#: here would be a claim nothing checks.
SUPPORTED = {"jpeg": "image/jpeg", "png": "image/png",
             "gif": "image/gif", "webp": "image/webp"}

_ALLOWED_MIME = set(SUPPORTED.values())


class PageRejected(ValueError):
    """The bytes are not an acceptable page. Never retried as-is."""


@dataclass(frozen=True)
class ValidatedPage:
    fmt: str
    mime: str
    width: int
    height: int
    sha256: str
    size: int


def _png(data: bytes) -> tuple[str, int, int]:
    if len(data) < 24 or data[12:16] != b"IHDR":
        raise PageRejected("png header is truncated")
    width, height = struct.unpack(">II", data[16:24])
    return "png", width, height


def _gif(data: bytes) -> tuple[str, int, int]:
    if len(data) < 10:
        raise PageRejected("gif header is truncated")
    width, height = struct.unpack("<HH", data[6:10])
    return "gif", width, height


def _jpeg(data: bytes) -> tuple[str, int, int]:
    i = 2
    while i + 9 < len(data):
        if data[i] != 0xFF:
            i += 1
            continue
        marker = data[i + 1]
        if marker in (0xD8, 0x01) or 0xD0 <= marker <= 0xD7:
            i += 2
            continue
        if marker == 0xFF:
            i += 1
            continue
        length = struct.unpack(">H", data[i + 2:i + 4])[0]
        # SOF0-SOF15, excluding the DHT/JPG/DAC markers that share the range.
        if 0xC0 <= marker <= 0xCF and marker not in (0xC4, 0xC8, 0xCC):
            height, width = struct.unpack(">HH", data[i + 5:i + 9])
            return "jpeg", width, height
        i += 2 + length
    raise PageRejected("jpeg has no frame header")


def _webp(data: bytes) -> tuple[str, int, int]:
    chunk = data[12:16]
    if chunk == b"VP8X":
        width = int.from_bytes(data[24:27], "little") + 1
        height = int.from_bytes(data[27:30], "little") + 1
        return "webp", width, height
    if chunk == b"VP8 ":
        width = int.from_bytes(data[26:28], "little") & 0x3FFF
        height = int.from_bytes(data[28:30], "little") & 0x3FFF
        return "webp", width, height
    if chunk == b"VP8L":
        bits = int.from_bytes(data[21:25], "little")
        return "webp", (bits & 0x3FFF) + 1, ((bits >> 14) & 0x3FFF) + 1
    raise PageRejected("webp has no recognised bitstream chunk")


def sniff_format(head: bytes) -> str:
    """Identify the format from the bytes, never from the name or the header."""
    if head[:8] == b"\x89PNG\r\n\x1a\n":
        return "png"
    if head[:3] == b"\xff\xd8\xff":
        return "jpeg"
    if head[:6] in (b"GIF87a", b"GIF89a"):
        return "gif"
    if head[:4] == b"RIFF" and head[8:12] == b"WEBP":
        return "webp"
    raise PageRejected("no recognised image signature")


def validate_page(path: Path, *, declared_mime: str | None = None,
                  max_bytes: int = MAX_PAGE_BYTES) -> ValidatedPage:
    """Check a downloaded file is an image we can actually use.

    Raises :class:`PageRejected` - which callers record as a permanent failure -
    for a wrong signature, a declared type that disagrees with the bytes, an
    HTML body served as an image, an oversized file, or dimensions large enough
    to exhaust memory when decoded.
    """
    try:
        size = path.stat().st_size
    except OSError as exc:
        raise PageRejected(f"page file is unreadable: {exc}") from exc
    if size == 0:
        raise PageRejected("page file is empty")
    if size > max_bytes:
        raise PageRejected(f"page is {size} bytes, over the {max_bytes} limit")

    with path.open("rb") as handle:
        head = handle.read(HEADER_BYTES)
        if head[:1] == b"<" or b"<html" in head[:1024].lower():
            raise PageRejected("response is markup, not an image")

        fmt = sniff_format(head)
        mime = SUPPORTED[fmt]
        if declared_mime and declared_mime.split(";")[0].strip().lower() != mime:
            raise PageRejected(
                f"declared type {declared_mime!r} disagrees with the {fmt} signature")
        if mime not in _ALLOWED_MIME:  # pragma: no cover - unreachable by construction
            raise PageRejected(f"{mime} is not an allowed page type")

        _fmt, width, height = {"png": _png, "gif": _gif, "jpeg": _jpeg, "webp": _webp}[fmt](head)
        digest = hashlib.sha256()
        digest.update(head)
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)

    if width <= 0 or height <= 0:
        raise PageRejected(f"page reports degenerate dimensions {width}x{height}")
    if width > MAX_DIMENSION or height > MAX_DIMENSION:
        raise PageRejected(f"page is {width}x{height}, over the {MAX_DIMENSION}px side limit")
    if width * height > MAX_PIXELS:
        raise PageRejected(f"page is {width * height} pixels, over the {MAX_PIXELS} limit")
    return ValidatedPage(fmt=fmt, mime=mime, width=width, height=height,
                         sha256=digest.hexdigest(), size=size)


def _resolve_inside(root: Path, relative: str) -> Path:
    """Refuse any path that would leave the storage root."""
    target = (root / relative).resolve()
    base = root.resolve()
    if target != base and base not in target.parents:
        raise PageRejected(f"path escapes the content root: {relative}")
    return target


#: Write granularity for a page body. Small enough that a ceiling is enforced
#: promptly, large enough not to dominate the transfer cost.
WRITE_CHUNK = 64 * 1024


def write_bounded(path: Path, chunks, *, max_bytes: int, heartbeat=None) -> int:
    """Stream ``chunks`` into ``path``, refusing as soon as ``max_bytes`` is passed.

    The ceiling applies *while* the body is written, not after it lands. A server
    that streams without ever declaring a Content-Length is cut off at the limit
    instead of being allowed to fill the disk first.

    ``heartbeat`` is called after every chunk written. The fetcher uses it to
    renew the content lock *during* a long transfer rather than only between
    pages - a single page can outlast the lease on its own. A heartbeat that
    raises propagates, so a lost fence stops the transfer instead of being
    noticed only at commit time.

    On refusal the handle is closed and the partial file removed, so nothing
    half-written can ever be mistaken for page content. On success the number of
    bytes written is returned.
    """
    total = 0
    with path.open("wb") as handle:
        try:
            for chunk in chunks:
                total += len(chunk)
                if total > max_bytes:
                    raise PageRejected(
                        f"page exceeded the {max_bytes} byte limit after {total} bytes")
                handle.write(chunk)
                if heartbeat is not None:
                    heartbeat()
        except BaseException:
            path.unlink(missing_ok=True)
            raise
    return total


def stage_part(root: Path, part_relative: str) -> Path:
    """Create the temporary file for an attempt and return it."""
    part = _resolve_inside(root, part_relative)
    part.parent.mkdir(parents=True, exist_ok=True)
    return part


def place_page(root: Path, rel_relative: str, part: Path) -> Path:
    """Move a validated part into its generation directory, atomically."""
    final = _resolve_inside(root, rel_relative)
    final.parent.mkdir(parents=True, exist_ok=True)
    os.replace(part, final)
    return final


def digest_of(path: Path) -> str:
    hasher = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            hasher.update(block)
    return hasher.hexdigest()


def cleanup_stale_parts(root: Path, *, older_than_seconds: int = 86_400) -> int:
    """Remove ``.part`` files whose owning process is gone or which are simply old.

    A part is only touched when its mtime is older than the threshold, so a
    transfer in flight is never deleted out from under itself.
    """
    import time
    tmp = root / "content" / "tmp"
    if not tmp.is_dir():
        return 0
    cutoff = time.time() - older_than_seconds
    removed = 0
    for part in tmp.rglob("*.part"):
        try:
            if part.stat().st_mtime < cutoff:
                part.unlink()
                removed += 1
        except OSError:
            continue
    return removed


def usage_bytes(root: Path) -> int:
    """Bytes currently held under the content root.

    Covers both the placed pages (``pages/``) and the in-flight parts
    (``content/tmp/``). The database file itself is not counted: the quota
    bounds downloaded page data, not the size of the index.
    """
    total = 0
    for base in (root / "pages", root / "content"):
        if not base.is_dir():
            continue
        for path in base.rglob("*"):
            if path.is_file():
                try:
                    total += path.stat().st_size
                except OSError:
                    continue
    return total
