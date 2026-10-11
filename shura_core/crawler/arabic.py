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

Candidates are recorded as *pending proposals* - they are registered disabled
and PAUSED, so they cannot be crawled until an operator reviews and completes
their trust configuration.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from urllib.parse import urljoin, urlparse

from ..models import Source, SourceState

__all__ = ["SiteCandidate", "SiteVerdict", "evaluate_site", "ArabicSiteDiscovery",
           "SeedFileFinder", "DEFAULT_UA_HINT"]

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

    Proposals land in the existing ``sources`` table disabled and PAUSED, so a
    newly found site is queued for an operator and can never be crawled
    automatically. Every decision is written to the existing event ledger.
    """

    def __init__(self, store, http, *, daily_limit: int = 250):
        self.store = store
        self.http = http
        self.daily_limit = max(1, min(int(daily_limit), 250))
        self.errors: list[dict] = []

    def discover(self, finders) -> list[dict]:
        """Return the verdicts for this pass and record the viable proposals."""
        self.errors = []
        proposals: list[dict] = []
        seen: set[str] = set()
        for finder in finders:
            try:
                candidates = finder.find(limit=self.daily_limit)
            except Exception as exc:                            # noqa: BLE001
                # One broken finder must not cost the whole pass.
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
                verdict = evaluate_site(self.http, candidate)
                self.store.event("discovery", "site_candidate",
                                 verdict.to_dict())
                if verdict.verdict == "CANDIDATE" and self._record_proposal(verdict):
                    proposals.append(verdict.to_dict())
        return proposals

    def _record_proposal(self, verdict: SiteVerdict) -> bool:
        """Register a verified candidate as a disabled, paused proposal.

        Trust material is deliberately left empty. That is what makes the
        proposal useless for crawling and exactly right for review: an operator
        must supply the package and signing fingerprint before acceptance.
        """
        candidate = verdict.candidate
        source_id = candidate.source_id()
        if self.store.get_source(source_id):
            return False                  # known already; a repeat pass adds nothing
        source = Source(
            source_id=source_id,
            name=candidate.domain,
            url=candidate.url,
            kind="html",
            language=candidate.language if candidate.language != "und" else "ar",
            enabled=False,
            state=SourceState.PAUSED,
            configuration={
                "allowed_hosts": [candidate.domain],
                "package": "",             # an operator must fill these in
                "signing_key": "",
                "review_required": True,
                "discovered_by": candidate.origin,
                "discovery_score": verdict.score,
                "discovery_evidence": verdict.to_dict(),
                "content_policy": {"review_enabled": False},
            })
        self.store.add_source(source)
        self.store.event(source_id, "site_proposed", verdict.to_dict())
        return True