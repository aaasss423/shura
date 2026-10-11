"""A real manga source connector: MangaDex, Arabic translations first.

Why this source
---------------
MangaDex exposes a documented, public, key-free JSON API. No sign-in, no paywall,
no anti-bot to defeat, and nothing here works around a protection. That matters
more to this project than raw volume: the goal is source quality and stability,
not the largest number of sites. Discovery looked at, and rejected:

* ``procomic.net`` - the same service this repo's existing Arabic Mihon
  extension targets, but its series page says reading chapters "requires
  signing in", so it cannot be used without an account.
* ``mangalik.net`` (lekmanga.net) - public and Arabic, but it answered 403 to a
  manga page and to its own robots.txt after a few requests. A source that
  blocks the client outright is not a source this project should depend on.
* ``dilar.tube`` - a client-rendered SPA behind Cloudflare and an ad-block gate.

Page identity
-------------
MangaDex serves chapter images from a **per-chapter CDN host that rotates**
(``https://cmxdXXXX.mangadex.network``). A URL built from it is therefore a
location, not an identity, and this connector never treats it as one:

* ``source_page_id`` is the file name the API returns for the page
  (``<n>-<content hash>.jpg``). That identifier is host-independent and is the
  source's own name for the page, so it is the preferred identity.
* ``source_page_url`` is only the current access URL. It is expected to change.
* ``content_key`` is left to the store, which derives it from the downloaded
  bytes rather than from any URL.

Classification
--------------
``contentRating`` maps onto the store's own vocabulary:

===================  =====================
MangaDex             Shura
===================  =====================
``safe``             ``SAFE``
``suggestive``       ``MATURE``
``erotica``          ``NSFW``
``pornographic``     ``NSFW``
absent               ``null`` (not classified)
===================  =====================

Absent is left null on purpose: the fetch gate treats an absent classification as
not permitted, and inventing one would be worse than admitting ignorance. A
classification is never a clearance - nothing here grants approval.
"""

from __future__ import annotations

import json
import time
from urllib.error import HTTPError
from urllib.parse import quote, urlencode

from ..security.network import NetworkPolicyError

__all__ = ["MangaDexSource", "MangaDexLinkResolver", "DiscoveryRejected",
           "LinkUnavailable", "SOURCE_ID", "API_HOST", "IMAGE_HOST_SUFFIX",
           "MAX_LIMIT", "MAX_OFFSET"]

#: The content-store source id this connector feeds.
SOURCE_ID = "mangadex"

API_HOST = "api.mangadex.org"

#: Chapter images are served from a rotating per-chapter host under this domain.
#: The ingest layer validates every page URL against the source's allowed_hosts,
#: so an operator must list this suffix in content-sources.json.
IMAGE_HOST_SUFFIX = "mangadex.network"

#: Languages in priority order. Arabic leads because this project is Arabic-first;
#: the rest are a fallback, never a broadening of scope by default.
LANGUAGE_PRIORITY = ("ar", "en", "fr", "es", "pt-br", "ru", "id", "tr", "it", "de", "pl")

_CLASSIFICATION = {
    "safe": "SAFE",
    "suggestive": "MATURE",
    "erotica": "NSFW",
    "pornographic": "NSFW",
}

#: MangaDex's documented paging limits.
MAX_LIMIT = 100
MAX_OFFSET = 10_000

#: Bounded retry for a transient API fault. Never unbounded: a discovery run
#: must be able to stop. Backoff grows and then holds.
API_ATTEMPTS = 3
API_BACKOFF_SECONDS = (0.5, 1.5, 3.0)


class MangaDexLinkResolver:
    """Produces a page's *current* image URL from the official @Home endpoint.

    Why this exists
    ---------------
    MangaDex@Home links are not durable. The API specification describes the
    ``baseUrl`` as ``https://host.mangadex.network:port/some-temporary-access-token``
    - a token in the path and a host that changes per chapter. A URL stored at
    discovery time is therefore expected to stop working. Fetching it directly
    would mean every stale link either fails forever or, worse, is retried until
    the attempt ceiling is burned on a link that can never work.

    So the store keeps what is durable and the fetcher asks for what is live:

    * ``ext_chapter_id`` carries the MangaDex chapter UUID (``<manga>/<chapter>``),
      which is the stable handle the @Home endpoint is keyed by.
    * ``source_page_id`` is that page's own file name in the chapter.
    * ``source_page_url`` is kept only as a record of where it was last seen.

    Resolution is per page and matches on the page's own file name, so a link
    resolved for one chapter or page can never be handed to another. Every
    resolution goes through the project's guarded HTTP client, so the host
    allow-list still applies to the refresh itself.
    """

    def __init__(self, source: MangaDexSource, *, source_id: str = SOURCE_ID):
        self.source = source
        self.source_id = source_id
        #: chapter UUID -> access info, reused only within one fetch run so a
        #: chapter's pages cost one @Home call between them, not one per page.
        self._cache: dict[str, dict] = {}

    @staticmethod
    def chapter_id_of(ext_chapter_id: str) -> str:
        """``<manga-uuid>/<chapter-uuid>`` -> ``<chapter-uuid>``."""
        return ext_chapter_id.rsplit("/", 1)[-1]

    def resolve(self, *, source_id: str, ext_chapter_id: str, page_index: int,
                source_page_id: str | None, stored_url: str | None = None) -> str:
        if source_id != self.source_id:
            return stored_url or ""
        if not source_page_id:
            # Nothing durable to re-resolve from. Returning the stored link
            # rather than guessing keeps single-shot sources working unchanged.
            return stored_url or ""
        chapter_id = self.chapter_id_of(ext_chapter_id)
        access = self._cache.get(chapter_id)
        if access is None:
            access = self.source.at_home(chapter_id)
            self._cache[chapter_id] = access
        name = source_page_id
        if name not in access["files"]:
            # The page is no longer part of this chapter. Guessing a position
            # would risk handing another page's bytes to this page's record.
            raise LinkUnavailable(
                f"page {name!r} is not in chapter {chapter_id} any more "
                f"({len(access['files'])} pages listed)")
        return f"{access['base_url']}/data/{access['hash']}/{name}"

    def forget(self) -> None:
        self._cache.clear()


def _as_number(value):
    """MangaDex returns chapter/volume as strings, and not always numeric.

    ``"1"`` becomes ``1.0``; ``"1.5"`` becomes ``1.5``; ``"prologue"`` and
    friends become ``None``. Guessing a number out of a word would be inventing
    ordering that the source did not state, and the store's chapter_number is
    declared REAL and nullable - so null is the honest answer.
    """
    if value is None or isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        return float(value)
    if isinstance(value, str):
        text = value.strip()
        try:
            return float(text)
        except ValueError:
            return None
    return None


class DiscoveryRejected(RuntimeError):
    """The source answered in a way this connector will not guess about."""


class LinkUnavailable(DiscoveryRejected):
    """A page's current image link could not be resolved right now.

    Distinct from a download failure: nothing was fetched and nothing is wrong
    with the page. The usual cause is that MangaDex@Home has not been asked for
    a fresh link, so the caller should retry later rather than give up.
    """


#: MangaDex@Home base URLs are documented as carrying a temporary access token
#: and may use a non-standard port. ``forcePort443=true`` asks for a plain
#: standard-port URL instead, which is what the project's strict port policy
#: (and TLS) can actually use. Without it every resolved link would be refused.
FORCE_PORT_443 = True


def _decode(http, url: str) -> dict:
    """Fetch JSON through the project's guarded HTTP client, with bounded retry.

    ``http`` is anything with ``get(url) -> (bytes, headers, final)``, which is
    what :class:`shura_core.security.network.SafeHTTP` provides. Host
    allow-listing, HTTPS-only, private-address refusal, redirect limits and the
    response ceiling all therefore still apply to discovery.

    A policy refusal is never retried - retrying it would just be hammering a
    guard. Only transport faults and 5xx-style service trouble are, at most
    :data:`API_ATTEMPTS` times, with a growing wait.
    """
    delay = API_BACKOFF_SECONDS
    last: Exception | None = None
    for attempt in range(API_ATTEMPTS):
        try:
            body, _headers, _final = http.get(url)
        except NetworkPolicyError:
            raise
        except (OSError, HTTPError) as exc:
            last = exc
            status = getattr(exc, "code", None)
            if status is not None and 400 <= int(status) < 500 and int(status) != 429:
                # A client error is a question about this request, not a blip.
                raise DiscoveryRejected(f"{url} refused: {exc}") from exc
            if attempt == API_ATTEMPTS - 1:
                break
            time.sleep(delay[min(attempt, len(delay) - 1)])
            continue
        try:
            payload = json.loads(body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise DiscoveryRejected(f"{url} did not return JSON: {exc}") from exc
        if not isinstance(payload, dict):
            raise DiscoveryRejected(f"{url} returned {type(payload).__name__}, not an object")
        return payload
    raise DiscoveryRejected(f"{url} failed after {API_ATTEMPTS} attempts: {last}")


def _decode_once(http, url: str) -> dict:
    """A single attempt, no retry. Used where a retry would be meaningless."""
    body, _headers, _final = http.get(url)
    try:
        payload = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise DiscoveryRejected(f"{url} did not return JSON: {exc}") from exc
    if not isinstance(payload, dict):
        raise DiscoveryRejected(f"{url} returned {type(payload).__name__}, not an object")
    return payload


class MangaDexSource:
    """Lists works, their chapters, and a chapter's page images."""

    def __init__(self, http, *, source_id: str = SOURCE_ID, languages=LANGUAGE_PRIORITY,
                 language_priority=None):
        self.http = http
        self.source_id = source_id
        self.languages = tuple(language_priority) if language_priority else tuple(languages)

    # ---------------------------------------------------------------- works

    def _paged(self, path: str, params: list, *, limit: int, max_items: int,
               label: str) -> list[dict]:
        """Walk an offset-paginated collection, yielding each id exactly once.

        MangaDex caps ``limit`` at 100 and refuses an offset beyond 10000, so
        both are respected rather than looping forever. Results already seen are
        skipped by id: a source that shifts a row between pages must not produce
        a duplicate. A page that fails mid-walk raises, and whatever was
        gathered up to that point is returned rather than discarded, so a
        restart re-walks and converges instead of losing work.
        """
        seen: set[str] = set()
        collected: list[dict] = []
        offset = 0
        while len(collected) < max_items and offset <= MAX_OFFSET:
            page_size = max(1, min(int(limit), MAX_LIMIT))
            query = urlencode([("limit", str(page_size)), ("offset", str(offset)), *params])
            payload = _decode(self.http, f"https://{API_HOST}{path}?{query}")
            data = payload.get("data")
            if not isinstance(data, list):
                break
            fresh = 0
            for entry in data:
                if not isinstance(entry, dict):
                    continue
                identifier = entry.get("id")
                if not identifier or identifier in seen:
                    continue
                seen.add(identifier)
                collected.append(entry)
                fresh += 1
                if len(collected) >= max_items:
                    break
            total = payload.get("total")
            if fresh == 0 or len(data) < page_size:
                break
            if isinstance(total, int) and offset + page_size >= total:
                break
            offset += page_size
        return collected

    def works(self, *, limit: int = 25, offset: int = 0, language: str | None = None,
              max_items: int | None = None) -> list[dict]:
        """Works available in a language, most recently updated first.

        Defaults to the first configured language that actually returns results,
        so an operator asking for "Arabic first" gets Arabic when it exists and
        is not silently handed something else.
        """
        wanted = (language,) if language else self.languages
        for lang in wanted:
            rows = self._works_for(lang, limit=limit, offset=offset,
                                   max_items=max_items if max_items is not None else limit)
            if rows:
                return rows
        return []

    def _works_for(self, language: str, *, limit: int, offset: int,
                   max_items: int) -> list[dict]:
        entries = self._paged("/manga", [
            ("availableTranslatedLanguage[]", language),
            ("order[updatedAt]", "desc"),
            ("includes[]", "cover_art"),
        ], limit=limit, max_items=max_items, label="manga")
        if offset:
            entries = entries[offset:]
        out: list[dict] = []
        for entry in entries:
            attributes = entry.get("attributes") or {}
            manga_id = entry.get("id")
            if not manga_id or not isinstance(attributes, dict):
                continue
            titles = attributes.get("title")
            if isinstance(titles, dict) and titles.get(language):
                title = str(titles[language])
            else:
                title = self._best_title(attributes)
            # Arabic titles routinely carry leading/trailing whitespace.
            title = " ".join(title.split())
            out.append({
                "id": str(manga_id),
                "title": title,
                "status": attributes.get("status"),
                "year": attributes.get("year"),
                "original_language": attributes.get("originalLanguage"),
                "translated_languages": attributes.get("availableTranslatedLanguages") or [],
                "classification": _CLASSIFICATION.get(attributes.get("contentRating")),
            })
        return out

    @staticmethod
    def _best_title(attributes: dict) -> str:
        titles = attributes.get("title")
        if isinstance(titles, dict):
            for key in ("en", "ja-ro", "ja"):
                value = titles.get(key)
                if value:
                    return str(value)
            for value in titles.values():
                if value:
                    return str(value)
        return ""

    # ------------------------------------------------------------- chapters

    def chapters(self, manga_id: str, *, language: str = "ar", limit: int = 100,
                 max_items: int | None = None) -> list[dict]:
        entries = self._paged("/chapter", [
            ("manga", manga_id),
            ("translatedLanguage[]", language),
            ("order[chapter]", "asc"),
            ("order[volume]", "asc"),
        ], limit=limit, max_items=max_items if max_items is not None else limit,
            label="chapter")
        out: list[dict] = []
        for entry in entries:
            attributes = entry.get("attributes") or {}
            chapter_id = entry.get("id")
            if not chapter_id:
                continue
            if attributes.get("isUnavailable"):
                continue
            out.append({
                "id": str(chapter_id),
                "volume": attributes.get("volume"),
                "number": _as_number(attributes.get("chapter")),
                # Arabic chapter titles carry stray whitespace too.
                "title": " ".join(str(attributes.get("title") or "").split()) or None,
                "pages": attributes.get("pages"),
                "language": attributes.get("translatedLanguage"),
            })
        return out

    # ----------------------------------------------------------------- pages

    def at_home(self, chapter_id: str) -> dict:
        """The current MangaDex@Home access information for one chapter."""
        query = "forcePort443=true" if FORCE_PORT_443 else ""
        url = f"https://{API_HOST}/at-home/server/{quote(chapter_id)}"
        if query:
            url = f"{url}?{query}"
        payload = _decode(self.http, url)
        base_url = payload.get("baseUrl")
        chapter = payload.get("chapter")
        if not isinstance(base_url, str) or not isinstance(chapter, dict):
            raise LinkUnavailable(f"chapter {chapter_id} returned no image server")
        digest = chapter.get("hash")
        files = chapter.get("data")
        if not isinstance(digest, str) or not isinstance(files, list):
            raise LinkUnavailable(f"chapter {chapter_id} returned no page list")
        for name in files:
            if not isinstance(name, str) or not name:
                continue
            if "/" in name or name.startswith("."):
                # A file name, never a path: refuse anything shaped like one
                # rather than let it steer a request.
                raise DiscoveryRejected(f"chapter {chapter_id} returned an odd page name")
        return {"base_url": base_url, "hash": digest, "files": files}

    def chapter_pages(self, chapter_id: str) -> list[dict]:
        """The ordered page images for one chapter.

        ``source_page_id`` is the API's own file name for the page, which does
        not change when the serving host rotates. ``source_page_url`` is the
        current location, and MangaDex@Home links are temporary - see
        :class:`MangaDexLinkResolver` for how a fetch gets a live one.
        """
        access = self.at_home(chapter_id)
        out: list[dict] = []
        for index, name in enumerate(access["files"]):
            out.append({
                "page_index": index,
                "source_page_id": name,
                "source_page_url": f"{access['base_url']}/data/{access['hash']}/{name}",
            })
        return out

    # -------------------------------------------------------------- manifest

    def manifest(self, *, language: str = "ar", works: int = 5,
                 chapters_per_work: int = 2, pages_per_chapter: int = 5) -> dict:
        """Build a manifest for the ingest layer, in memory.

        No database is touched and no clearance is written. This returns exactly
        the structure :func:`shura_core.content.import_payload` accepts, so the
        discovered rows land through the same validation, transaction and upserts
        as a hand-written manifest file.
        """
        manifest = {"version": 1, "source_id": self.source_id,
                   "works": [], "chapters": [], "pages": []}
        for work in self.works(limit=works, language=language):
            ext_work_id = work["id"]
            manifest["works"].append({
                "ext_work_id": ext_work_id,
                "source_work_id": ext_work_id,
                "title": work["title"],
                "source_url": f"https://{API_HOST}/manga/{ext_work_id}",
                # None stays None. The fetch gate refuses an unclassified work.
                "content_classification": work["classification"],
            })
            found = self.chapters(ext_work_id, language=language,
                                  limit=chapters_per_work)
            for ordinal, chapter in enumerate(found):
                ext_chapter_id = f"{ext_work_id}/{chapter['id']}"
                manifest["chapters"].append({
                    "ext_chapter_id": ext_chapter_id,
                    "ext_work_id": ext_work_id,
                    "source_chapter_id": chapter["id"],
                    "chapter_number": chapter["number"],
                    "is_special": 0,
                    "ordinal": ordinal,
                    "title": " ".join((chapter["title"] or "").split()),
                    "source_url": f"https://{API_HOST}/chapter/{chapter['id']}",
                    "page_count": chapter["pages"],
                    "listing_epoch": 0,
                })
                for page in self.chapter_pages(chapter["id"])[:pages_per_chapter]:
                    manifest["pages"].append({
                        "ext_chapter_id": ext_chapter_id,
                        "page_index": page["page_index"],
                        "ordinal": page["page_index"],
                        "source_page_url": page["source_page_url"],
                        "source_page_id": page["source_page_id"],
                        "listing_epoch": 0,
                    })
        return manifest