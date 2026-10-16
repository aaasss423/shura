"""What the agent learned, kept in one place.

Until now a conversion that failed told you nothing afterwards. ``build_plan``
returns ``blockers`` and ``build_extension`` raises ``BuildFailure``; both reach
stdout and are then gone. The next attempt starts from nothing, and the same
site is re-probed from scratch by something that cannot remember what it already
learned.

This is that memory. Every attempt - converted, built, published, or refused -
is one row, keyed by the site it was about, carrying the blockers verbatim and
the cause those blockers were classified into. It answers the two questions the
loop actually needs to get better:

  * has this site ever been shown to be unconvertible, so it should not be
    re-probed for nothing;
  * which cause dominates, so the gate responsible is known to be the problem
    rather than the site.

It is deliberately a separate table from ``history``. ``history`` is an
append-only audit of what the pipeline decided; this is a queryable record of
what conversion *could not* do, and mixing them would mean every status screen
had to filter out an unbounded pile of learning rows.
"""

from __future__ import annotations

import json
import sqlite3
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from pathlib import Path

__all__ = ["Attempt", "Cause", "Knowledge", "LEARNED_SCHEMA"]

#: Appended to the state store's DDL. Kept idempotent like every other table
#: there: an existing database picks it up the next time it is opened.
LEARNED_SCHEMA = """
CREATE TABLE IF NOT EXISTS learned_attempts (
    source_id TEXT NOT NULL,
    at TEXT NOT NULL,
    stage TEXT NOT NULL,
    outcome TEXT NOT NULL,
    cause TEXT NOT NULL DEFAULT '',
    package TEXT NOT NULL DEFAULT '',
    blockers TEXT NOT NULL DEFAULT '[]',
    evidence TEXT NOT NULL DEFAULT '{}',
    detail TEXT NOT NULL DEFAULT '{}'
);
CREATE INDEX IF NOT EXISTS idx_learned_source ON learned_attempts(source_id, at);
CREATE INDEX IF NOT EXISTS idx_learned_cause ON learned_attempts(cause, at);
"""

#: How many attempts to remember per site. Beyond this the oldest rows are
#: dropped: the point is to recognise a site that keeps failing the same way,
#: not to keep a permanent archive of every probe.
MAX_ATTEMPTS_PER_SOURCE = 20


class Cause:
    """Why an attempt ended the way it did.

    These are categories, not messages. A site whose reader is JavaScript and a
    site whose pages are hidden behind a login both refuse to convert, but only
    one of them is worth re-probing after the gates change - so the distinction
    has to survive into storage, not just into a log line.
    """

    #: Converted, built and verified.
    OK = "ok"
    #: The site serves no readable images: JavaScript-rendered, or images are
    #: injected client-side. Re-probing will not change that.
    JAVASCRIPT_RENDERED = "javascript_rendered"
    #: The site requires an account. This project does not bypass access
    #: controls, so this is a permanent refusal rather than a gate to tune.
    AUTH_WALLED = "auth_walled"
    #: The extractor could not recognise the markup. This is the one category
    #: worth retrying once the extractor improves.
    EXTRACTOR_LIMITED = "extractor_limited"
    #: There was not enough evidence to decide anything - a budget ran out or
    #: the site answered inconsistently. Worth retrying.
    INSUFFICIENT_EVIDENCE = "insufficient_evidence"
    #: The plan was fine and the build broke. Infrastructure, not the site.
    BUILD_FAILED = "build_failed"
    #: The site refused outright, or redirected somewhere out of scope.
    SITE_REFUSED = "site_refused"


@dataclass
class Attempt:
    """One conversion attempt, as the loop remembers it."""

    source_id: str
    stage: str
    outcome: str
    cause: str = Cause.OK
    package: str = ""
    blockers: list[str] = field(default_factory=list)
    evidence: dict = field(default_factory=dict)
    detail: dict = field(default_factory=dict)
    at: str = ""

    def __post_init__(self):
        if not self.at:
            self.at = datetime.now(timezone.utc).isoformat()

    def as_dict(self) -> dict:
        return asdict(self)


class Knowledge:
    """The agent's memory of what it has already tried."""

    def __init__(self, store):
        # Accepts a StateStore or a bare sqlite3 connection, so the agent can be
        # driven against a scratch database without a full store.
        self.db = getattr(store, "db", store)
        self.db.executescript(LEARNED_SCHEMA)

    # -- writing ----------------------------------------------------------
    def record(self, attempt: Attempt) -> None:
        """Remember one attempt, and trim what it has superseded."""
        self.db.execute(
            "INSERT INTO learned_attempts"
            "(source_id,at,stage,outcome,cause,package,blockers,evidence,detail)"
            " VALUES (?,?,?,?,?,?,?,?,?)",
            (attempt.source_id, attempt.at, attempt.stage, attempt.outcome,
             attempt.cause, attempt.package, json.dumps(attempt.blockers),
             json.dumps(attempt.evidence), json.dumps(attempt.detail)),
        )
        self.db.execute(
            "DELETE FROM learned_attempts WHERE source_id=? AND rowid NOT IN"
            " (SELECT rowid FROM learned_attempts WHERE source_id=?"
            "  ORDER BY at DESC LIMIT ?)",
            (attempt.source_id, attempt.source_id, MAX_ATTEMPTS_PER_SOURCE),
        )
        self.db.commit()

    # -- reading ----------------------------------------------------------
    def attempts(self, source_id: str, limit: int = MAX_ATTEMPTS_PER_SOURCE) -> list[Attempt]:
        rows = self.db.execute(
            "SELECT source_id,at,stage,outcome,cause,package,blockers,evidence,detail"
            " FROM learned_attempts WHERE source_id=? ORDER BY at DESC LIMIT ?",
            (source_id, max(1, limit)),
        ).fetchall()
        return [Attempt(source_id=r[0], at=r[1], stage=r[2], outcome=r[3], cause=r[4],
                        package=r[5], blockers=json.loads(r[6]),
                        evidence=json.loads(r[7]), detail=json.loads(r[8]))
                for r in rows]

    def last(self, source_id: str) -> Attempt | None:
        found = self.attempts(source_id, limit=1)
        return found[0] if found else None

    def is_worth_retrying(self, source_id: str) -> tuple[bool, str]:
        """Whether this site should be probed again, and why not if it should not.

        Only permanent causes are excluded. A site that once produced no
        evidence, or whose build broke, is exactly the site that should be
        retried once the gate or the extractor changes - refusing to look again
        would make the agent unable to benefit from its own improvements.
        """
        previous = self.last(source_id)
        if previous is None:
            return True, ""
        if previous.outcome == "published":
            return False, "this site is already published"
        permanent = {Cause.JAVASCRIPT_RENDERED, Cause.AUTH_WALLED, Cause.SITE_REFUSED}
        if previous.cause in permanent:
            return False, f"{previous.cause} on the last attempt; re-probing would prove nothing new"
        return True, ""

    def cause_histogram(self, limit: int = 20) -> list[tuple[str, int]]:
        """Which causes dominate. The gate that is actually at fault shows up here."""
        rows = self.db.execute(
            "SELECT cause, COUNT(*) AS n FROM learned_attempts"
            " WHERE cause!='' GROUP BY cause ORDER BY n DESC LIMIT ?",
            (max(1, limit),),
        ).fetchall()
        return [(r[0], r[1]) for r in rows]

    def blame(self) -> list[dict]:
        """Name the gate behind each dominant cause.

        A cause on its own says the agent failed. Pairing it with the gate it
        came out of says which piece of policy is wrong: every
        ``extractor_limited`` refusal points at ``htmlsite`` rather than at the
        site, and that is a list of work, not a list of dead ends.
        """
        from .diagnose import gate_for_cause

        out = []
        for cause, n in self.cause_histogram():
            out.append({"cause": cause, "count": n, "gate": gate_for_cause(cause)})
        return out