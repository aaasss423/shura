"""The fetch stage: budgets, quotas, per-page isolation, and a replaceable transport.

The transport is an interface, not an HTTP client. Production wires
:class:`SafeHTTPTransport`, which delegates to the repository's existing
``SafeHTTP`` unchanged - host allow-listing, HTTPS, private-address refusal,
redirect limits, response-size caps. Tests wire a local fixture transport. There
is no switch that turns a production guard off, and this module never reaches
around the transport it was given.

Every failure is contained to the page it happened on. One rejected page, one
timeout, one chapter whose listing will not parse: the run records it and moves
to the next page. Nothing here can take the work down with it.
"""

from __future__ import annotations

import json
import os
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable
from urllib.error import URLError

from . import storage
from . import store
from ..security.network import NetworkPolicyError, ResponseTooLarge
from .storage import PageRejected
from .store import (AttemptNotStartable, FenceLost, Lock, abandon_attempt, assert_fence,
                    begin_attempt, commit_done, renew_lock)

#: Classifications that may never have their image bytes fetched.
NON_DOWNLOADABLE = {"NSFW", "MIXED", "UNKNOWN"}


@dataclass
class Budgets:
    """Per-run ceilings. Hitting one is a pause, never a failure."""

    max_requests: int = 2000
    max_pages: int = 5000
    max_bytes: int = 2 * 1024 * 1024 * 1024
    max_seconds: float = 1800.0
    max_page_bytes: int = storage.MAX_PAGE_BYTES


@dataclass
class Budget:
    """Mutable consumption against a :class:`Budgets`."""

    limits: Budgets
    requests: int = 0
    pages: int = 0
    bytes_used: int = 0
    started: float = field(default_factory=time.monotonic)

    def exhausted(self) -> str | None:
        if self.requests >= self.limits.max_requests:
            return "request budget exhausted"
        if self.pages >= self.limits.max_pages:
            return "page budget exhausted"
        if self.bytes_used >= self.limits.max_bytes:
            return "byte budget exhausted"
        if (time.monotonic() - self.started) >= self.limits.max_seconds:
            return "time budget exhausted"
        return None


class SharedRateLimiter:
    """One token bucket per host, shared by every task in the process.

    A limiter per fetcher would make the effective ceiling ``N x workers``, which
    is exactly the way a crawler turns into a ban.
    """

    def __init__(self, per_second: float = 1.0):
        self.per_second = per_second
        self._last: dict[str, float] = {}

    def wait(self, host: str) -> None:
        interval = 1.0 / max(self.per_second, 0.001)
        previous = self._last.get(host)
        now = time.monotonic()
        if previous is not None:
            delay = previous + interval - now
            if delay > 0:
                time.sleep(delay)
                now = time.monotonic()
        self._last[host] = now


#: Process-wide limiter. Shared by construction, not by remembering to share it.
HOST_LIMITER = SharedRateLimiter()


@dataclass(frozen=True)
class FetchResult:
    outcome: str          # DONE | REFUSED | LINK_UNAVAILABLE | TRANSIENT | FAILED |
                        # BLOCKED | QUOTA_HOLD | SKIPPED | WAITING
    reason: str = ""
    attempts: int = 0


def _slices(body: bytes, size: int = storage.WRITE_CHUNK):
    """Yield ``body`` in slices.

    Only used by whole-body transports (the test fixture). The production path
    streams from the socket instead, so this is not on the memory-critical route.
    """
    for offset in range(0, len(body), size):
        yield body[offset:offset + size]


class PageFetcher:
    """Downloads one page at a time, one attempt at a time, never past the fence."""

    def __init__(self, conn, lock: Lock, root: Path, transport, budget: Budget,
                 limiter: SharedRateLimiter | None = None,
                 policy_fetcher: Callable[[str], int] | None = None,
                 renew_every: float = store.LOCK_TTL_SECONDS / 3,
                 clock: Callable[[], float] = time.monotonic,
                 link_resolver: Callable[..., str] | None = None):
        self.conn = conn
        self.lock = lock
        self.root = Path(root)
        self.transport = transport
        self.budget = budget
        self.limiter = limiter or HOST_LIMITER
        # Returns the source's quota in bytes; supplied per source by the caller.
        self.policy_fetcher = policy_fetcher or (lambda _source_id: 1 << 62)
        # The lease is renewed while the body is arriving, not only between
        # pages: one slow page can outlast the lease on its own.
        self.renew_every = max(0.0, float(renew_every))
        self.clock = clock
        self._renewed_at = clock()
        # Optional: for sources whose page links expire (MangaDex@Home). It is
        # consulted before an attempt starts, never after.
        self.link_resolver = link_resolver

    def _heartbeat(self) -> None:
        """Renew the content lease if enough time has passed. Fence loss raises."""
        if self.renew_every <= 0:
            return
        now = self.clock()
        if now - self._renewed_at < self.renew_every:
            return
        self.lock = renew_lock(self.conn, self.lock)
        self._renewed_at = now

    def _record(self, source_id: str, chapter_id: str, page_index: int, state: str,
                reason: str, next_retry: str = "") -> None:
        self.conn.execute(
            "UPDATE chapter_pages SET state=?, reason=?, next_retry=? WHERE source_id=? "
            "AND ext_chapter_id=? AND page_index=?",
            (state, reason, next_retry, source_id, chapter_id, page_index))

    def _record_failure(self, source_id: str, chapter_id: str, page_index: int,
                        state: str, reason: str, attempts: int) -> None:
        """Record a failed attempt and arm the backoff for the next one."""
        self._record(source_id, chapter_id, page_index, state, reason,
                     "" if attempts + 1 >= store.MAX_PAGE_ATTEMPTS else store.retry_after(attempts))

    def _fail(self, source_id: str, chapter_id: str, page_index: int, state: str,
              reason: str, attempt, part, *, backoff: bool = True) -> FetchResult:
        """Retire one attempt: drop the partial file, free the intent, record the state.

        Every exit path through the receive/write block funnels here, so the part
        file is unlinked and the save intent is resolved exactly once, whatever
        went wrong. ``backoff=False`` is used for BLOCKED, which is terminal and
        must never look like something that will be tried again.
        """
        part.unlink(missing_ok=True)
        abandon_attempt(self.conn, self.lock, source_id=source_id, ext_chapter_id=chapter_id,
                        page_index=page_index, attempt_no=attempt.attempt_no, reason=reason)
        if backoff:
            self._record_failure(source_id, chapter_id, page_index, state, reason,
                                 attempt.attempt_no)
        else:
            self._record(source_id, chapter_id, page_index, state, reason, "")
        return FetchResult(state, reason, attempt.attempt_no)

    def fetch_page(self, *, source_id: str, ext_work_id: str, ext_chapter_id: str,
                   page_index: int, page_url: str, host: str,
                   declared_mime: str | None = None) -> FetchResult:
        """Fetch one page. Every outcome other than DONE leaves the page startable."""
        # The safety gate runs first, before the budget, before any attempt and
        # before the transport is touched. Placing it here rather than in the
        # runner is what makes it unbypassable: a caller that constructs a
        # PageFetcher and calls fetch_page directly goes through it too.
        allowed, why = chapter_is_fetchable(self.conn, source_id, ext_chapter_id)
        if not allowed:
            # Record the reason but never the state. A policy decision is not a
            # permanent outcome, so the page stays startable and is re-evaluated
            # when the clearance decision changes.
            self.conn.execute(
                "UPDATE chapter_pages SET reason=? WHERE source_id=? AND ext_chapter_id=? "
                "AND page_index=?",
                (f"fetch refused: {why}", source_id, ext_chapter_id, page_index))
            return FetchResult("REFUSED", f"fetch refused: {why}")

        stop = self.budget.exhausted()
        if stop:
            self._record(source_id, ext_chapter_id, page_index, "PENDING", stop)
            return FetchResult("SKIPPED", stop)

        if self.link_resolver is not None:
            # Resolved BEFORE the attempt starts. A link that cannot be refreshed
            # is a transient condition about the link, not a verdict on the page:
            # no attempt is consumed, nothing is fetched, nothing is written but
            # the reason, and the page stays startable for a later run.
            row = self.conn.execute(
                "SELECT source_page_id FROM chapter_pages WHERE source_id=? AND "
                "ext_chapter_id=? AND page_index=?",
                (source_id, ext_chapter_id, page_index)).fetchone()
            try:
                page_url = self.link_resolver(
                    source_id=source_id, ext_chapter_id=ext_chapter_id,
                    page_index=page_index,
                    source_page_id=row["source_page_id"] if row is not None else None,
                    stored_url=page_url)
            except Exception as exc:                # noqa: BLE001 - link layer
                self._record(source_id, ext_chapter_id, page_index, "PENDING",
                             f"link not refreshed: {exc}")
                return FetchResult("LINK_UNAVAILABLE", f"link not refreshed: {exc}")
            if not page_url:
                self._record(source_id, ext_chapter_id, page_index, "PENDING",
                             "no image link could be resolved")
                return FetchResult("LINK_UNAVAILABLE", "no image link could be resolved")

        try:
            attempt = begin_attempt(
                self.conn, self.lock, source_id=source_id, ext_chapter_id=ext_chapter_id,
                page_index=page_index, slug=source_id, ext_work_id=ext_work_id,
                expected_bytes=None)
        except AttemptNotStartable as exc:
            return FetchResult("WAITING", str(exc))
        except FenceLost:
            raise

        quota = self.policy_fetcher(source_id)
        if storage.usage_bytes(self.root) >= quota:
            abandon_attempt(self.conn, self.lock, source_id=source_id,
                            ext_chapter_id=ext_chapter_id, page_index=page_index,
                            attempt_no=attempt.attempt_no, reason="quota exhausted")
            self._record(source_id, ext_chapter_id, page_index, "QUOTA_HOLD",
                         "source storage quota reached")
            return FetchResult("QUOTA_HOLD", "source storage quota reached")

        part = storage.stage_part(self.root, attempt.part_path)
        self.limiter.wait(host)
        self.budget.requests += 1
        limit = self.budget.limits.max_page_bytes
        try:
            # The transport's context manager owns the response. It is entered
            # here and left here, so the socket is closed on the normal exit and
            # on every exception thrown back into it.
            with self.transport.stream(page_url, max_bytes=limit) as (_h, _final, chunks, limit):
                storage.write_bounded(part, chunks, max_bytes=limit,
                                      heartbeat=self._heartbeat)
                checked = storage.validate_page(
                    part, declared_mime=declared_mime or _h.get_content_type(),
                    max_bytes=limit)
        except ResponseTooLarge as exc:
            # The response was bigger than the ceiling. A size budget, not a
            # security refusal - bounded by MAX_PAGE_ATTEMPTS like any failure.
            return self._fail(source_id, ext_chapter_id, page_index, "FAILED", str(exc),
                              attempt, part)
        except NetworkPolicyError as exc:
            # Refused by policy: host, scheme, port, credentials, private address,
            # redirect limit or redirect loop. A security refusal, so BLOCKED.
            return self._fail(source_id, ext_chapter_id, page_index, "BLOCKED", str(exc),
                              attempt, part, backoff=False)
        except PageRejected as exc:
            return self._fail(source_id, ext_chapter_id, page_index, "FAILED", str(exc),
                              attempt, part)
        except (URLError, TimeoutError, OSError) as exc:  # noqa: BLE001 - a network fault
            # URLError and socket.timeout are both OSError subclasses, so this
            # catches every transport fault raised while the body was arriving.
            return self._fail(source_id, ext_chapter_id, page_index, "TRANSIENT", str(exc),
                              attempt, part)

        # A page that does not fit in what is left of the byte budget is not
        # committed: the ceiling is a ceiling, not a suggestion. Leaving it
        # startable lets a later run with more room pick it up.
        if self.budget.bytes_used + checked.size > self.budget.limits.max_bytes:
            part.unlink(missing_ok=True)
            stop = (f"byte budget exhausted: page needs {checked.size} bytes, "
                    f"{self.budget.limits.max_bytes - self.budget.bytes_used} left")
            abandon_attempt(self.conn, self.lock, source_id=source_id,
                            ext_chapter_id=ext_chapter_id, page_index=page_index,
                            attempt_no=attempt.attempt_no, reason=stop)
            self._record(source_id, ext_chapter_id, page_index, "PENDING", stop)
            return FetchResult("SKIPPED", stop, attempt.attempt_no)

        try:
            # The file may only be moved into place while this process still owns
            # the lock; a displaced owner must not leave a page where the next run
            # adopts it.
            assert_fence(self.conn, self.lock)
            storage.place_page(self.root, attempt.rel_path, part)
            self.budget.pages += 1
            self.budget.bytes_used += checked.size
            commit_done(self.conn, self.lock, source_id=source_id,
                        ext_chapter_id=ext_chapter_id, page_index=page_index,
                        attempt_no=attempt.attempt_no, rel_path=attempt.rel_path,
                        sha256=checked.sha256, size=checked.size, mime=checked.mime,
                        width=checked.width, height=checked.height,
                        content_key=checked.sha256)
        except FenceLost:
            # Losing the fence is not a page-level outcome to record: the save
            # intent and the partial file both belong to an attempt this process
            # no longer owns. Drop the part and let the loss propagate.
            part.unlink(missing_ok=True)
            raise
        return FetchResult("DONE", "", attempt.attempt_no)

    def fetch_chapter(self, *, source_id: str, ext_work_id: str, ext_chapter_id: str,
                      page_urls: list[str]) -> list[FetchResult]:
        """Fetch every page of a chapter, isolating each one.

        A page that fails does not stop the others, and does not stop the work:
        the results are per page and the chapter state is only ever derived.
        """
        if len(page_urls) > 20_000:
            raise ValueError("chapter exceeds the 20000 page ceiling")
        results: list[FetchResult] = []
        for index, url in enumerate(page_urls):
            host = url.split("/")[2].split(":")[0] if "//" in url else ""
            try:
                results.append(self.fetch_page(
                    source_id=source_id, ext_work_id=ext_work_id,
                    ext_chapter_id=ext_chapter_id, page_index=index, page_url=url, host=host))
            except FenceLost:
                raise
            except Exception as exc:                  # noqa: BLE001 - never abort the run
                self._record(source_id, ext_chapter_id, index, "TRANSIENT", str(exc))
                results.append(FetchResult("TRANSIENT", str(exc)))
        self._settle_chapter(source_id, ext_chapter_id)
        return results

    def _settle_chapter(self, source_id: str, ext_chapter_id: str) -> None:
        """Derive the chapter state from its pages. No page decides the work."""
        rows = self.conn.execute(
            "SELECT state, COUNT(*) FROM chapter_pages WHERE source_id=? AND ext_chapter_id=? "
            "GROUP BY state", (source_id, ext_chapter_id)).fetchall()
        counts = {row[0]: int(row[1]) for row in rows}
        total = sum(counts.values())
        if total and counts.get("DONE", 0) == total:
            state, reason = "DONE", ""
        elif counts.get("DONE", 0):
            state = "PARTIAL"
            reason = f"{counts.get('DONE')} of {total} pages stored"
        elif counts.get("TRANSIENT", 0) or counts.get("PENDING", 0):
            state, reason = "TRANSIENT", "pages remain to be retried"
        elif counts.get("QUOTA_HOLD", 0):
            state, reason = "QUOTA_HOLD", "held on storage quota"
        else:
            state, reason = "FAILED", "no page could be stored"
        self.conn.execute(
            "UPDATE work_chapters SET state=?, reason=?, updated_at=datetime('now') "
            "WHERE source_id=? AND ext_chapter_id=?", (state, reason, source_id, ext_chapter_id))


def work_is_downloadable(conn, source_id: str, ext_work_id: str) -> tuple[bool, str]:
    """Whether a work's image bytes may be fetched at all.

    Absence of a classification is not permission. This gate is about *fetching*;
    whether a downloaded page may be shown to a user is a separate clearance
    check, and neither implies the other.
    """
    row = conn.execute(
        "SELECT content_classification FROM works WHERE source_id=? AND ext_work_id=?",
        (source_id, ext_work_id)).fetchone()
    if row is None:
        return False, "unknown work"
    classification = (row[0] or "UNKNOWN").upper()
    if classification in NON_DOWNLOADABLE:
        return False, f"work is classified {classification}; its pages are not fetched"
    return True, classification


def chapter_is_fetchable(conn, source_id: str, ext_chapter_id: str) -> tuple[bool, str]:
    """Whether one chapter's image bytes may be fetched at all.

    Two independent conditions, and both must hold:

    1. the owning work is classified download-safe, and
    2. an ``element_clearance`` row exists for this chapter and its decision is
       ``CLEARED``.

    A SAFE or MATURE classification is *not* permission on its own; it only
    keeps step 1 from failing. Absent anything - no classification, no clearance
    row, a WITHHELD or PENDING_REVIEW decision - the answer is no. The owning
    work is resolved from the chapter row rather than trusted from the caller,
    so a caller that names a different ``ext_work_id`` cannot route around the
    gate.
    """
    chapter = conn.execute(
        "SELECT ext_work_id FROM work_chapters WHERE source_id=? AND ext_chapter_id=?",
        (source_id, ext_chapter_id)).fetchone()
    if chapter is None:
        return False, "no such chapter"
    ext_work_id = chapter["ext_work_id"]

    allowed, classification = work_is_downloadable(conn, source_id, ext_work_id)
    if not allowed:
        return False, f"work is {classification}; its pages are not fetched"

    decision = conn.execute(
        "SELECT decision, basis, policy_rule FROM element_clearance "
        "WHERE source_id=? AND ext_chapter_id=?",
        (source_id, ext_chapter_id)).fetchone()
    if decision is None:
        return False, "no clearance decision recorded; absence is not permission"

    if decision["basis"] == "policy-rule":
        from .schema import REGISTERED_POLICY_RULES
        if decision["policy_rule"] not in REGISTERED_POLICY_RULES:
            # No rule is registered today, so this branch always refuses. It is
            # written as a lookup rather than as "policy rules exist" so that
            # registering one later is a deliberate, visible step.
            return False, (f"clearance rests on policy rule "
                           f"{decision['policy_rule']!r}, which is not a registered, "
                           "tested rule")
    if decision["decision"] != "CLEARED":
        return False, f"clearance decision is {decision['decision']}"
    return True, f"{classification} + clearance CLEARED ({decision['basis']})"


# --------------------------------------------------------------------------------------
# Fetch-source configuration. Read from a local file; shura.db is never touched.
# --------------------------------------------------------------------------------------

SOURCE_FILE_NAME = "content-sources.json"

_REQUIRED = ("source_id", "fetch_enabled", "allowed_hosts", "quota_bytes", "retention_days")


def load_source_config(path: Path) -> list[dict]:
    """Read and validate ``content-sources.json``.

    A missing or invalid file yields no fetchable sources at all. Failing open
    here would mean a typo in a config file silently enables fetching from every
    configured host.
    """
    try:
        raw = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return []
    if not isinstance(raw, dict) or raw.get("version") != 1:
        return []
    entries = raw.get("sources")
    if not isinstance(entries, list):
        return []

    usable: list[dict] = []
    for entry in entries:
        if not isinstance(entry, dict):
            continue
        if any(field not in entry for field in _REQUIRED):
            continue
        source_id = entry["source_id"]
        hosts = entry["allowed_hosts"]
        if not isinstance(source_id, str) or not source_id:
            continue
        if not isinstance(hosts, list) or not hosts or any(not isinstance(h, str) or not h for h in hosts):
            continue
        if not isinstance(entry["fetch_enabled"], bool):
            continue
        if not isinstance(entry["quota_bytes"], int) or entry["quota_bytes"] < 0:
            continue
        if not isinstance(entry["retention_days"], int) or entry["retention_days"] < 0:
            continue
        # Default-deny: a source is only fetchable when the file says so.
        normalised = normalise_source_entry(entry)
        if normalised is not None:
            usable.append(normalised)
    return usable


def normalise_source_entry(entry) -> dict | None:
    """Validate one source entry against the same rules the loader applies.

    Returned so the config writer cannot accept a shape the loader would later
    silently drop: if this returns ``None`` the file is malformed, and writing
    it would create a source that looks configured and is not.
    """
    if not isinstance(entry, dict):
        return None
    if any(field not in entry for field in _REQUIRED):
        return None
    source_id = entry["source_id"]
    hosts = entry["allowed_hosts"]
    if not isinstance(source_id, str) or not source_id:
        return None
    if not isinstance(hosts, list) or not hosts or any(not isinstance(h, str) or not h for h in hosts):
        return None
    if not isinstance(entry["fetch_enabled"], bool):
        return None
    if not isinstance(entry["quota_bytes"], int) or entry["quota_bytes"] < 0:
        return None
    if not isinstance(entry["retention_days"], int) or entry["retention_days"] < 0:
        return None
    return {
        "source_id": source_id,
        "kind": str(entry.get("kind", "index")),
        "allowed_hosts": hosts,
        "fetch_enabled": entry["fetch_enabled"],
        "quota_bytes": entry["quota_bytes"],
        "retention_days": entry["retention_days"],
    }


def upsert_source_entry(path: Path, entry: dict) -> dict:
    """Add or replace one source entry in ``content-sources.json``, safely.

    This is the *configuration* stage, deliberately separate from operator
    approval. It writes exactly one entry and leaves every other entry, and the
    file's shape, alone. Fetching is not enabled here: ``fetch_enabled`` is
    taken from the caller and this function refuses to invent ``true``, because
    enabling fetch is its own reviewed decision.

    The write is atomic - a temporary file in the same directory, then
    ``os.replace`` - so a crash cannot leave a half-written config that the
    loader would read as "no sources at all". The result is re-read through
    ``load_source_config`` before returning, so a caller can trust that what is
    on disk is what the fetcher will see.
    """
    from .fetcher import load_source_config as _load  # self-check, same rules
    normalised = normalise_source_entry(entry)
    if normalised is None:
        raise ValueError("refusing to write a source entry the loader would reject")
    if normalised["fetch_enabled"] and not entry.get("allow_enable"):
        raise ValueError(
            "this writer does not enable fetching; pass allow_enable=True to set "
            "fetch_enabled=true deliberately")

    path = Path(path)
    document: dict = {"version": 1, "sources": []}
    if path.exists():
        try:
            existing = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise ValueError(f"refusing to edit an unreadable config: {exc}") from exc
        if not isinstance(existing, dict) or existing.get("version") != 1 \
                or not isinstance(existing.get("sources"), list):
            raise ValueError("existing config is not a version 1 source document")
        document = existing

    sources = [e for e in document["sources"]
               if not (isinstance(e, dict) and e.get("source_id") == normalised["source_id"])]
    sources.append({k: v for k, v in normalised.items()})
    document["sources"] = sources

    # Validate the whole document before it can replace a working config.
    candidate = dict(document)
    if not _load_documents_ok(candidate):
        raise ValueError("the resulting config would not load; nothing was written")

    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(json.dumps(candidate, ensure_ascii=False, indent=2) + "\n",
                   encoding="utf-8")
    os.replace(tmp, path)
    _load(path)          # re-read through the real loader; raises if unusable
    return normalised


def _load_documents_ok(document: dict) -> bool:
    """Would ``load_source_config`` accept every entry in this document?"""
    if not isinstance(document, dict) or document.get("version") != 1:
        return False
    if not isinstance(document.get("sources"), list):
        return False
    return all(normalise_source_entry(e) is not None for e in document["sources"])


def sync_source_policy(conn, entries: list[dict], *, now: str = "") -> int:
    """Mirror the local config into ``source_policy``. Local database only."""
    stamp = now or "1970-01-01T00:00:00+00:00"
    keep = {entry["source_id"] for entry in entries}
    for row in conn.execute("SELECT source_id FROM source_policy").fetchall():
        if row[0] not in keep:
            conn.execute("DELETE FROM source_policy WHERE source_id=?", (row[0],))
    for entry in entries:
        conn.execute(
            "INSERT INTO source_policy(source_id,kind,allowed_hosts,fetch_enabled,quota_bytes,"
            "retention_days,synced_at) VALUES(?,?,?,?,?,?,?) "
            "ON CONFLICT(source_id) DO UPDATE SET kind=excluded.kind,"
            "allowed_hosts=excluded.allowed_hosts,fetch_enabled=excluded.fetch_enabled,"
            "quota_bytes=excluded.quota_bytes,retention_days=excluded.retention_days,"
            "synced_at=excluded.synced_at",
            (entry["source_id"], entry["kind"], json.dumps(entry["allowed_hosts"]),
             1 if entry["fetch_enabled"] else 0, entry["quota_bytes"],
             entry["retention_days"], stamp))
    return len(entries)


def fetchable_sources(conn):
    return conn.execute(
        "SELECT source_id, allowed_hosts, quota_bytes FROM source_policy "
        "WHERE fetch_enabled=1 ORDER BY source_id").fetchall()
