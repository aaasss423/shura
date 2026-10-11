"""One content fetch run, driven by the local ``content-sources.json``.

Activation comes from that file and nowhere else. :func:`load_source_config` is
already default-deny - a missing, unreadable or invalid file yields no sources
at all, and a source is fetchable only when its own ``fetch_enabled`` is true.
This module deliberately does not call :func:`sync_source_policy` and does not
query the ``source_policy`` table, so no database row can turn fetching on and
``shura.db`` is not written by the activation decision.

The content lock is a single row (``fetch_lock``, ``lock_id = 1``): one fetching
process at a time, no parallel workers. The lease is renewed as the run goes and
released in ``finally`` whatever happens.
"""

from __future__ import annotations

import os
import socket
from dataclasses import dataclass, field
from pathlib import Path

from ..security.network import SafeHTTP
from .fetcher import SOURCE_FILE_NAME, Budget, Budgets, PageFetcher, load_source_config
from .store import STARTABLE, FenceLost, LockUnavailable, acquire_lock, release_lock, renew_lock

__all__ = ["run_fetch_run", "FetchRunReport", "default_identity"]


@dataclass
class FetchRunReport:
    """What one run did. ``blocked`` is counted apart: a refusal is not a failure."""

    sources_considered: int = 0
    sources_fetched: list[str] = field(default_factory=list)
    pages_done: int = 0
    refused: int = 0
    link_unavailable: int = 0
    transient: int = 0
    failed: int = 0
    blocked: int = 0
    skipped: int = 0
    locked_out: bool = False

    def as_dict(self) -> dict:
        return {
            "sourcesConsidered": self.sources_considered,
            "sourcesFetched": list(self.sources_fetched),
            "pagesDone": self.pages_done,
            "pagesRefused": self.refused,
            "pagesLinkUnavailable": self.link_unavailable,
            "pagesTransient": self.transient,
            "pagesFailed": self.failed,
            "pagesBlocked": self.blocked,
            "pagesSkipped": self.skipped,
            "lockedOut": self.locked_out,
        }


def default_identity() -> tuple[str, str]:
    """Owner identity for the lock as ``(instance, host)`` - pid plus hostname."""
    return f"pid-{os.getpid()}", socket.gethostname()


def _startable_pages(conn, source_id: str, limit: int) -> list[tuple[str, int, str]]:
    """Pages in a STARTABLE state, as ``(chapter_id, page_index, url)``.

    STARTABLE is the gate: BLOCKED and DONE are absent from it, so a page blocked
    by policy or already stored can never be picked up here.
    """
    placeholders = ",".join("?" for _ in STARTABLE)
    rows = conn.execute(
        f"SELECT ext_chapter_id, page_index, source_page_url FROM chapter_pages "
        f"WHERE source_id=? AND state IN ({placeholders}) "
        f"ORDER BY ext_chapter_id, page_index LIMIT ?",
        (source_id, *STARTABLE, limit)).fetchall()
    return [(r["ext_chapter_id"], r["page_index"], r["source_page_url"]) for r in rows]


def _work_id(conn, source_id: str, chapter_id: str) -> str:
    row = conn.execute(
        "SELECT ext_work_id FROM work_chapters WHERE source_id=? AND ext_chapter_id=?",
        (source_id, chapter_id)).fetchone()
    return row["ext_work_id"] if row else chapter_id


def run_fetch_run(conn, root: Path, *, config_path: Path | None = None,
                  budget: Budget | None = None, instance_id: str | None = None,
                  host: str | None = None, max_pages: int = 50,
                  link_resolver_factory=None) -> FetchRunReport:
    """Fetch startable pages for every source the local config enables.

    Never raises for a per-page or per-source problem; lock contention is reported
    rather than thrown. A lost fence *is* propagated - it means another process
    owns the work now, and continuing would be unsafe.
    """
    report = FetchRunReport()
    config_path = Path(config_path) if config_path else Path(SOURCE_FILE_NAME)
    entries = load_source_config(config_path)
    report.sources_considered = len(entries)

    # Default-deny again at the point of use: an entry that is present but not
    # enabled is not a source this run will fetch from.
    enabled = [e for e in entries if e["fetch_enabled"]]
    if not enabled:
        return report

    if not (instance_id and host):
        instance_id, host = default_identity()
    try:
        lock = acquire_lock(conn, instance_id, host)
    except LockUnavailable:
        report.locked_out = True
        return report

    try:
        budget = budget or Budget(Budgets())
        for entry in enabled:
            source_id = entry["source_id"]
            quota = int(entry["quota_bytes"])
            # SafeHTTP's own ceiling stays independent; the fetcher narrows the
            # per-page limit by passing max_page_bytes into stream().
            http = SafeHTTP(entry["allowed_hosts"])
            from .transport import SafeHTTPTransport
            fetcher = PageFetcher(conn, lock, Path(root), SafeHTTPTransport(http), budget,
                                  policy_fetcher=lambda _sid, q=quota: q)
            report.sources_fetched.append(source_id)
            for chapter_id, page_index, url in _startable_pages(conn, source_id, max_pages):
                if budget.exhausted():
                    report.skipped += 1
                    continue
                # The lease is short; renew as we go so a long run cannot lose the
                # fence it is still relying on. renew_lock raises FenceLost if lost.
                lock = renew_lock(conn, lock)
                fetcher.lock = lock
                try:
                    result = fetcher.fetch_page(
                        source_id=source_id,
                        ext_work_id=_work_id(conn, source_id, chapter_id),
                        ext_chapter_id=chapter_id, page_index=page_index,
                        page_url=url, host=url.split("/")[2] if "//" in url else "")
                except FenceLost:
                    raise
                except Exception:                    # noqa: BLE001 - isolate one page
                    report.transient += 1
                    continue
                if result.outcome == "DONE":
                    report.pages_done += 1
                elif result.outcome == "REFUSED":
                    # A safety-gate refusal. Not a failure, and not a retry:
                    # nothing was attempted, so nothing was consumed.
                    report.refused += 1
                elif result.outcome == "LINK_UNAVAILABLE":
                    report.link_unavailable += 1
                elif result.outcome == "BLOCKED":
                    report.blocked += 1
                elif result.outcome in ("TRANSIENT", "WAITING"):
                    report.transient += 1
                elif result.outcome == "SKIPPED":
                    report.skipped += 1
                else:
                    report.failed += 1
    finally:
        release_lock(conn, lock)
    return report