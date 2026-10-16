"""Turning a refusal into a diagnosis.

``ExtensionPlan.blockers`` and ``BuildFailure`` speak in sentences written for
an operator. The loop needs something narrower: of everything that went wrong,
is this the site's fault, the extractor's, or a gate that is simply too strict?

The distinction decides what happens next, and getting it wrong is expensive in
both directions. Treat a JavaScript-rendered site as "extractor limited" and the
agent re-probes it forever, learning nothing; treat a parser gap as "the site is
unconvertible" and a perfectly good source is discarded forever.

So each blocker is matched against the signature it was raised with, and a
message that matches nothing is reported as unknown rather than guessed at.
"""

from __future__ import annotations

import re

from .knowledge import Cause

__all__ = ["classify", "classify_build", "gate_for_cause", "SIGNATURES"]

#: (compiled pattern, cause, gate). Order matters: the first match wins, so the
#: specific signatures are listed before the broad ones.
SIGNATURES: list[tuple[re.Pattern, str, str]] = [
    # A site that answers, but serves the chapter as JavaScript. Re-probing it
    # finds the same empty page; only a rendering-capable reader would change it.
    (re.compile(r"served no reading images", re.I),
     Cause.JAVASCRIPT_RENDERED, "converter:page_images_observed"),
    (re.compile(r"links to no works or chapters, which a JavaScript-rendered site",
                re.I),
     Cause.JAVASCRIPT_RENDERED, "arabic:no_work_or_chapter_links"),
    (re.compile(r"no chapter pages are served", re.I),
     Cause.JAVASCRIPT_RENDERED, "arabic:has_chapters"),
    # Deliberately not bypassed by this project, so it is never a gate to tune.
    # Anchored on the status phrase or a bracketed status: a bare `401|403`
    # substring also matches a URL like /series/403/ or a version 1.403, and
    # AUTH_WALLED is permanent - one such false positive retires a good source
    # for ever.
    (re.compile(r"refused public access|\(401/402/403\)|\b(?:401|403)\b"
                r"(?=\s|$|[\"',)])", re.I),
     Cause.AUTH_WALLED, "arabic:blocked_status"),
    (re.compile(r"redirects to another host|redirects off HTTPS", re.I),
     Cause.SITE_REFUSED, "arabic:redirect_scope"),
    (re.compile(r"is not a language tag|not HTTPS|no host to bind", re.I),
     Cause.SITE_REFUSED, "htmlsite:usable"),
    # The site answered, but not enough was seen to decide anything. This is the
    # one that most often means a budget ran out rather than a real limitation,
    # so it stays retryable.
    (re.compile(r"page budget|reached its page budget|not opened", re.I),
     Cause.INSUFFICIENT_EVIDENCE, "htmlsite:MAX_PAGES"),
    (re.compile(r"no entry link was observed|no entry hrefs|no observed hrefs",
                re.I),
     Cause.INSUFFICIENT_EVIDENCE, "cli:observed_hrefs"),
    (re.compile(r"share no repeated shape|is not a listing", re.I),
     Cause.INSUFFICIENT_EVIDENCE, "converter:derive_entry_shape"),
    # The site is readable and we simply failed to recognise it. Retryable once
    # the extractor changes.
    (re.compile(r"no page path prefix could be established", re.I),
     Cause.EXTRACTOR_LIMITED, "converter:derive_page_prefix"),
    (re.compile(r"did not match the page image pattern", re.I),
     Cause.EXTRACTOR_LIMITED, "converter:PAGE_IMAGE"),
    (re.compile(r"no page image pattern was proven", re.I),
     Cause.EXTRACTOR_LIMITED, "converter:PAGE_IMAGE"),
    (re.compile(r"does not occur in the catalogue URL", re.I),
     Cause.EXTRACTOR_LIMITED, "converter:entry_shape_check"),
    (re.compile(r"no page for the work itself|the work page could not be read", re.I),
     Cause.EXTRACTOR_LIMITED, "htmlsite:work_url"),
]

#: Which module owns each cause, for `blame()`.
_GATES = {
    Cause.OK: "-",
    Cause.JAVASCRIPT_RENDERED: "extractor (needs a rendering-capable reader)",
    Cause.AUTH_WALLED: "policy (correctly refuses; do not weaken)",
    Cause.EXTRACTOR_LIMITED: "shura_core/content/htmlsite.py + converter",
    Cause.INSUFFICIENT_EVIDENCE: "shura_core/content/htmlsite.py (budgets)",
    Cause.BUILD_FAILED: "shura_core/extension/builder.py (toolchain)",
    Cause.SITE_REFUSED: "sources.json / network policy",
    "unknown": "not classified - add a signature",
}


def classify(blockers: list[str], default: str = Cause.INSUFFICIENT_EVIDENCE) -> tuple[str, str]:
    """The dominant cause among ``blockers``, and the gate that raised it.

    The most severe cause present wins, not the first one: a plan carrying both
    "no images" and "no entry link" is a site with no readable pages, and
    reporting the second would send the agent back to fetch a listing it can
    never use.
    """
    severity = [Cause.JAVASCRIPT_RENDERED, Cause.AUTH_WALLED, Cause.SITE_REFUSED,
                Cause.EXTRACTOR_LIMITED, Cause.INSUFFICIENT_EVIDENCE]
    found: dict[str, str] = {}
    for blocker in blockers or []:
        for pattern, cause, gate in SIGNATURES:
            if pattern.search(blocker or ""):
                found.setdefault(cause, gate)
                break
        else:
            found.setdefault("unknown", blocker)
    if not found:
        return default, _GATES.get(default, "")
    for cause in severity:
        if cause in found:
            return cause, found[cause]
    unknown = found.get("unknown")
    return "unknown", unknown or ""


def classify_build(reason: str) -> tuple[str, str]:
    """A build that failed is the toolchain's fault until proven otherwise."""
    text = reason or ""
    if re.search(r"no dex in the APK contains it|produced no APK", text, re.I):
        return Cause.BUILD_FAILED, "builder:dex_or_apk_missing"
    if re.search(r"gradle failed", text, re.I):
        # --offline against an empty Gradle cache is the common cause and is not
        # the module's fault, so it is named separately from a compile error.
        if re.search(r"no cached version|offline mode|not available for offline", text, re.I):
            return Cause.BUILD_FAILED, "builder:gradle_cache_empty"
        return Cause.BUILD_FAILED, "builder:compile"
    return Cause.BUILD_FAILED, "builder:unknown"


def gate_for_cause(cause: str) -> str:
    return _GATES.get(cause, _GATES["unknown"])