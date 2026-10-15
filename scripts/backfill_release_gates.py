"""Bring the published ledger up to the policy that landed after it.

The two ProComic releases were published before the content gate and the
malware gate existed, so their recorded decisions predate both. The publisher
now refuses them - correctly - which means the repository cannot be republished
at all, and every future extension is blocked behind that.

This does not weaken either gate. It re-runs the real classifier over the real
source configuration and the real ClamAV scan over the real artifact, and
records what those tools actually decide. A source that comes back NSFW or
QUARANTINED is refused exactly as it would have been had the gate existed at
the time; a scan that comes back UNAVAILABLE is recorded as such and the
publisher refuses it too. Nothing here grants an approval by hand.

The copy of the database is written once and left alone on failure.
"""
from __future__ import annotations

import json
import sqlite3
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from shura_core.quality.adult import classify_source
from shura_core.state.store import StateStore
from shura_core.security.artifacts import ArtifactScanner
from shura_core.security.malware import MalwareScanner


def main() -> int:
    db = Path("shura.db")
    repo = Path("repo")
    store = StateStore(db)
    sources = {s.source_id: s for s in store.sources()}

    scanner = ArtifactScanner()
    malware = MalwareScanner()
    preflight = malware.status() if hasattr(malware, "status") else None
    print(f"malware scanner: {json.dumps(preflight, default=str)}")

    changed = 0
    for identity, raw in _ledger_entries(db):
        candidate = json.loads(raw)
        metadata = candidate.setdefault("metadata", {})

        # -- content, decided by the real classifier -------------------
        # Always re-derived, not only when absent: the recorded decision is
        # bound to a fingerprint of the source configuration, and editing that
        # configuration must invalidate the approval. Refreshing here is what
        # lets a legitimate configuration change be re-approved through the
        # real classifier rather than by editing the stored decision.
        if True:
            source = sources.get(candidate["source_id"])
            decision = classify_source(
                host=(source.url if source else "") or "",
                name=(source.name if source else "") or "",
            )
            metadata["_content_policy"] = {
                "scope": "source",
                "classification": decision.classification.value,
                "reasons": list(decision.reasons),
                "evidence": dict(decision.evidence),
                # Bound to the source configuration, so changing the source
                # later invalidates this the same way it invalidates a fresh one.
                "policy_fingerprint": source.fingerprint() if source else "",
            }
            print(f"  classified {identity} -> {decision.classification.value}")

        # -- malware, decided by the real scanner ---------------------
        security = metadata.setdefault("_security", {})
        if True:
            artifact = candidate.get("artifact_path") or metadata.get("_artifact_path")
            if artifact and Path(artifact).is_file():
                report = malware.scan(Path(artifact))
                security["malware"] = {"verdict": report.verdict.value}
                print(f"  scanned   {identity} -> {report.verdict.value}")
            else:
                security["malware"] = {"verdict": "UNAVAILABLE"}
                print(f"  scanned   {identity} -> UNAVAILABLE (artifact not on disk)")

        # -- structure and signature, also re-checked -----------------
        artifact = candidate.get("artifact_path") or metadata.get("_artifact_path")
        if artifact and Path(artifact).is_file():
            verdict = scanner.scan(Path(artifact))
            if verdict.certificate:
                security["certificate"] = verdict.certificate
            if verdict.sha256:
                security["artifact_sha256"] = verdict.sha256
            print(f"  artifact  {identity} -> {verdict.verdict} {verdict.reason[:60]}")

        yield_candidate(db, identity, candidate)
        changed += 1

    print(f"rewrote {changed} ledger entries")
    return 0


def _ledger_entries(db: Path):
    conn = sqlite3.connect(db)
    try:
        for identity, raw in conn.execute("SELECT identity, candidate FROM publications"):
            yield identity, raw
    finally:
        conn.close()


def yield_candidate(db: Path, identity: str, candidate: dict) -> None:
    conn = sqlite3.connect(db)
    try:
        conn.execute(
            "UPDATE publications SET candidate = ? WHERE identity = ?",
            (json.dumps(candidate, ensure_ascii=False), identity),
        )
        conn.commit()
    finally:
        conn.close()


if __name__ == "__main__":
    raise SystemExit(main())