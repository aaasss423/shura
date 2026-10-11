"""Local, offline ingest of a content manifest into the content store.

This is the missing half of the content pipeline. The fetch half exists and is
gated; nothing was producing the rows it consumes. This module adds exactly one
way in - a versioned manifest file read from disk - and nothing else: no
crawler, no listing parser, no network, no automatic clearance.

Three things it deliberately does not do:

* **It never grants clearance.** Importing a chapter says nothing about whether
  its pages may be fetched. :func:`grant_clearance` is the only writer of
  ``element_clearance``, and this module never calls it.
* **It never downloads.** Nothing here opens a socket.
* **It never makes ``source_page_url`` an identity.** The schema separates a
  page's mutable location from its identity (``source_page_id`` /
  ``stable_page_url`` / ``page_key_kind``), and the import derives
  ``page_key_kind`` from those columns rather than trusting a URL that merely
  appeared in a listing.

Writes are atomic and repeatable: one transaction, and every insert keys on the
table's real primary key. Re-importing the same manifest converges rather than
duplicating, and never disturbs fetch evidence (``state``, ``rel_path``,
``sha256``, ``verified_at``...).
"""

from __future__ import annotations

import json
from pathlib import Path

from ..security.network import validate_url

__all__ = ["ImportRejected", "MANIFEST_VERSION", "import_manifest", "import_payload",
           "manifest_summary"]

MANIFEST_VERSION = 1

#: Mirrors the CHECK constraint on works.content_classification. NULL is allowed
#: by the schema and means "not classified", which the gate treats as not
#: permitted; it is not a licence.
CLASSIFICATIONS = ("SAFE", "MATURE", "NSFW", "MIXED", "UNKNOWN")


class ImportRejected(ValueError):
    """The manifest is not importable. Nothing has been written."""


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise ImportRejected(message)


def _text(value, field: str) -> str:
    if value is None:
        return ""
    _require(isinstance(value, str), f"{field} must be a string")
    return value


def _optional_text(value, field: str) -> str | None:
    if value is None:
        return None
    _require(isinstance(value, str) and value.strip() != "",
             f"{field} must be a non-empty string or null")
    return value


def _int(value, field: str, *, minimum: int | None = None) -> int:
    _require(isinstance(value, int) and not isinstance(value, bool),
             f"{field} must be an integer")
    if minimum is not None:
        _require(value >= minimum, f"{field} must be >= {minimum}")
    return value


def _optional_int(value, field: str, *, minimum: int | None = None) -> int | None:
    return None if value is None else _int(value, field, minimum=minimum)


def _optional_real(value, field: str) -> float | None:
    """``work_chapters.chapter_number`` is declared REAL, so a float is legal."""
    if value is None:
        return None
    _require(isinstance(value, (int, float)) and not isinstance(value, bool),
             f"{field} must be a number or null")
    return float(value)


def _flag(value, field: str) -> int:
    _require(value in (0, 1), f"{field} must be 0 or 1")
    return value


def _rows(raw, field: str) -> list[dict]:
    _require(isinstance(raw, list), f"{field} must be a list")
    for index, entry in enumerate(raw):
        _require(isinstance(entry, dict), f"{field}[{index}] must be an object")
    return raw


def _source_policy(conn, source_id: str) -> tuple[list[str], int]:
    row = conn.execute(
        "SELECT allowed_hosts, fetch_enabled FROM source_policy WHERE source_id=?",
        (source_id,)).fetchone()
    _require(row is not None,
             f"source {source_id!r} is not in source_policy; the manifest cannot introduce a source")
    try:
        hosts = json.loads(row["allowed_hosts"])
    except json.JSONDecodeError as exc:
        raise ImportRejected(f"source {source_id!r} has malformed allowed_hosts") from exc
    _require(isinstance(hosts, list) and hosts
             and all(isinstance(h, str) and h.strip() for h in hosts),
             f"source {source_id!r} has no usable allowed_hosts")
    return hosts, int(row["fetch_enabled"])


def _parse(raw, conn) -> tuple[str, list[dict], list[dict], list[dict], list[str], int]:
    """Validate the whole manifest and return it in normalised form."""
    _require(isinstance(raw, dict), "the manifest must be a JSON object")
    _require(raw.get("version") == MANIFEST_VERSION,
             f"manifest version must be {MANIFEST_VERSION}")

    source_id = _text(raw.get("source_id"), "source_id").strip()
    _require(source_id != "", "source_id is required")
    allowed_hosts, fetch_enabled = _source_policy(conn, source_id)

    works = _rows(raw.get("works", []), "works")
    chapters = _rows(raw.get("chapters", []), "chapters")
    pages = _rows(raw.get("pages", []), "pages")

    seen_work_ids: set[str] = set()
    for index, work in enumerate(works):
        work_id = _text(work.get("ext_work_id"), f"works[{index}].ext_work_id").strip()
        _require(work_id != "", f"works[{index}].ext_work_id is required")
        _require(work_id not in seen_work_ids, f"works[{index}].ext_work_id repeats")
        seen_work_ids.add(work_id)
        classification = work.get("content_classification")
        if classification is not None:
            _require(isinstance(classification, str)
                     and classification in CLASSIFICATIONS,
                     f"works[{index}].content_classification must be one of "
                     f"{CLASSIFICATIONS} or null")

    seen_chapter_ids: set[str] = set()
    for index, chapter in enumerate(chapters):
        chapter_id = _text(chapter.get("ext_chapter_id"),
                           f"chapters[{index}].ext_chapter_id").strip()
        _require(chapter_id != "", f"chapters[{index}].ext_chapter_id is required")
        _require(chapter_id not in seen_chapter_ids,
                 f"chapters[{index}].ext_chapter_id repeats")
        seen_chapter_ids.add(chapter_id)
        owner = _text(chapter.get("ext_work_id"), f"chapters[{index}].ext_work_id").strip()
        _require(owner in seen_work_ids,
                 f"chapters[{index}].ext_work_id {owner!r} is not in this manifest")
        _int(chapter.get("ordinal", 0), f"chapters[{index}].ordinal")
        _optional_real(chapter.get("chapter_number"), f"chapters[{index}].chapter_number")
        _int(chapter.get("page_count", 0) or 0, f"chapters[{index}].page_count", minimum=0)

    seen_page_keys: set[tuple[str, int]] = set()
    for index, page in enumerate(pages):
        chapter_id = _text(page.get("ext_chapter_id"), f"pages[{index}].ext_chapter_id").strip()
        _require(chapter_id in seen_chapter_ids,
                 f"pages[{index}].ext_chapter_id {chapter_id!r} is not in this manifest")
        page_index = _int(page.get("page_index"), f"pages[{index}].page_index", minimum=0)
        key = (chapter_id, page_index)
        _require(key not in seen_page_keys, f"pages[{index}] repeats chapter/page_index")
        seen_page_keys.add(key)
        _int(page.get("ordinal", page_index), f"pages[{index}].ordinal", minimum=0)

        # The location is checked against the source's own policy. This is the
        # source_policy table as written by content-sources.json, not a new rule.
        url = _text(page.get("source_page_url"), f"pages[{index}].source_page_url").strip()
        _require(url != "", f"pages[{index}].source_page_url is required")
        try:
            validate_url(url, allowed_hosts)
        except ValueError as exc:
            raise ImportRejected(
                f"pages[{index}].source_page_url is refused by this source's policy: {exc}"
            ) from exc

        # Identity is derived, never taken from the manifest's own idea of it.
        source_page_id = _optional_text(page.get("source_page_id"),
                                        f"pages[{index}].source_page_id")
        stable_page_url = _optional_text(page.get("stable_page_url"),
                                         f"pages[{index}].stable_page_url")
        if source_page_id is not None and stable_page_url is not None:
            raise ImportRejected(
                f"pages[{index}] offers both source_page_id and stable_page_url; "
                "the schema allows exactly one identity")

    return source_id, works, chapters, pages, allowed_hosts, fetch_enabled


def _write(conn, source_id: str, works: list[dict], chapters: list[dict],
           pages: list[dict]) -> dict:
    counts = {"works": 0, "work_chapters": 0, "chapter_pages": 0}
    conn.execute("BEGIN IMMEDIATE")
    try:
        for work in works:
            conn.execute(
                "INSERT INTO works(source_id,ext_work_id,source_work_id,title,source_url,"
                "content_classification,discovered_at,updated_at) VALUES(?,?,?,?,?,?,?,?) "
                "ON CONFLICT(source_id,ext_work_id) DO UPDATE SET "
                "source_work_id=excluded.source_work_id,title=excluded.title,"
                "source_url=excluded.source_url,"
                "content_classification=COALESCE(excluded.content_classification,"
                "works.content_classification),updated_at=excluded.updated_at",
                (source_id, work["ext_work_id"], _text(work.get("source_work_id"), "x"),
                 _text(work.get("title"), "x"), _text(work.get("source_url"), "x"),
                 work.get("content_classification"), _now(), _now()))
            counts["works"] += 1

        for chapter in chapters:
            conn.execute(
                "INSERT INTO work_chapters(source_id,ext_chapter_id,ext_work_id,"
                "source_chapter_id,chapter_number,is_special,ordinal,title,source_url,"
                "page_count,listing_hash,listing_epoch,first_seen,updated_at) "
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?) "
                "ON CONFLICT(source_id,ext_chapter_id) DO UPDATE SET "
                "ext_work_id=excluded.ext_work_id,"
                "source_chapter_id=excluded.source_chapter_id,"
                "chapter_number=excluded.chapter_number,"
                "is_special=excluded.is_special,ordinal=excluded.ordinal,"
                "title=excluded.title,source_url=excluded.source_url,"
                "page_count=excluded.page_count,listing_hash=excluded.listing_hash,"
                "listing_epoch=excluded.listing_epoch,updated_at=excluded.updated_at",
                (source_id, chapter["ext_chapter_id"], chapter["ext_work_id"],
                 _text(chapter.get("source_chapter_id"), "x"),
                 _optional_real(chapter.get("chapter_number"), "x"),
                 _flag(chapter.get("is_special", 0), "x"),
                 _int(chapter.get("ordinal", 0), "x", minimum=0),
                 _text(chapter.get("title"), "x"), _text(chapter.get("source_url"), "x"),
                 _optional_int(chapter.get("page_count"), "x", minimum=0),
                 _optional_text(chapter.get("listing_hash"), "x"),
                 _int(chapter.get("listing_epoch", 0), "x", minimum=0),
                 _now(), _now()))
            counts["work_chapters"] += 1

        for page in pages:
            source_page_id = _optional_text(page.get("source_page_id"), "x")
            stable_page_url = _optional_text(page.get("stable_page_url"), "x")
            if source_page_id is not None:
                key_kind = "source_page_id"
            elif stable_page_url is not None:
                key_kind = "stable_url"
            else:
                key_kind = "none"
            conn.execute(
                "INSERT INTO chapter_pages(source_id,ext_chapter_id,page_index,ordinal,"
                "source_page_id,source_page_url,stable_page_url,page_key_kind,listing_epoch,"
                "created_at) VALUES(?,?,?,?,?,?,?,?,?,?) "
                "ON CONFLICT(source_id,ext_chapter_id,page_index) DO UPDATE SET "
                "ordinal=excluded.ordinal,source_page_id=excluded.source_page_id,"
                "source_page_url=excluded.source_page_url,"
                "stable_page_url=excluded.stable_page_url,"
                "page_key_kind=excluded.page_key_kind,"
                "listing_epoch=excluded.listing_epoch",
                (source_id, page["ext_chapter_id"],
                 _int(page.get("page_index"), "x", minimum=0),
                 _int(page.get("ordinal", 0), "x", minimum=0),
                 source_page_id, _text(page.get("source_page_url"), "x"),
                 stable_page_url, key_kind,
                 _int(page.get("listing_epoch", 0), "x", minimum=0), _now()))
            counts["chapter_pages"] += 1
        conn.execute("COMMIT")
    except BaseException:
        conn.execute("ROLLBACK")
        raise
    return counts


def _now() -> str:
    from .store import _now as store_now
    return store_now()


def import_payload(conn, raw) -> dict:
    """Import an already-parsed manifest into the content store.

    This is the single write boundary. :func:`import_manifest` reads a file and
    calls it; the discovery connector builds the same structure in memory and
    calls it too. Neither can therefore take a path the other does not: there is
    one validation, one transaction and one set of upserts.

    Raises :class:`ImportRejected` - having written nothing.
    """
    source_id, works, chapters, pages, _hosts, _enabled = _parse(raw, conn)
    counts = _write(conn, source_id, works, chapters, pages)
    return {"source_id": source_id, **counts}


def import_manifest(conn, path: Path) -> dict:
    """Import one manifest file from disk into the content store.

    Raises :class:`ImportRejected` - having written nothing - if the file is
    missing, unreadable, malformed, names a source that is not in
    ``source_policy``, or offers a page URL the source's policy refuses.
    """
    path = Path(path)
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except OSError as exc:
        raise ImportRejected(f"cannot read manifest {path}: {exc}") from exc
    except json.JSONDecodeError as exc:
        raise ImportRejected(f"manifest {path} is not valid JSON: {exc}") from exc
    return import_payload(conn, raw)


def manifest_summary(path: Path) -> dict:
    """Read a manifest without writing anything, for a dry run."""
    path = Path(path)
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except OSError as exc:
        raise ImportRejected(f"cannot read manifest {path}: {exc}") from exc
    except json.JSONDecodeError as exc:
        raise ImportRejected(f"manifest {path} is not valid JSON: {exc}") from exc
    _require(isinstance(raw, dict), "the manifest must be a JSON object")
    _require(raw.get("version") == MANIFEST_VERSION,
             f"manifest version must be {MANIFEST_VERSION}")
    return {
        "version": MANIFEST_VERSION,
        "source_id": raw.get("source_id"),
        "works": len(raw.get("works", []) or []),
        "chapters": len(raw.get("chapters", []) or []),
        "pages": len(raw.get("pages", []) or []),
        "dryRun": True,
    }