"""Locking, fencing and the download-attempt lifecycle for the content database.

Two problems are solved here, both of which have bitten this project before.

**A stale owner must not be able to write.** A lease that merely expires is not
enough: an owner that was paused past its lease can wake up and commit work after
somebody else has taken over. So ownership is a fencing token - ``fetch_lock.
monotonic``, which only ever increases - and every result is committed only if
the token still matches, both before the file is moved into place and again
inside the transaction that records it.

**An attempt is one atomic step.** Flipping a page to ``IN_PROGRESS``, counting
the attempt, recording which attempt is active and writing the save intent all
happen in a single ``BEGIN IMMEDIATE``. If any part fails, none of it happened:
there is no state in which a page looks busy with nothing on disk to prove it.
"""

from __future__ import annotations

import os
import sqlite3
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

from .schema import LOCK_TTL_SECONDS

#: States from which starting a download attempt is legitimate.
#: FAILED is startable, but only within a bounded number of attempts - see
#: MAX_PAGE_ATTEMPTS. It records that the last attempt was rejected and why, not
#: that the page is permanently ungettable. BLOCKED is deliberately absent: a
#: content-gated or otherwise refused page is never retried, no matter how many
#: attempts it has left.
STARTABLE = ("PENDING", "TRANSIENT", "QUOTA_HOLD", "FAILED")

#: Attempts after which a page is retired instead of retried. Without a ceiling
#: a source that serves an invalid file would be re-fetched on every run, forever.
MAX_PAGE_ATTEMPTS = 5

#: Backoff before the next attempt: doubles per attempt, capped at the maximum.
RETRY_BASE_SECONDS = 60
RETRY_MAX_SECONDS = 3600


def retry_after(attempts: int) -> str:
    """The earliest time attempt number ``attempts + 1`` may start."""
    delay = min(RETRY_BASE_SECONDS * (2 ** max(0, attempts - 1)), RETRY_MAX_SECONDS)
    return _plus(delay)


def _reached(ts: str) -> bool:
    """True if the ISO timestamp ``ts`` is in the past (or unparseable/empty)."""
    if not ts:
        return True
    try:
        return datetime.fromisoformat(ts) <= datetime.now(timezone.utc)
    except ValueError:
        return True


class LockUnavailable(RuntimeError):
    """Another live process holds the content lock."""


class FenceLost(RuntimeError):
    """This process no longer owns the lock; its writes must not land."""


class AttemptNotStartable(RuntimeError):
    """An earlier attempt on this page is unresolved."""


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _plus(seconds: int) -> str:
    return (datetime.now(timezone.utc) + timedelta(seconds=seconds)).isoformat()


@dataclass(frozen=True)
class Lock:
    """A held lock. ``token`` is the fencing token for every write that follows."""

    token: int
    owner_instance: str
    owner_host: str
    expires_at: str


@dataclass(frozen=True)
class Recovered:
    """A file found after a crash, with the identity needed to accept it.

    Only produced when a save intent ties the file to a specific page *and* the
    attempt that produced it. The caller re-verifies the bytes before recording
    anything; this says which page the evidence points at, not that it is good.
    """

    source_id: str
    ext_chapter_id: str
    page_index: int
    attempt_no: int
    rel_path: str
    sha256: str
    size: int


@dataclass(frozen=True)
class Attempt:
    """One download attempt, with the single attempt number everything derives from."""

    source_id: str
    ext_chapter_id: str
    page_index: int
    attempt_no: int
    rel_path: str
    part_path: str


def acquire_lock(conn: sqlite3.Connection, owner_instance: str, owner_host: str,
                 ttl_seconds: int = LOCK_TTL_SECONDS) -> Lock:
    """Take the content lock, or raise.

    The row is permanent, so ``monotonic`` is read and incremented with a
    compare-and-swap rather than regenerated. A live owner blocks acquisition;
    an expired lease is taken over; and the ``rowcount == 1`` assertion means two
    processes racing the same expired lease cannot both believe they won.
    """
    now = _now()
    expires = _plus(ttl_seconds)
    conn.execute("BEGIN IMMEDIATE")
    try:
        row = conn.execute(
            "SELECT monotonic, owner_instance, lease_expires_at FROM fetch_lock WHERE lock_id=1"
        ).fetchone()
        if row is None:
            raise LockUnavailable("fetch_lock row is missing; run migrate() first")
        held_by = row["owner_instance"]
        lease = row["lease_expires_at"]
        if held_by is not None and held_by != owner_instance and lease is not None and lease > now:
            raise LockUnavailable(
                f"content lock held by {held_by} until {lease}; refusing to run concurrently"
            )
        token = int(row["monotonic"]) + 1
        changed = conn.execute(
            "UPDATE fetch_lock SET monotonic=?, owner_instance=?, owner_host=?, "
            "acquired_at=?, heartbeat_at=?, lease_expires_at=? "
            "WHERE lock_id=1 AND monotonic=?",
            (token, owner_instance, owner_host, now, now, expires, int(row["monotonic"])),
        ).rowcount
        if changed != 1:
            raise LockUnavailable("lost the compare-and-swap on the content lock")
        conn.execute("COMMIT")
    except Exception:
        conn.execute("ROLLBACK")
        raise
    return Lock(token=token, owner_instance=owner_instance, owner_host=owner_host, expires_at=expires)


def renew_lock(conn: sqlite3.Connection, lock: Lock, ttl_seconds: int = LOCK_TTL_SECONDS) -> Lock:
    """Extend the lease. Losing the lock raises instead of returning quietly."""
    now = _now()
    expires = _plus(ttl_seconds)
    changed = conn.execute(
        "UPDATE fetch_lock SET heartbeat_at=?, lease_expires_at=? "
        "WHERE lock_id=1 AND owner_instance=? AND monotonic=?",
        (now, expires, lock.owner_instance, lock.token),
    ).rowcount
    if changed != 1:
        raise FenceLost("lost the content lock; stopping before writing anything else")
    return Lock(lock.token, lock.owner_instance, lock.owner_host, expires)


def release_lock(conn: sqlite3.Connection, lock: Lock) -> bool:
    """Release the lock without deleting the row, so the token keeps counting up."""
    changed = conn.execute(
        "UPDATE fetch_lock SET owner_instance=NULL, owner_host=NULL, acquired_at=NULL, "
        "heartbeat_at=NULL, lease_expires_at=NULL "
        "WHERE lock_id=1 AND owner_instance=? AND monotonic=?",
        (lock.owner_instance, lock.token),
    ).rowcount
    return changed == 1


def assert_fence(conn: sqlite3.Connection, lock: Lock) -> None:
    """Refuse to continue if this process is no longer the owner."""
    row = conn.execute(
        "SELECT monotonic, owner_instance FROM fetch_lock WHERE lock_id=1"
    ).fetchone()
    if row is None or int(row["monotonic"]) != lock.token or row["owner_instance"] != lock.owner_instance:
        raise FenceLost(
            f"fence token {lock.token} is stale (lock now at "
            f"{row['monotonic'] if row else 'missing'}); discarding this result"
        )


def unresolved_attempt(conn: sqlite3.Connection, source_id: str, ext_chapter_id: str,
                       page_index: int) -> sqlite3.Row | None:
    """The save intent left behind by an attempt that has not been resolved."""
    return conn.execute(
        "SELECT * FROM save_intents WHERE source_id=? AND ext_chapter_id=? AND page_index=? "
        "ORDER BY attempt_no DESC LIMIT 1",
        (source_id, ext_chapter_id, page_index),
    ).fetchone()


def begin_attempt(conn: sqlite3.Connection, lock: Lock, *, source_id: str, ext_chapter_id: str,
                  page_index: int, slug: str, ext_work_id: str,
                  expected_bytes: int | None = None) -> Attempt:
    """Start one download attempt, atomically, and return its single attempt number.

    Acceptance is decided inside the same transaction that changes state: the
    page must be in a startable state, must not already carry an active attempt,
    and must have no unresolved save intent. An earlier attempt is never
    overwritten - it has to be recovered or abandoned first.
    """
    assert_fence(conn, lock)
    now = _now()
    conn.execute("BEGIN IMMEDIATE")
    try:
        assert_fence(conn, lock)
        page = conn.execute(
            "SELECT state, active_attempt_no, attempts, next_retry, reason FROM chapter_pages "
            "WHERE source_id=? AND ext_chapter_id=? AND page_index=?",
            (source_id, ext_chapter_id, page_index),
        ).fetchone()
        if page is None:
            raise KeyError(f"no such page: {source_id}/{ext_chapter_id}/{page_index}")
        if page["state"] not in STARTABLE:
            raise AttemptNotStartable(
                f"page {page_index} is {page['state']}, not startable")
        if int(page["attempts"]) >= MAX_PAGE_ATTEMPTS:
            raise AttemptNotStartable(
                f"page {page_index} has used all {MAX_PAGE_ATTEMPTS} attempts "
                f"(last reason: {page['reason'] or 'none'}); it is retired, not retried")
        if not _reached(page["next_retry"]):
            raise AttemptNotStartable(
                f"page {page_index} is backing off until {page['next_retry']}")
        if page["active_attempt_no"] is not None:
            raise AttemptNotStartable(
                f"page {page_index} already has attempt {page['active_attempt_no']} active; "
                "recover or abandon it before starting another")
        if page["attempts"] > 0 and page["active_attempt_no"] is None and page["state"] == "IN_PROGRESS":
            raise AttemptNotStartable("inconsistent attempt counters")
        pending = unresolved_attempt(conn, source_id, ext_chapter_id, page_index)
        if pending is not None:
            raise AttemptNotStartable(
                f"page {page_index} has an unresolved save intent from attempt "
                f"{pending['attempt_no']}; recover or abandon it first")

        attempt_no = int(page["attempts"]) + 1
        rel_path = (f"pages/{slug}/{ext_work_id}/{ext_chapter_id}"
                    f"/g{attempt_no:04d}/p{page_index:04d}.img")
        part_path = f"content/tmp/{ext_chapter_id}/g{attempt_no:04d}-{page_index:04d}.part"
        conn.execute(
            "UPDATE chapter_pages SET state='IN_PROGRESS', attempts=?, active_attempt_no=?, "
            "reason='', next_retry='' WHERE source_id=? AND ext_chapter_id=? AND page_index=?",
            (attempt_no, attempt_no, source_id, ext_chapter_id, page_index),
        )
        conn.execute(
            "INSERT INTO save_intents(source_id,ext_chapter_id,page_index,attempt_no,rel_path,"
            "part_path,expected_bytes,started_at,owner_instance,fence_token) "
            "VALUES(?,?,?,?,?,?,?,?,?,?)",
            (source_id, ext_chapter_id, page_index, attempt_no, rel_path, part_path,
             expected_bytes, now, lock.owner_instance, lock.token),
        )
        conn.execute("COMMIT")
    except Exception:
        conn.execute("ROLLBACK")
        raise
    return Attempt(source_id, ext_chapter_id, page_index, attempt_no, rel_path, part_path)


def abandon_attempt(conn: sqlite3.Connection, lock: Lock, *, source_id: str, ext_chapter_id: str,
                    page_index: int, attempt_no: int, reason: str) -> None:
    """Retire an unresolved attempt so the page can be started again."""
    assert_fence(conn, lock)
    conn.execute("BEGIN IMMEDIATE")
    try:
        assert_fence(conn, lock)
        conn.execute(
            "UPDATE chapter_pages SET state='PENDING', active_attempt_no=NULL, reason=? "
            "WHERE source_id=? AND ext_chapter_id=? AND page_index=? AND active_attempt_no=?",
            (reason, source_id, ext_chapter_id, page_index, attempt_no),
        )
        conn.execute(
            "DELETE FROM save_intents WHERE source_id=? AND ext_chapter_id=? AND page_index=? "
            "AND attempt_no=?",
            (source_id, ext_chapter_id, page_index, attempt_no),
        )
        conn.execute("COMMIT")
    except Exception:
        conn.execute("ROLLBACK")
        raise


def commit_done(conn: sqlite3.Connection, lock: Lock, *, source_id: str, ext_chapter_id: str,
                page_index: int, attempt_no: int, rel_path: str, sha256: str, size: int,
                mime: str, width: int, height: int, content_key: str) -> None:
    """Record a verified page. Rejected outright if this process lost the lock."""
    assert_fence(conn, lock)
    now = _now()
    conn.execute("BEGIN IMMEDIATE")
    try:
        assert_fence(conn, lock)
        changed = conn.execute(
            "UPDATE chapter_pages SET state='DONE', rel_path=?, sha256=?, bytes=?, mime=?, "
            "width=?, height=?, content_key=?, verified_at=?, active_attempt_no=NULL "
            "WHERE source_id=? AND ext_chapter_id=? AND page_index=? AND active_attempt_no=?",
            (rel_path, sha256, size, mime, width, height, content_key, now,
             source_id, ext_chapter_id, page_index, attempt_no),
        ).rowcount
        if changed != 1:
            raise AttemptNotStartable(
                f"page {page_index} is not on attempt {attempt_no}; refusing to commit a stale result")
        conn.execute(
            "DELETE FROM save_intents WHERE source_id=? AND ext_chapter_id=? AND page_index=? "
            "AND attempt_no=? AND fence_token=?",
            (source_id, ext_chapter_id, page_index, attempt_no, lock.token),
        )
        conn.execute("COMMIT")
    except Exception:
        conn.execute("ROLLBACK")
        raise


def recover_interrupted(conn: sqlite3.Connection, *, root: str) -> list[Attempt]:
    """Reconcile attempts left behind by a crash.

    A file is adopted only when the save intent proves which page and which
    attempt produced it, and only when that attempt is still the one the page is
    waiting on. An orphaned file with no intent is never guessed at.

    Returns the attempts that were resolved; the caller re-verifies the bytes
    before turning any of them into ``DONE``.
    """
    import hashlib
    from pathlib import Path

    resolved: list[Recovered] = []
    base = Path(root)
    rows = conn.execute(
        "SELECT i.*, p.active_attempt_no FROM save_intents i "
        "JOIN chapter_pages p ON p.source_id=i.source_id AND p.ext_chapter_id=i.ext_chapter_id "
        "AND p.page_index=i.page_index WHERE p.state='IN_PROGRESS'"
    ).fetchall()
    for row in rows:
        attempt_no = int(row["attempt_no"])
        part = base / row["part_path"]
        final = base / row["rel_path"]
        if int(row["active_attempt_no"] or -1) != attempt_no:
            # A newer attempt owns this page; this file belongs to a dead generation.
            part.unlink(missing_ok=True)
            final.unlink(missing_ok=True)
            conn.execute(
                "DELETE FROM save_intents WHERE source_id=? AND ext_chapter_id=? "
                "AND page_index=? AND attempt_no=?",
                (row["source_id"], row["ext_chapter_id"], row["page_index"], attempt_no),
            )
            continue
        if final.is_file():
            digest = hashlib.sha256(final.read_bytes()).hexdigest()
            resolved.append(Recovered(row["source_id"], row["ext_chapter_id"],
                                      int(row["page_index"]), attempt_no,
                                      row["rel_path"], digest, final.stat().st_size))
        else:
            part.unlink(missing_ok=True)
            conn.execute(
                "UPDATE chapter_pages SET state='PENDING', active_attempt_no=NULL "
                "WHERE source_id=? AND ext_chapter_id=? AND page_index=? AND active_attempt_no=?",
                (row["source_id"], row["ext_chapter_id"], row["page_index"], attempt_no),
            )
            conn.execute(
                "DELETE FROM save_intents WHERE source_id=? AND ext_chapter_id=? "
                "AND page_index=? AND attempt_no=?",
                (row["source_id"], row["ext_chapter_id"], row["page_index"], attempt_no),
            )
    return resolved


def grant_clearance(conn: sqlite3.Connection, *, source_id: str, ext_work_id: str,
                    ext_chapter_id: str, decision: str, basis: str, decided_by: str,
                    evidence: str, policy_version: str, policy_rule: str | None = None) -> None:
    """Record a per-chapter clearance decision.

    ``basis='policy-rule'`` requires a rule that exists and is tested. There is
    none registered, so that basis is refused rather than silently accepted: a
    hand-written rule id would otherwise read as a classifier that was never
    implemented.
    """
    from .schema import REGISTERED_POLICY_RULES

    if basis == "policy-rule" and policy_rule not in REGISTERED_POLICY_RULES:
        raise ValueError(
            f"no implemented policy rule named {policy_rule!r}; "
            "policy-rule clearance cannot be granted without one that exists and is tested"
        )
    conn.execute(
        "INSERT INTO element_clearance(source_id,ext_work_id,ext_chapter_id,decision,basis,"
        "policy_rule,decided_by,evidence,policy_version,decided_at) VALUES(?,?,?,?,?,?,?,?,?,?) "
        "ON CONFLICT(source_id,ext_chapter_id) DO UPDATE SET decision=excluded.decision,"
        "basis=excluded.basis,policy_rule=excluded.policy_rule,decided_by=excluded.decided_by,"
        "evidence=excluded.evidence,policy_version=excluded.policy_version,"
        "decided_at=excluded.decided_at",
        (source_id, ext_work_id, ext_chapter_id, decision, basis, policy_rule,
         decided_by, evidence, policy_version, _now()),
    )
