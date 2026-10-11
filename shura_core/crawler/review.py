"""Operator review of discovered manga/manhwa sites.

Discovery and evaluation produce *candidates*. An operator then decides about
each one, and that decision is a separate act from having found it.

The five stages, deliberately not collapsed into each other:

1. **discovery** - :class:`~shura_core.crawler.arabic.SearchFinder` proposes a
   hostname from a public search API.
2. **evaluation** - :func:`~shura_core.crawler.arabic.evaluate_site` probes it
   and records a verdict with reasons.
3. **operator approval** - this module. The operator accepts or rejects a
   candidate, with a reason.
4. **configuration** - the operator adds the host to ``content-sources.json``.
5. **fetch enablement** - a further, independent edit that sets
   ``fetch_enabled`` to true.

Acceptance is stage 3 only. It does not write the config, does not name a host
in any allowlist, and does not make anything fetchable. Nothing in this module
can be reached from a discovered site without a human typing a decision.

**State lives in the existing event ledger**, not in a new table. The ledger is
already append-only, already audited, and already the project's record of what
happened and who did it; a candidate is just another subject in it. A candidate
is identified by a stable id (``site:<domain>``), so a repeat pass recognises a
site it has already proposed and a decided candidate never returns to the
waiting list.
"""

from __future__ import annotations

import json

__all__ = ["PENDING", "ACCEPTED", "REJECTED", "ReviewError",
           "record_review", "candidate_records", "pending_candidates",
           "decided_candidates", "deferred_candidates", "candidate_status"]

PENDING = "pending"
ACCEPTED = "accepted"
REJECTED = "rejected"

#: An operator must say why. "Rejected because I said so" is not a review.
_REASON_REQUIRED = (REJECTED,)

_EVENT_PROPOSED = "site_proposed"
_EVENT_DECIDED = "site_reviewed"


class ReviewError(ValueError):
    """A review action that must not be recorded."""


def _rows(store, event: str):
    try:
        return store.db.execute(
            "SELECT source_id, detail FROM history WHERE event=? ORDER BY id",
            (event,)).fetchall()
    except Exception:                                        # noqa: BLE001
        return []


def record_review(store, source_id: str, decision: str, *, operator: str,
                  reason: str = "") -> dict:
    """Record one operator decision about a proposed candidate.

    Idempotent in the sense that matters: re-deciding a candidate is refused
    rather than silently overwriting the earlier decision, so the record of what
    was decided, when, and by whom stays intact. To revisit a decision, record a
    new one deliberately via ``reopen``.
    """
    if not source_id or not isinstance(source_id, str):
        raise ReviewError("a candidate id is required")
    if decision not in (ACCEPTED, REJECTED):
        raise ReviewError(f"decision must be {ACCEPTED!r} or {REJECTED!r}")
    if decision in _REASON_REQUIRED and not reason.strip():
        raise ReviewError("a rejection must carry a reason")
    if not operator.strip():
        raise ReviewError("a review must name the operator who made it")

    if not _proposed(store, source_id):
        raise ReviewError(
            f"{source_id} is not a proposed candidate; nothing to review")

    existing = _latest_decision(store, source_id)
    if existing is not None:
        raise ReviewError(
            f"{source_id} was already {existing['status']} by {existing['operator']}; "
            "decide again deliberately rather than overwriting the record")

    record = {"source_id": source_id, "status": decision,
              "operator": operator, "reason": reason.strip()}
    store.event(source_id, _EVENT_DECIDED, record)
    return record


def reopen(store, source_id: str, *, operator: str, reason: str) -> dict:
    """Deliberately re-open a decided candidate for another look.

    The earlier decision stays in the ledger; this adds a superseding one.
    """
    if not reason.strip():
        raise ReviewError("re-opening a candidate must say why")
    record = {"source_id": source_id, "status": PENDING, "operator": operator,
              "reason": reason.strip(), "supersedes": _latest_decision(store, source_id)}
    store.event(source_id, _EVENT_DECIDED, record)
    return record


def _proposed(store, source_id: str) -> bool:
    return any(sid == source_id for sid, _ in _rows(store, _EVENT_PROPOSED))


def _latest_decision(store, source_id: str) -> dict | None:
    latest = None
    for sid, detail in _rows(store, _EVENT_DECIDED):
        if sid != source_id:
            continue
        try:
            latest = json.loads(detail)
        except (TypeError, json.JSONDecodeError):
            continue
    return latest


def candidate_status(store, source_id: str) -> str:
    """Where one candidate stands: pending, accepted or rejected."""
    decision = _latest_decision(store, source_id)
    return decision["status"] if decision else PENDING


def candidate_records(store) -> list[dict]:
    """Every candidate with its evidence and its review state.

    Built by reading the ledger, so there is no second copy to fall out of step
    with what the crawler actually did.
    """
    proposals: dict[str, dict] = {}
    for sid, detail in _rows(store, _EVENT_PROPOSED):
        try:
            record = json.loads(detail)
        except (TypeError, json.JSONDecodeError):
            continue
        record["source_id"] = sid
        proposals[sid] = record

    decisions: dict[str, dict] = {}
    for sid, detail in _rows(store, _EVENT_DECIDED):
        try:
            decisions[sid] = json.loads(detail)
        except (TypeError, json.JSONDecodeError):
            continue

    out = []
    for sid, record in proposals.items():
        decision = decisions.get(sid)
        record = dict(record)
        record["review"] = ({"status": PENDING,
                             "operator": decision["operator"],
                             "reason": decision.get("reason", ""),
                             "reopened": decision.get("supersedes") is not None}
                            if decision and decision["status"] == PENDING
                            else decision)
        record["status"] = candidate_status(store, sid)
        out.append(record)
    out.sort(key=lambda r: r["source_id"])
    return out


def pending_candidates(store) -> list[dict]:
    """Candidates nobody has decided about yet."""
    return [r for r in candidate_records(store) if r["status"] == PENDING]


def decided_candidates(store) -> list[dict]:
    """Candidates already accepted or rejected, so they stay out of the queue."""
    return [r for r in candidate_records(store) if r["status"] != PENDING]


def deferred_candidates(store) -> list[dict]:
    """Sites the evaluator could not confirm - public, Arabic, nothing to show.

    They carry no proposal (only a confirmed ``CANDIDATE`` does), so they are
    read straight from the verdict ledger. Re-evaluating one appends fresh
    evidence without creating a second proposal entry.
    """
    latest: dict[str, dict] = {}
    for sid, detail in _rows(store, "site_candidate"):
        try:
            record = json.loads(detail)
        except (TypeError, json.JSONDecodeError):
            continue
        if record.get("verdict") == "DEFERRED":
            latest[sid] = record
    out = []
    for sid, record in latest.items():
        if candidate_status(store, sid) != PENDING:
            continue
        record = dict(record)
        record["source_id"] = sid
        out.append(record)
    out.sort(key=lambda r: r["source_id"])
    return out