from __future__ import annotations
from dataclasses import dataclass, field, asdict
from datetime import datetime, timezone
from enum import StrEnum
from hashlib import sha256
import json
from typing import Any


def now() -> str:
    return datetime.now(timezone.utc).isoformat()

class SourceState(StrEnum):
    ACTIVE="ACTIVE"; ACCEPTED="ACCEPTED"; RETRY_LATER="RETRY_LATER"; PAUSED="PAUSED"; DEAD="DEAD"; QUARANTINED="QUARANTINED"

@dataclass
class Source:
    source_id: str
    name: str
    url: str
    kind: str
    language: str = "und"
    region: str | None = None
    enabled: bool = True
    state: SourceState = SourceState.ACTIVE
    configuration: dict[str, Any] = field(default_factory=dict)
    configuration_fingerprint: str = ""
    created_at: str = field(default_factory=now)
    updated_at: str = field(default_factory=now)
    last_attempt: str | None = None
    last_success: str | None = None
    last_failure: str | None = None
    next_retry: str | None = None
    failure_reason: str | None = None
    attempt_count: int = 0
    def fingerprint(self) -> str:
        safe = json.dumps(self.configuration, sort_keys=True, separators=(",", ":"))
        material = "\0".join((self.name,self.url,self.kind,self.language,self.region or "",safe))
        return sha256(material.encode()).hexdigest()

@dataclass
class Candidate:
    source_id: str
    package: str
    version: str
    apk_url: str
    name: str = ""
    language: str = "und"
    extension_lib: str = ""
    signing_key: str | None = None
    metadata: dict[str, Any] = field(default_factory=dict)
    provenance: dict[str, Any] = field(default_factory=dict)
    discovered_at: str = field(default_factory=now)
    @property
    def identity(self) -> str: return f"{self.package}|{self.version}"
    def as_dict(self) -> dict[str, Any]: return asdict(self)

class ChapterStatus(StrEnum):
    HEALTHY="HEALTHY"
    # Preview slice verified but the chapter's remaining pages are served via deferred/protected
    # media that the manifest does not publish. Recorded distinctly so full chapter health is
    # never silently asserted on a preview-only check.
    PARTIAL="PARTIAL"
    PAID="PAID"; TEMPORARY_FAILURE="TEMPORARY_FAILURE"; STALE_LINK="STALE_LINK"; PARSE_ERROR="PARSE_ERROR"; UNAVAILABLE="UNAVAILABLE"

RETRYABLE_CHAPTER_STATUSES=(ChapterStatus.TEMPORARY_FAILURE,ChapterStatus.STALE_LINK,ChapterStatus.PARSE_ERROR)

@dataclass
class ChapterRecord:
    """Per-chapter content verdict. Chapter health is tracked independently of the work,
    so a damaged chapter never degrades decisions about the manga/series as a whole."""
    work_id: str
    chapter_id: str
    status: ChapterStatus
    reason: str = ""
    pages: list[str] = field(default_factory=list)
    attempts: int = 0
    def identity(self) -> str: return f"{self.work_id}|{self.chapter_id}"

@dataclass
class WorkReview:
    """Work-level summary derived from independent per-chapter verdicts. A work is
    publishable whenever it has at least one healthy, public chapter; damaged or paid
    chapters are recorded and excluded but never drag the whole work down."""
    work_id: str
    work_name: str
    verdict: str
    reason: str = ""
    totals: dict[str, int] = field(default_factory=dict)
    chapters: list[ChapterRecord] = field(default_factory=list)

@dataclass
class ProcessingResult:
    identity: str
    source_id: str
    stage: str
    verdict: str
    reason: str = ""
    artifact_sha256: str | None = None
    created_at: str = field(default_factory=now)

@dataclass
class Publication:
    run_id: str
    published_this_run: int
    publications_today: int
    publication_charged: bool
    outcome: str
    created_at: str = field(default_factory=now)
