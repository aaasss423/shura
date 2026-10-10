"""Source and title content classification for publication policy.

This is deliberately separate from :mod:`shura_core.pipeline.content`, which
answers "is this chapter healthy" and never "should this be published". Mixing
the two is how an explicit source gets through because one chapter 404s, or a
work gets dropped because its pages were temporarily unreachable.

Classification is multi-signal and every decision carries the evidence that
produced it, so a decision can be audited and re-derived later:

* an explicit declaration in the source configuration, which outranks inference;
* host-level signals, since an extension is served from a domain;
* descriptive signals (name, tags, description) for the source;
* descriptive signals for an individual title inside an otherwise safe source,
  because a general site can still carry one explicit title.

``MATURE`` is never treated as ``NSFW``. An age warning is not evidence of
explicit content, and conflating the two rejects legitimate catalogues.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Iterable, Mapping


class SourceClass(str, Enum):
    SAFE = "SAFE"
    MATURE = "MATURE"
    NSFW = "NSFW"
    MIXED = "MIXED"
    UNKNOWN = "UNKNOWN"
    QUARANTINED = "QUARANTINED"


# Terms that indicate explicit sexual content. Deliberately narrow: a broad
# lexicon produces false positives on ordinary catalogue vocabulary.
EXPLICIT_TERMS = (
    "porn", "porno", "pornography", "hentai", "sex comic", "adult comic",
    "explicit sex", "erotic manga", "ncgi", "lewd comic", "sexo", "pornhub",
    "rule34", "e-hentai", "hitomi", "tsumino",
)

# Age/prudence warnings. NOT evidence of explicit content on their own.
MATURE_TERMS = ("mature", "18+", "17+", "16+", "15+", "teen+", "adults only", "content warning")

_DECLARED = {c.value.lower(): c for c in SourceClass}


@dataclass(frozen=True)
class Assessment:
    """One classification decision plus the evidence behind it."""

    classification: SourceClass
    reasons: tuple[str, ...] = ()
    evidence: Mapping[str, Any] = field(default_factory=dict)

    @property
    def publishable(self) -> bool:
        """May this reach the public repository without an operator decision?"""
        return self.classification is SourceClass.SAFE

    def to_dict(self) -> dict[str, Any]:
        return {
            "classification": self.classification.value,
            "reasons": list(self.reasons),
            "evidence": dict(self.evidence),
        }


def _haystack(*values: Any) -> str:
    return " ".join(str(v) for v in values if v).lower()


def _hits(text: str, terms: Iterable[str]) -> list[str]:
    return [t for t in terms if re.search(rf"(?<![a-z0-9]){re.escape(t)}(?![a-z0-9])", text)]


def classify_source(
    *,
    declared: str | None = None,
    host: str | None = None,
    name: str | None = None,
    description: str | None = None,
    tags: Iterable[str] | None = None,
) -> Assessment:
    """Classify a source from its own signals.

    A declaration in the source configuration outranks inference in both
    directions: an operator asserting SAFE is trusted, and an operator asserting
    NSFW is never overridden by a clean-looking description.
    """
    if declared:
        chosen = _DECLARED.get(str(declared).strip().lower())
        if chosen is not None:
            return Assessment(chosen, (f"declared by source configuration: {declared}",), {"declared": declared})

    reasons: list[str] = []
    evidence: dict[str, Any] = {}

    host_hits = _hits((host or "").lower(), EXPLICIT_TERMS)
    if host_hits:
        evidence["host_terms"] = host_hits
        return Assessment(SourceClass.NSFW, (f"host {host} carries explicit-content markers: {host_hits}",), evidence)

    tag_list = list(tags or ())
    text = _haystack(name, description, " ".join(tag_list))
    explicit = _hits(text, EXPLICIT_TERMS)
    mature = _hits(text, MATURE_TERMS)
    evidence["explicit_terms"] = explicit
    evidence["mature_terms"] = mature

    if explicit and mature:
        reasons.append(f"explicit and maturity markers together: {explicit + mature}")
        return Assessment(SourceClass.MIXED, tuple(reasons), evidence)
    if explicit:
        reasons.append(f"explicit-content markers: {explicit}")
        return Assessment(SourceClass.NSFW, tuple(reasons), evidence)
    if mature:
        reasons.append(f"maturity markers only, not evidence of explicit content: {mature}")
        return Assessment(SourceClass.MATURE, tuple(reasons), evidence)
    if name or description or tag_list:
        reasons.append(f"scanned the declared metadata and found no explicit-content marker")
        return Assessment(SourceClass.SAFE, tuple(reasons), evidence)
    # A bare hostname says nothing about what the catalogue serves. Treating it
    # as SAFE would turn "we know nothing" into "we checked and it is fine".
    reasons.append(f"only a host was available ({host}); not enough to classify a catalogue")
    return Assessment(SourceClass.UNKNOWN, tuple(reasons), evidence)


def classify_work(*, work_id: str, name: str | None, tags: Iterable[str] | None = None,
                  description: str | None = None) -> Assessment:
    """Classify a single title, independent of the source that hosts it.

    A safe source is allowed to contain one explicit title, and that title must
    not ride the source's verdict out into publication.
    """
    tag_list = list(tags or ())
    text = _haystack(name, description, " ".join(tag_list))
    explicit = _hits(text, EXPLICIT_TERMS)
    mature = _hits(text, MATURE_TERMS)
    evidence = {"work_id": work_id, "explicit_terms": explicit, "mature_terms": mature}
    if explicit:
        return Assessment(SourceClass.NSFW, (f"title carries explicit-content markers: {explicit}",), evidence)
    if mature:
        return Assessment(SourceClass.MATURE, (f"maturity markers only: {mature}",), evidence)
    return Assessment(SourceClass.UNKNOWN, ("no per-title signal available",), evidence)
