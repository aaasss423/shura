"""One traceable content cycle: discover -> import -> gate -> fetch -> store.

This is the missing production loop. It runs the four stages that previously had
to be invoked by hand, and it does not blur them:

* discovery never writes to the store;
* import writes rows and grants nothing;
* approval is a separate, explicit act by an operator;
* fetching is gated per chapter, so a cycle with no approvals fetches nothing.

The cycle is safe to run repeatedly. Import is an idempotent upsert, so a
restart re-walks and converges rather than duplicating or losing work.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

from .fetcher import load_source_config, sync_source_policy
from .ingest import ImportRejected, import_payload
from .mangadex import (DiscoveryRejected, MangaDexLinkResolver, MangaDexSource,
                      SOURCE_ID as MANGADEX_SOURCE_ID)
from .runner import run_fetch_run

__all__ = ["run_content_cycle", "CycleReport"]


@dataclass
class CycleReport:
    """What one cycle did, stage by stage, so a run can be audited."""

    discovered_works: int = 0
    discovered_chapters: int = 0
    discovered_pages: int = 0
    imported_works: int = 0
    imported_chapters: int = 0
    imported_pages: int = 0
    #: Chapters that imported cleanly but still have no clearance decision.
    awaiting_approval: int = 0
    fetch: object | None = None
    discovery_error: str = ""
    import_error: str = ""

    def as_dict(self) -> dict:
        fetch = self.fetch.as_dict() if self.fetch is not None else None
        return {
            "discoveredWorks": self.discovered_works,
            "discoveredChapters": self.discovered_chapters,
            "discoveredPages": self.discovered_pages,
            "importedWorks": self.imported_works,
            "importedChapters": self.imported_chapters,
            "importedPages": self.imported_pages,
            "awaitingApproval": self.awaiting_approval,
            "fetch": fetch,
            "clearanceGranted": 0,
            "discoveryError": self.discovery_error,
            "importError": self.import_error,
        }


def run_content_cycle(conn, root: Path, *, http_factory, config_path: Path,
                      language: str = "ar", works: int = 5,
                      chapters_per_work: int = 2, pages_per_chapter: int = 5,
                      max_pages: int = 50, do_fetch: bool = True,
                      instance_id: str | None = None, host: str | None = None,
                      source_id: str | None = None) -> CycleReport:
    """Run one discovery->import->fetch cycle.

    ``http_factory(allowed_hosts)`` must return the project's guarded HTTP
    client. It is a factory rather than a client so the same code path serves
    discovery and the link refresh, and so a caller can supply a test double.
    """
    report = CycleReport()
    source_id = source_id or MANGADEX_SOURCE_ID
    config_path = Path(config_path)

    entries = load_source_config(config_path)
    sync_source_policy(conn, entries)
    policy = conn.execute(
        "SELECT allowed_hosts, fetch_enabled FROM source_policy WHERE source_id=?",
        (source_id,)).fetchone()
    if policy is None:
        report.discovery_error = (
            f"source {source_id!r} is not listed in {config_path.name}; nothing is discovered")
        return report
    if not int(policy["fetch_enabled"]):
        # Default-deny. A source that is present but switched off is not a
        # source this run may touch, at either the discovery or the fetch stage.
        report.discovery_error = (
            f"source {source_id!r} is not enabled in {config_path.name}; nothing is discovered")
        return report
    import json as _json
    hosts = _json.loads(policy["allowed_hosts"])
    if "api.mangadex.org" not in hosts:
        hosts = hosts + ["api.mangadex.org"]
    http = http_factory(hosts)

    source = MangaDexSource(http, source_id=source_id)
    try:
        manifest = source.manifest(language=language, works=works,
                                   chapters_per_work=chapters_per_work,
                                   pages_per_chapter=pages_per_chapter)
    except DiscoveryRejected as exc:
        report.discovery_error = str(exc)
        return report
    report.discovered_works = len(manifest["works"])
    report.discovered_chapters = len(manifest["chapters"])
    report.discovered_pages = len(manifest["pages"])

    try:
        result = import_payload(conn, manifest)
    except ImportRejected as exc:
        report.import_error = str(exc)
        return report
    report.imported_works = result["works"]
    report.imported_chapters = result["work_chapters"]
    report.imported_pages = result["chapter_pages"]

    # A chapter that imported but has no CLEARED decision is waiting on an
    # operator. It is reported, never silently treated as allowed.
    report.awaiting_approval = conn.execute(
        "SELECT COUNT(*) FROM work_chapters w WHERE NOT EXISTS ("
        "  SELECT 1 FROM element_clearance e"
        "  WHERE e.source_id = w.source_id AND e.ext_chapter_id = w.ext_chapter_id)"
    ).fetchone()[0]

    if not do_fetch:
        return report

    def resolver(http_client, resolved_source_id):
        return MangaDexLinkResolver(
            MangaDexSource(http_client, source_id=resolved_source_id),
            source_id=resolved_source_id)

    report.fetch = run_fetch_run(conn, root, config_path=config_path, max_pages=max_pages,
                                 instance_id=instance_id, host=host,
                                 link_resolver_factory=resolver)
    return report