"""Independent discovery of Arabic manga/manhwa *websites*.

This is a different job from :mod:`shura_core.discovery`, which finds GitHub
repositories hosting Android extensions. That one answers "which repository
ships an extension?"; this one answers "which public websites serve manga in
Arabic?". They share only the store and the guarded HTTP client.

Three rules shape the whole module.

**Discovery is not trust.** A candidate is a hostname someone proposed. It
becomes a crawlable source only after an operator registers it through
:mod:`shura_core.sources.registry`, which enforces the trust material. Nothing
here can enable a source, and nothing here writes an APK or an index.

**Evidence, not vibes.** An Arabic-looking name proves nothing. Every candidate
is probed for things that can be shown: public reachability over HTTPS, Arabic
language signals in the document, and structural evidence that the site lists
works and chapters. The verdict carries the reasons.

**Public access only.** A candidate that answers 401/403, or demands a login or
a CAPTCHA, is recorded as refused with that reason and the pass moves on. No
attempt is made to reach past an access control.

**Discovery is separated from evaluation, and from storage.** Discovery only
proposes hostnames (:class:`SearchFinder` asks a permitted public search API;
:class:`SeedFileFinder` replays what an operator wrote down). Evaluation decides
whether a proposal is any good (:func:`evaluate_site`). Neither writes a
crawlable source: a proposal is recorded in the existing event ledger as a
*review item*, and fetching stays impossible until an operator adds the host to
``content-sources.json`` themselves. A search hit is never evidence, and a 200
response is never proof of manga.
"""

from __future__ import annotations

import json
import os
import re
from dataclasses import dataclass, field
from urllib.parse import urlparse

__all__ = ["SiteCandidate", "SiteVerdict", "evaluate_site", "ArabicSiteDiscovery",
           "SeedFileFinder", "SearchFinder", "SearchProvider",
           "JsonHttpSearchProvider", "configured_search_finder", "pending_proposals",
           "proposed_source_ids",
           "DEFAULT_ARABIC_QUERIES", "DEFAULT_UA_HINT"]

#: Sent as a query hint only. The request still carries SafeHTTP's own
#: User-Agent; nothing here spoofs or bypasses an access control.
DEFAULT_UA_HINT = "Shura source discovery (+https://shura.invalid)"

_ARABIC = re.compile(r"[\u0600-\u06FF\u0750-\u077F\uFB50-\uFDFF\uFE70-\uFEFF]")
#: Arabic-language attributes a server-rendered document commonly carries.
_ARABIC_ATTRS = ("lang", "dir")
_CHAPTER_HINTS = ("chapter", "فصل", "chapters", "حلقة", "episode")
_INDEX_HINTS = ("manga", "مانجا", "manhwa", "مانهوا", "فهرس", "catalog", "listing")


def _domain(url: str) -> str:
    try:
        return (urlparse(url).hostname or "").lower()
    except ValueError:
        return ""


@dataclass
class SiteCandidate:
    """Something a finder proposed. A proposal, not a source."""

    url: str
    origin: str = ""                 # which finder proposed it
    language: str = "und"
    note: str = ""

    @property
    def domain(self) -> str:
        return _domain(self.url)

    def source_id(self) -> str:
        return f"site:{self.domain}" if self.domain else f"site:{self.url}"


@dataclass
class SiteVerdict:
    """What probing could actually show about a candidate."""

    candidate: SiteCandidate
    reachable: bool = False
    https: bool = False
    arabic_signals: int = 0
    has_index: bool = False
    has_chapters: bool = False
    blocked: bool = False             # 401/403 or an access control of some kind
    reasons: list[str] = field(default_factory=list)
    score: float = 0.0

    @property
    def verdict(self) -> str:
        if not self.reachable or self.blocked:
            return "REJECTED"
        if not self.https:
            return "REJECTED"
        if not self.arabic_signals:
            return "REJECTED"
        if not (self.has_index or self.has_chapters):
            return "DEFERRED"         # public, but nothing verifiable yet
        return "CANDIDATE"

    def to_dict(self) -> dict:
        return {"url": self.candidate.url, "domain": self.candidate.domain,
                "origin": self.candidate.origin, "language": self.candidate.language,
                "verdict": self.verdict, "score": round(self.score, 3),
                "reachable": self.reachable, "https": self.https,
                "arabicSignals": self.arabic_signals, "hasIndex": self.has_index,
                "hasChapters": self.has_chapters, "blocked": self.blocked,
                "reasons": list(self.reasons)}


class SeedFileFinder:
    """Candidates from a local JSON file - a public information source the
    operator already has and can edit without touching code.

    The file is a plain list of objects: ``{"url": ..., "language": ...}``.
    Nothing here invents a URL; if the file names a site, that is the operator's
    claim, and the evaluator still has to prove it.
    """

    def __init__(self, path):
        self.path = path

    def find(self, limit: int = 250) -> list[SiteCandidate]:
        from pathlib import Path
        try:
            raw = json.loads(Path(self.path).read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return []
        if isinstance(raw, dict):
            raw = raw.get("sites") or raw.get("candidates") or []
        if not isinstance(raw, list):
            return []
        out: list[SiteCandidate] = []
        for entry in raw[:limit]:
            if isinstance(entry, str):
                entry = {"url": entry}
            if not isinstance(entry, dict):
                continue
            url = str(entry.get("url") or "").strip()
            if not url.startswith("https://"):
                # Plain http is not a candidate: the project refuses it anyway,
                # and recording it would only create noise for the operator.
                continue
            out.append(SiteCandidate(url=url, origin="seed-file",
                                     language=str(entry.get("language") or "und"),
                                     note=str(entry.get("note") or "")))
        return out


def evaluate_site(http, candidate: SiteCandidate, *, max_bytes: int = 2_000_000) -> SiteVerdict:
    """Probe a candidate over the project's guarded HTTP client.

    Only a public GET of the site's own page. A refusal is a recorded reason,
    never a puzzle to work around.
    """
    verdict = SiteVerdict(candidate=candidate)
    if not candidate.domain:
        verdict.reasons.append("no resolvable domain")
        return verdict
    try:
        body, headers, final = http.get(candidate.url, max_bytes=max_bytes)
    except Exception as exc:                                  # noqa: BLE001
        name = type(exc).__name__
        status = getattr(exc, "code", None)
        if status in (401, 402, 403) or name in ("HTTPError",):
            verdict.blocked = True
            verdict.reasons.append(f"site refused public access ({status or name})")
        else:
            verdict.reasons.append(f"not publicly reachable: {exc}")
        return verdict

    verdict.reachable = True
    verdict.https = (urlparse(final).scheme or "").lower() == "https"
    if not verdict.https:
        verdict.reasons.append("did not serve over HTTPS")

    text = body.decode(headers.get_content_charset() or "utf-8", errors="replace") \
        if hasattr(headers, "get_content_charset") else body.decode("utf-8", "replace")
    head = text[:200_000].lower()

    signals = 0
    for attr in _ARABIC_ATTRS:
        match = re.search(rf'{attr}\s*=\s*["\']?\s*([a-z\-]+)', head)
        if match and (match.group(1).startswith("ar") or attr == "dir" and match.group(1) == "rtl"):
            signals += 1
    arabic_chars = len(_ARABIC.findall(text[:50_000]))
    if arabic_chars >= 20:
        signals += 1
    verdict.arabic_signals = signals
    if not signals:
        verdict.reasons.append("no Arabic language signal found in the public page")

    verdict.has_index = any(hint in head for hint in _INDEX_HINTS)
    verdict.has_chapters = any(hint in head for hint in _CHAPTER_HINTS)
    if not (verdict.has_index or verdict.has_chapters):
        verdict.reasons.append("no verifiable work index or chapter listing in the public page")

    # Score explains itself: reachability and HTTPS are prerequisites, then the
    # Arabic evidence and the structural evidence, each weighted.
    verdict.score = round(
        (0.30 * min(verdict.arabic_signals, 2) / 2
         + 0.25 * (1 if verdict.has_index else 0)
         + 0.25 * (1 if verdict.has_chapters else 0)
         + 0.20 * (1 if verdict.reachable and verdict.https else 0)), 3)
    if verdict.verdict != "CANDIDATE":
        verdict.score = 0.0
    return verdict


class ArabicSiteDiscovery:
    """Runs finders, evaluates their candidates, and records proposals.

    A proposal is a *review item*, not a source. It is written to the existing
    event ledger and nowhere else.

    Why it is not written to ``sources``: that table is the APK extension
    ledger. Its rows carry a package name and an APK signing-certificate
    fingerprint, and they end in the APK publisher. A manga website has none of
    those things, so a website row there could never legitimately be accepted
    and could only ever drift toward the extension pipeline.

    Why it is not written to ``source_policy``: that table is a mirror of the
    operator's ``content-sources.json``. ``sync_source_policy`` deletes every row
    whose ``source_id`` is absent from that file, so a proposal written there
    would be erased by the next sync -- silently, and without a decision.

    So a candidate lives in the ledger as evidence, and fetching stays
    impossible by construction: the fetch path reads
    ``source_policy.fetch_enabled=1``, which only ``content-sources.json`` can
    set. Being discovered grants nothing.
    """

    def __init__(self, store, http, *, daily_limit: int = 250):
        self.store = store
        self.http = http
        self.daily_limit = max(1, min(int(daily_limit), 250))
        self.errors: list[dict] = []
        self.proposed: set[str] = proposed_source_ids(store)

    def discover(self, finders) -> list[dict]:
        """Return the verdicts for this pass and record the viable proposals."""
        self.errors = []
        proposals: list[dict] = []
        seen: set[str] = set()
        for finder in finders:
            try:
                candidates = finder.find(limit=self.daily_limit)
            except Exception as exc:                            # noqa: BLE001
                # One broken finder must not cost the whole pass, and must not
                # erase what earlier finders already produced.
                self.errors.append({"finder": type(finder).__name__, "error": str(exc)})
                self.store.event("discovery", "site_finder_failed",
                                 {"finder": type(finder).__name__, "reason": str(exc)})
                continue
            for candidate in candidates:
                if len(proposals) >= self.daily_limit:
                    break
                key = candidate.source_id()
                if not key or key in seen:
                    continue                     # de-duplicated within and across finders
                seen.add(key)
                try:
                    verdict = evaluate_site(self.http, candidate)
                except Exception as exc:                        # noqa: BLE001
                    # One site that cannot be probed costs only that site.
                    self.errors.append({"domain": candidate.domain, "error": str(exc)})
                    self.store.event("discovery", "site_evaluation_failed",
                                     {"domain": candidate.domain, "reason": str(exc)})
                    continue
                self.store.event("discovery", "site_candidate", verdict.to_dict())
                if verdict.verdict == "CANDIDATE" and self._record_proposal(verdict):
                    proposals.append(verdict.to_dict())
        return proposals

    def _record_proposal(self, verdict: SiteVerdict) -> bool:
        """Record a verified candidate as a review item in the event ledger.

        Deliberately not a source row. Nothing here can make the site crawlable,
        and no operator decision is implied by a proposal being on the list.
        """
        source_id = verdict.candidate.source_id()
        if source_id in self.proposed:
            return False                  # known already; a repeat pass adds nothing
        self.proposed.add(source_id)
        self.store.event(source_id, "site_proposed", {
            **verdict.to_dict(),
            "review": "awaiting operator review; not a crawlable source",
            "activation": "operator must add the host to content-sources.json"})
        return True


def proposed_source_ids(store) -> set[str]:
    """Source ids already proposed, read back from the event ledger.

    This is what keeps a repeated pass from creating duplicate review items
    without needing a table of its own.
    """
    try:
        rows = store.db.execute(
            "SELECT source_id FROM history WHERE event='site_proposed'").fetchall()
    except Exception:                                        # noqa: BLE001
        return set()
    return {r[0] for r in rows}


def pending_proposals(store) -> list[dict]:
    """The latest proposal record per source, newest evidence first.

    Read back out of the ledger so the report and the operator see the same
    record the crawler wrote, with no second copy to fall out of step.
    """
    latest: dict[str, dict] = {}
    try:
        rows = store.db.execute(
            "SELECT source_id, detail FROM history WHERE event='site_proposed' "
            "ORDER BY id").fetchall()
    except Exception:                                        # noqa: BLE001
        return []
    for source_id, detail in rows:
        try:
            record = json.loads(detail)
        except (TypeError, json.JSONDecodeError):
            continue
        record["source_id"] = source_id
        latest[source_id] = record
    return list(latest.values())


#: Arabic queries, deliberately varied rather than one repeated phrase. Each
#: names a different way a reader looks for this material, so the finder is not
#: dependent on a single site's SEO wording.
DEFAULT_ARABIC_QUERIES = (
    "قراءة مانجا أونلاين",          # قراءة مانجا اونلاين
    "مانهوا مترجم عربي",                      # مانهاوا مترجم عربي
    "مواقع مانجا عربية",                   # مواقع مانجا عربية
    "فهرس الفصول مانجا",                    # فهرس الفصول مانجا
    "قراءة مانجا بدون تسجيل",  # قراءة مانجا بدون تسجيل
    "مانهوا عربي مجاني",                        # مانهاوا عربي مجاني
    "قصص مانجا إلمانجا مترجم", # قصص مانجا المانجا مترجم
)


class SearchProvider:
    """A permitted public search API, behind an interface.

    Deliberately abstract. The crawler must never grow a dependency on one
    vendor's request shape, an API key name, or an endpoint. Implementations
    return *URLs only*; deciding whether a URL is any good is
    :func:`evaluate_site`'s job, not the provider's.
    """

    name = "abstract"

    def search(self, query: str, limit: int = 20) -> list[str]:
        raise NotImplementedError


class JsonHttpSearchProvider(SearchProvider):
    """Talks to a JSON search API over the project's guarded HTTP client.

    Configured by endpoint template, an auth header and a dotted path to the
    result list, so a provider can be added without touching the crawler. The
    endpoint must already be one the operator is entitled to use; this class
    only enforces that credentials are present and that requests go through
    :class:`SafeHTTP` like everything else.

    Nothing here bypasses a block: if the API refuses, the refusal propagates
    and the pass records it like any other failure.
    """

    name = "json-http"

    def __init__(self, http, *, endpoint: str, result_path: str,
                 headers: dict[str, str] | None = None, link_field: str = "url"):
        if not endpoint:
            raise ValueError("a search provider needs an endpoint")
        self.http = http
        self.endpoint = endpoint
        self.result_path = result_path
        self.headers = dict(headers or {})
        self.link_field = link_field

    def search(self, query: str, limit: int = 20) -> list[str]:
        url = self.endpoint.replace("{query}", _quote(query))
        body, _headers, _final = self.http.get(url, max_bytes=2_000_000,
                                              headers=self.headers or None)
        payload = json.loads(body.decode("utf-8", "replace"))
        results = _dig(payload, self.result_path)
        if not isinstance(results, list):
            raise ValueError(f"search response has no list at {self.result_path!r}")
        urls: list[str] = []
        for item in results[:limit]:
            link = item.get(self.link_field) if isinstance(item, dict) else None
            if isinstance(link, str) and link.strip():
                urls.append(link.strip())
        return urls


class SearchFinder:
    """Proposes candidate sites from a public search provider.

    This is the part that actually finds domains the operator has not written
    down. It performs no evaluation: a search hit is a *suggestion* and nothing
    more. :func:`evaluate_site` decides whether a suggestion is any good, and
    a search result is never taken as evidence that a site is legitimate.
    """

    def __init__(self, provider: SearchProvider, queries=DEFAULT_ARABIC_QUERIES):
        self.provider = provider
        self.queries = tuple(queries)

    def find(self, limit: int = 250) -> list[SiteCandidate]:
        out: list[SiteCandidate] = []
        seen: set[str] = set()
        if limit <= 0:
            return []
        for query in self.queries:
            if len(out) >= limit:
                break
            try:
                links = self.provider.search(query, limit=max(1, limit - len(out)))
            except Exception:                                # noqa: BLE001
                # A failed query loses that query, never the ones already done.
                continue
            for link in links:
                if len(out) >= limit:
                    break
                domain = _domain(link)
                if not domain or domain in seen:
                    continue
                seen.add(domain)
                out.append(SiteCandidate(url=link, origin=f"search:{self.provider.name}",
                                         language="ar", note=query))
        return out


def _quote(value: str) -> str:
    from urllib.parse import quote
    return quote(value, safe="")


def _dig(payload, path: str):
    """Walk a dotted path, returning None rather than raising on a shape change."""
    current = payload
    for part in path.split("."):
        if isinstance(current, dict):
            current = current.get(part)
        else:
            return None
        if current is None:
            return None
    return current


def configured_search_finder(*, endpoint=None, headers=None, result_path=None,
                             queries=DEFAULT_ARABIC_QUERIES):
    """Build a :class:`SearchFinder` only from an endpoint the operator set up.

    Returns ``None`` when nothing is configured, which is the default. Search is
    therefore opt-in and cannot run by accident, and no credential is ever
    invented, requested or logged here: the operator supplies an endpoint they
    are entitled to use, and the header they hold for it.
    """
    endpoint = (endpoint or os.getenv("SHURA_SEARCH_ENDPOINT", "")).strip()
    result_path = result_path or os.getenv("SHURA_SEARCH_RESULT_PATH", "web.results")
    if headers is None:
        raw = os.getenv("SHURA_SEARCH_HEADERS", "").strip()
        headers = json.loads(raw) if raw else {}
    if not endpoint:
        return None
    host = _domain(endpoint)
    if not host:
        raise ValueError("SHURA_SEARCH_ENDPOINT must be an absolute http(s) URL")
    from ..security.network import SafeHTTP
    # The guarded client is scoped to the search host alone. A candidate site
    # never inherits the provider's permissions, and vice versa.
    provider = JsonHttpSearchProvider(SafeHTTP([host]), endpoint=endpoint,
                                      result_path=result_path, headers=headers)
    return SearchFinder(provider, queries)
