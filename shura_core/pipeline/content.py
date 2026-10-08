"""Generic per-chapter content integrity engine (source-agnostic).

Every decision is made at the *chapter* level; a damaged, stale, temporary,
unavailable or paid chapter never drags a whole work down with it. A work is
only ever rejected when it leaves no healthy public chapters *and* has nothing
left to retry. Paid/gated content is always excluded from the public result
set and is never fetched, token-resolved or otherwise bypassed.
"""
from __future__ import annotations
from dataclasses import dataclass
from typing import Any, Callable

from ..models import ChapterRecord, ChapterStatus, WorkReview, RETRYABLE_CHAPTER_STATUSES
from ..security.network import NetworkPolicyError

MANIFEST_KEY = "_content"
GATED_HTTP = {401, 403}
TRANSIENT_HTTP = {408, 425, 429, 500, 502, 503, 504}
BOOL_GATE_KEYS=("paid","is_paid","locked","locked_by_coins","lockedByCoins","coin_locked","coinLocked","premium","exclusive","members_only","paywalled","lockedByShortlink","shortlink_only")

def is_paid_chapter(chapter: dict[str, Any], policy: dict[str, Any]) -> bool:
    """Generic paid detection from declared gating metadata. Only considers what the
    source *declares* (gate fields/values); never probes or unlocks anything."""
    meta = chapter.get("meta") or {}
    if any(chapter.get(k) is True for k in BOOL_GATE_KEYS): return True
    if any(meta.get(k) is True for k in BOOL_GATE_KEYS): return True
    gate = str(meta.get("gate") or chapter.get("gate") or "").strip().upper()
    if policy.get("literal_paid_gates"):
        if gate in {str(g).upper() for g in policy["literal_paid_gates"]}:
            return True
    hinted = any(h.upper() in gate for h in ("COIN","PAID","LOCKED","PREMIUM","EXCLUSIVE"))
    if hinted: return True
    for v in meta.values():
        if isinstance(v, (str, int, float)) and any(h.upper() in str(v).upper() for h in ("COIN","PAID","LOCKED","PREMIUM","EXCLUSIVE")):
            return True
    return False

PREVIEW_ONLY_MARKERS = ("previewOnly", "publicOnly", "partial_public", "public_preview_only", "publicPreviewOnly")

def is_preview_only(chapter: dict[str, Any]) -> bool:
    """True only when a manifest *explicitly* declares that its ``pages`` are the source's public
    preview slice and the chapter's remaining pages are served via deferred/protected media that
    the manifest does not publish. Explicit opt-in means existing full manifests stay HEALTHY."""
    meta = chapter.get("meta") or {}
    for holder in (chapter, meta):
        if any(holder.get(k) is True for k in PREVIEW_ONLY_MARKERS):
            return True
    declared = str(chapter.get("preview_only") or meta.get("preview_only") or "").strip().lower()
    return declared in {"1", "true", "yes"}

def classify_page_failure(exc: BaseException, status: int | None, policy: dict[str, Any]) -> tuple[ChapterStatus, str]:
    """Classify a single page failure into a chapter-level status. Status-coded HTTP errors are
    classified first (HTTPError is also an OSError); a gated response is never labelled 'paid'
    (that would imply we know why it is blocked) - protected/gated pages are simply 'not public'."""
    if status in GATED_HTTP:
        return ChapterStatus.UNAVAILABLE, f"page http {status}: not publicly readable (protected/gated; not bypassed)"
    if status == 404:
        return ChapterStatus.STALE_LINK, "page http 404: stale/moved link, re-resolve required"
    if status in TRANSIENT_HTTP:
        return ChapterStatus.TEMPORARY_FAILURE, f"page http {status}: transient server failure"
    if status and 400 <= status < 500:
        return ChapterStatus.UNAVAILABLE, f"page http {status}: content unavailable"
    if isinstance(exc, (TimeoutError, OSError, NetworkPolicyError)):
        return ChapterStatus.TEMPORARY_FAILURE, f"page fetch failed transiently: {exc!r}"
    return ChapterStatus.TEMPORARY_FAILURE, f"page fetch failed: {exc!r}"

@dataclass
class PageCheck:
    url: str
    ok: bool
    status: int | None = None
    detail: str = ""

def verify_pages(pages: list[str], fetch: Callable[..., Any], policy: dict[str, Any]) -> list[PageCheck]:
    """Verify each declared public page is actually readable. fetch(url) -> (bytes, headers, final_url).
    A 200 carrying the wrong content type (e.g. an HTML error page served as 200) counts as a parse error."""
    image_types = tuple(policy.get("image_content_types") or ("image/",))
    checks: list[PageCheck] = []
    for url in pages:
        try:
            body, headers, final = fetch(url)
            ctype = (headers.get("Content-Type") or "").lower()
            if not body:
                checks.append(PageCheck(url, False, 200, "empty page body"))
            elif image_types and ctype and not ctype.startswith(image_types):
                checks.append(PageCheck(url, False, 200, f"content-type mismatch (got {ctype!r}, expected image)"))
            else:
                checks.append(PageCheck(url, True, 200, ""))
        except Exception as exc:  # noqa: BLE001 - classified below
            status = getattr(exc, "status", None) or getattr(exc, "code", None) or (getattr(exc, "response", None) and getattr(exc.response, "status", None))
            st, reason = classify_page_failure(exc, status, policy)
            if hasattr(exc, "close"):
                try: exc.close()
                except Exception: pass
            checks.append(PageCheck(url, False, status, reason))
    return checks

def evaluate_chapter(chapter: dict[str, Any], fetch: Callable[..., Any] | None, policy: dict[str, Any], attempts: int = 0) -> ChapterRecord:
    work_id = str(chapter.get("work_id") or "")
    chapter_id = str(chapter.get("chapter_id") or chapter.get("id") or "")
    pages = list(chapter.get("pages") or [])
    if policy.get("public_only", True) and not is_paid_chapter(chapter, policy) and not pages:
        return ChapterRecord(work_id, chapter_id, ChapterStatus.UNAVAILABLE, "no publicly readable pages exposed (deferred/protected)", [], attempts)
    if is_paid_chapter(chapter, policy):
        return ChapterRecord(work_id, chapter_id, ChapterStatus.PAID, "chapter is paid/gated; excluded from public results (no bypass attempted)", [], attempts)
    if not fetch:
        return ChapterRecord(work_id, chapter_id, ChapterStatus.UNAVAILABLE, "no page verifier available", pages, attempts)
    if not pages:
        return ChapterRecord(work_id, chapter_id, ChapterStatus.UNAVAILABLE, "no publicly readable pages declared", [], attempts)
    checks = verify_pages(pages, fetch, policy)
    ok = [c for c in checks if c.ok]
    bad = [c for c in checks if not c.ok]
    if not bad:
        if is_preview_only(chapter):
            return ChapterRecord(
                work_id, chapter_id, ChapterStatus.PARTIAL,
                f"public preview pages verified ({len(checks)}/{len(pages)}); the source serves the remaining pages "
                "via deferred/protected media that the manifest does not publish - full chapter health is not asserted",
                pages, attempts)
        return ChapterRecord(work_id, chapter_id, ChapterStatus.HEALTHY, "", pages, attempts)
    reasons = [f"{c.url}: {c.detail}" for c in bad][:3]
    stale = any(c.status == 404 or "stale" in c.detail for c in bad)
    parse = any("content-type mismatch" in c.detail or "parse" in c.detail.lower() for c in bad)
    transient = any(c.status in TRANSIENT_HTTP or "transient" in c.detail or "empty page body" in c.detail for c in bad)
    if all(c.ok is False and c.status is None for c in bad):
        return ChapterRecord(work_id, chapter_id, ChapterStatus.TEMPORARY_FAILURE, "; ".join(reasons), pages, attempts)
    if parse and not stale:
        return ChapterRecord(work_id, chapter_id, ChapterStatus.PARSE_ERROR, "; ".join(reasons), pages, attempts)
    if stale:
        return ChapterRecord(work_id, chapter_id, ChapterStatus.STALE_LINK, "; ".join(reasons), pages, attempts)
    if transient:
        return ChapterRecord(work_id, chapter_id, ChapterStatus.TEMPORARY_FAILURE, "; ".join(reasons), pages, attempts)
    return ChapterRecord(work_id, chapter_id, ChapterStatus.UNAVAILABLE, "; ".join(reasons), pages, attempts)

def evaluate_work(work_id: str, work_name: str, chapters: list[dict[str, Any]], fetch: Callable[..., Any] | None, policy: dict[str, Any], attempts: int = 0) -> WorkReview:
    """Work-level decision derived ONLY from independent per-chapter verdicts.
    No blanket rule: a broken chapter can neither reject nor block a work."""
    records = [evaluate_chapter(ch, fetch, policy, attempts) for ch in chapters]
    for r in records: r.work_id = work_id
    totals: dict[str, int] = {}
    for r in records:
        totals[r.status.value] = totals.get(r.status.value, 0) + 1
    healthy = totals.get(ChapterStatus.HEALTHY.value, 0)
    partial = totals.get(ChapterStatus.PARTIAL.value, 0)
    retryable = sum(totals.get(s.value, 0) for s in RETRYABLE_CHAPTER_STATUSES)
    excluded = totals.get(ChapterStatus.PAID.value, 0) + totals.get(ChapterStatus.UNAVAILABLE.value, 0)
    if healthy > 0 or partial > 0:
        return WorkReview(work_id, work_name, "ACCEPTED",
            f"{healthy} healthy + {partial} partial(preview-verified) public chapter(s); {excluded} excluded (paid/unavailable); {retryable} retryable",
            totals, records)
    if retryable > 0:
        return WorkReview(work_id, work_name, "PENDING",
            f"no healthy chapter yet; {retryable} retryable + {excluded} excluded; will retry per policy (not final)",
            totals, records)
    return WorkReview(work_id, work_name, "REJECTED",
        "no publicly available chapters left (all paid/unavailable); remaining chapters stay recorded for later recheck",
        totals, records)