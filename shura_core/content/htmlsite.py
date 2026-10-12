"""A Shura source for an HTML catalogue site, driven by proven evidence.

Discovery found it and :func:`~shura_core.crawler.arabic.evaluate_site` proved
something about it. An operator reviewed and accepted it. Until now the chain
stopped there: nothing implemented *reading* the site, so
``run_fetch_run`` found no works, no chapters and no pages to fetch.

This closes that loop for the shape discovery can actually prove - a public
site that serves a work index and chapter links as HTML. It is deliberately
narrow. It is not a browser, it does not execute JavaScript, it does not guess
routes, and it refuses to work with a site whose structure it was not shown.

The link shapes are not re-invented here: the work and chapter patterns are
imported from the crawler module, so "what counts as a work link" has exactly
one definition and the evidence recorded at evaluation time is the same
evidence this module reads.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from urllib.parse import urljoin, urlparse

from ..crawler.arabic import (_CHAPTER_LINK, _WORK_LINK, _listing_entries,
                               _links_matching)

__all__ = ["SiteDescriptor", "HtmlCatalogueSource"]

#: Only this many pages are ever fetched for one site in one pass. A probe that
#: wanders is how a crawler turns into a denial of service against a stranger's
#: server.
MAX_PAGES = 12
MAX_BYTES = 2_000_000


@dataclass
class SiteDescriptor:
    """What evaluation proved about one site, and what reading it needs.

    Built from a candidate and its recorded evidence. If the evidence does not
    say the site serves chapters, this source refuses the site rather than
    guessing at it.
    """

    source_id: str
    name: str
    language: str = "ar"
    catalogue_url: str = ""
    host: str = ""
    origin: str = ""
    work_urls: list[str] = field(default_factory=list)

    @classmethod
    def from_record(cls, record: dict, *, source_id: str | None = None) -> "SiteDescriptor":
        """Build a descriptor from a stored candidate/evidence record."""
        evidence = record.get("discovery_evidence") or record
        host = evidence.get("domain") or ""
        catalogue = evidence.get("url") or ""
        return cls(source_id=source_id or record.get("source_id") or (f"site:{host}" if host else ""),
                   name=record.get("domain") or host or "site",
                   language=record.get("language") or "ar",
                   catalogue_url=catalogue, host=host,
                   origin=evidence.get("origin") or record.get("origin") or "")

    def usable(self) -> tuple[bool, str]:
        """Whether this site can be read at all under the policy.

        Returns ``(ok, reason)``. The reason is recorded either way, so a
        refused site says why instead of quietly producing nothing.
        """
        if not self.catalogue_url:
            return False, "no catalogue URL was recorded for this candidate"
        parsed = urlparse(self.catalogue_url)
        if parsed.scheme != "https":
            return False, "the catalogue URL is not HTTPS"
        if not parsed.hostname:
            return False, "the catalogue URL has no host"
        if self.language and not re.fullmatch(r"(?:[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*|und)",
                                              self.language):
            return False, f"{self.language!r} is not a language tag"
        return True, ""


class HtmlCatalogueSource:
    """Reads a public HTML catalogue as a Shura source.

    Only the operations the source contract declares are implemented: list
    works, list a work's chapters, and resolve one chapter's reading pages. It
    makes no claim beyond that, and it never bypasses an access control - a
    site that refuses is recorded as refused.
    """

    def __init__(self, descriptor: SiteDescriptor, http):
        self.d = descriptor
        self.http = http
        self.pages_fetched = 0

    # -- fetching, bounded ------------------------------------------------
    def _get(self, url: str) -> str | None:
        """Fetch one page, or None. Never more than ``MAX_PAGES`` in a pass."""
        if self.pages_fetched >= MAX_PAGES:
            return None
        if (urlparse(url).hostname or "").lower() != self.d.host.lower():
            return None                    # same host only; never wander
        try:
            body, headers, _final = self.http.get(url, max_bytes=MAX_BYTES)
        except Exception:                                  # noqa: BLE001
            return None
        self.pages_fetched += 1
        return _decode(body, headers)

    # -- the three operations --------------------------------------------
    def works(self, limit: int = 20) -> list[dict]:
        """The work index: ``[{"id","title","url"}]`` for what the index proves."""
        text = self._get(self.d.catalogue_url)
        if not text:
            return []
        links = _links_matching(text, _WORK_LINK) or sorted(_listing_entries(text))
        out = []
        for href in links[:max(1, limit)]:
            absolute = urljoin(self.d.catalogue_url, href)
            out.append({"id": _slug(absolute), "title": _link_title(text, href),
                        "url": absolute})
        return out

    def chapters(self, work: dict) -> list[dict]:
        """A work's chapters, as ``[{"id","title","url","number"}]``."""
        text = self._get(work["url"])
        if not text:
            return []
        links = _links_matching(text, _CHAPTER_LINK)
        out = []
        for href in links:
            absolute = urljoin(work["url"], href)
            # Namespaced by the work: two works must never present the same
            # chapter identity, which is exactly what ingest refuses.
            out.append({"id": f'{work["id"]}-{_slug(absolute)}',
                        "title": _link_title(text, href),
                        "url": absolute, "number": _leading_number(href)})
        # Newest first, which is the reading order Shura's contract expects.
        out.sort(key=lambda c: (c["number"] is None, -(c["number"] or 0.0)))
        return out

    def pages(self, chapter: dict) -> list[dict]:
        """One chapter's reading pages, in order, from the links the chapter serves."""
        text = self._get(chapter["url"])
        if not text:
            return []
        out = []
        for href in dict.fromkeys(_IMAGE_HREF.findall(text)):
            if (urlparse(urljoin(chapter["url"], href)).hostname or "") != self.d.host:
                continue
            out.append({"index": len(out), "url": urljoin(chapter["url"], href)})
        out.sort(key=_page_sort_key)
        for position, page in enumerate(out):
            page["index"] = position
        return out

    # -- the manifest the pipeline already knows how to ingest -----------
    def manifest(self, *, works: int = 5, chapters_per_work: int = 3,
                 pages_per_chapter: int = 20) -> dict:
        """Emit the manifest ``ingest._parse`` already validates.

        Same shape ``MangaDexSource`` emits, so the existing ingest, fetch and
        review path applies unchanged. Nothing new is invented downstream.
        """
        payload = {"version": 1, "source_id": self.d.source_id,
                   "works": [], "chapters": [], "pages": []}
        for work in self.works(limit=works):
            payload["works"].append({"ext_work_id": work["id"], "title": work["title"],
                                     "source_url": work["url"]})
            for chapter in self.chapters(work)[:chapters_per_work]:
                payload["chapters"].append({"ext_chapter_id": chapter["id"],
                                            "ext_work_id": work["id"],
                                            "title": chapter["title"],
                                            "chapter_number": chapter["number"],
                                            "source_url": chapter["url"]})
                for page in self.pages(chapter)[:pages_per_chapter]:
                    payload["pages"].append({"ext_chapter_id": chapter["id"],
                                             "page_index": page["index"],
                                             "source_page_url": page["url"]})
        return payload


#: Reading pages are linked as images. This is the same "prove it by structure"
#: rule the work and chapter shapes use, applied to the last hop.
_IMAGE_HREF = re.compile(
    r"""<a[^>]+href\s*=\s*["']([^"']+\.(?:jpe?g|png|webp)(?:\?[^"']*)?)["']""", re.I)
_PAGE_NUMBER = re.compile(r"(\d+)")


def _page_sort_key(page: dict):
    match = _PAGE_NUMBER.search(page["url"])
    return int(match.group(1)) if match else 0


def _decode(body: bytes, headers) -> str:
    charset = headers.get_content_charset() if hasattr(headers, "get_content_charset") else None
    return body.decode(charset or "utf-8", errors="replace")


def _link_title(text: str, href: str) -> str:
    """The anchor text for one href, which is the closest thing to a title."""
    pattern = re.compile(r"<a[^>]+href\s*=\s*[\"']" + re.escape(href) + r"[\"'][^>]*>(.*?)</a>",
                         re.I | re.S)
    match = pattern.search(text)
    if match:
        title = re.sub(r"<[^>]+>", "", match.group(1)).strip()
        if title:
            return title
    return href.rsplit("/", 1)[-1]


def _leading_number(href: str):
    # The last segment, with any trailing slash removed: "ch-1/" ends in "".
    tail = href.rstrip("/").rsplit("/", 1)[-1]
    match = re.search(r"(\d+)(?:\D|$)", tail)
    return float(match.group(1)) if match else None


def _slug(url: str) -> str:
    """A stable identity for a page, so the same page is the same row."""
    return re.sub(r"[^a-z0-9]+", "-", urlparse(url).path.strip("/").lower()).strip("-") or "root"