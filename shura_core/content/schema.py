"""Schema for the content database: downloaded works, chapters and pages.

This database is deliberately separate from ``shura.db``. The crawler state
database answers "what has been discovered and may it be published"; this one
answers "what have we downloaded and verified". Mixing them would put an
operator's accept/publish decision in the same rows as a byte download, and the
first thing that goes wrong when they share a table is a quarantined artifact
looking merely validated.

Phase one delivers the schema, its migrations and the locking/attempt lifecycle.
It fetches nothing, opens no socket and touches no repository file.

Design points that are easy to get wrong and are therefore stated in the DDL:

* ``page_key_kind`` is a biconditional. A row cannot claim a stable identity it
  does not carry, and cannot carry a stable identity while claiming none. This
  is what keeps "reorder detection" honest: when the kind is ``none`` the row
  records that reorder cannot be detected rather than implying it can.
* ``source_page_url`` is the last observed *download* URL and is never an
  identity. Many sources hand out expiring links that differ on every listing.
  ``stable_page_url`` is only promoted once the same URL has been seen twice.
* ``chapter_pages.active_attempt_no`` is declared here, not added later by an
  ALTER, so a fresh database and a migrated one have identical shape.
* ``fetch_lock`` is a permanent single row. It is never deleted, only released,
  so ``monotonic`` - the fencing token - can never be reset by a clean exit.
"""

from __future__ import annotations

import sqlite3

SCHEMA_VERSION = 2

#: Policy rules that may back an element clearance. Empty on purpose: no
#: per-element classification rule is implemented or tested yet, so
#: ``basis='policy-rule'`` can never be granted. Adding an id here is the
#: deliberate step that says "this rule exists and is covered by tests".
REGISTERED_POLICY_RULES: frozenset[str] = frozenset()

LOCK_TTL_SECONDS = 90
HEARTBEAT_SECONDS = 30

_TABLES = (
    """
    CREATE TABLE IF NOT EXISTS schema_version(
      version INTEGER NOT NULL,
      applied_at TEXT NOT NULL,
      applied_by TEXT NOT NULL,
      note TEXT NOT NULL,
      PRIMARY KEY(version))
    """,
    """
    CREATE TABLE IF NOT EXISTS source_policy(
      source_id TEXT PRIMARY KEY,
      kind TEXT NOT NULL,
      allowed_hosts TEXT NOT NULL,
      fetch_enabled INTEGER NOT NULL DEFAULT 0 CHECK(fetch_enabled IN (0,1)),
      quota_bytes INTEGER NOT NULL CHECK(quota_bytes >= 0),
      retention_days INTEGER NOT NULL CHECK(retention_days >= 0),
      synced_at TEXT NOT NULL)
    """,
    """
    CREATE TABLE IF NOT EXISTS fetch_lock(
      lock_id INTEGER PRIMARY KEY CHECK(lock_id = 1),
      monotonic INTEGER NOT NULL DEFAULT 0 CHECK(monotonic >= 0),
      owner_instance TEXT,
      owner_host TEXT,
      acquired_at TEXT,
      heartbeat_at TEXT,
      lease_expires_at TEXT)
    """,
    """
    CREATE TABLE IF NOT EXISTS works(
      source_id TEXT NOT NULL REFERENCES source_policy(source_id) ON DELETE CASCADE,
      ext_work_id TEXT NOT NULL,
      source_work_id TEXT NOT NULL DEFAULT '',
      title TEXT NOT NULL DEFAULT '',
      source_url TEXT NOT NULL DEFAULT '',
      content_classification TEXT CHECK(content_classification IN
        ('SAFE','MATURE','NSFW','MIXED','UNKNOWN')),
      content_policy_version TEXT,
      classified_at TEXT,
      pinned INTEGER NOT NULL DEFAULT 0 CHECK(pinned IN (0,1)),
      retention_days INTEGER,
      link_status TEXT NOT NULL DEFAULT 'NONE' CHECK(link_status IN
        ('NONE','UNREVIEWED','CONFIRMED','REJECTED')),
      link_confidence TEXT,
      linked_work_id TEXT,
      link_evidence TEXT,
      discovered_at TEXT NOT NULL,
      updated_at TEXT NOT NULL,
      PRIMARY KEY(source_id, ext_work_id))
    """,
    """
    CREATE TABLE IF NOT EXISTS work_chapters(
      source_id TEXT NOT NULL,
      ext_chapter_id TEXT NOT NULL,
      ext_work_id TEXT NOT NULL,
      source_chapter_id TEXT NOT NULL,
      chapter_number REAL,
      is_special INTEGER NOT NULL DEFAULT 0 CHECK(is_special IN (0,1)),
      ordinal INTEGER NOT NULL,
      title TEXT NOT NULL DEFAULT '',
      source_url TEXT NOT NULL DEFAULT '',
      page_count INTEGER,
      reorder_detectable INTEGER NOT NULL DEFAULT 0 CHECK(reorder_detectable IN (0,1)),
      listing_hash TEXT,
      listing_epoch INTEGER NOT NULL DEFAULT 0,
      state TEXT NOT NULL DEFAULT 'PENDING' CHECK(state IN
        ('PENDING','IN_PROGRESS','PARTIAL','DONE','FAILED','TRANSIENT',
         'BLOCKED_PAID','QUOTA_HOLD')),
      pinned INTEGER NOT NULL DEFAULT 0 CHECK(pinned IN (0,1)),
      reason TEXT NOT NULL DEFAULT '',
      attempts INTEGER NOT NULL DEFAULT 0 CHECK(attempts >= 0),
      next_retry TEXT NOT NULL DEFAULT '',
      first_seen TEXT NOT NULL,
      updated_at TEXT NOT NULL,
      PRIMARY KEY(source_id, ext_chapter_id),
      FOREIGN KEY(source_id, ext_work_id)
        REFERENCES works(source_id, ext_work_id) ON DELETE CASCADE,
      CHECK(chapter_number IS NULL OR is_special = 0))
    """,
    """
    CREATE TABLE IF NOT EXISTS chapter_pages(
      source_id TEXT NOT NULL,
      ext_chapter_id TEXT NOT NULL,
      page_index INTEGER NOT NULL CHECK(page_index >= 0),
      ordinal INTEGER NOT NULL,

      source_page_id TEXT,
      source_page_url TEXT NOT NULL DEFAULT '',
      stable_page_url TEXT,
      page_key_kind TEXT NOT NULL DEFAULT 'none' CHECK(page_key_kind IN
        ('source_page_id','stable_url','none')),

      current_rev INTEGER NOT NULL DEFAULT 1 CHECK(current_rev >= 1),
      rel_path TEXT, sha256 TEXT, bytes INTEGER,
      mime TEXT, width INTEGER, height INTEGER,
      content_key TEXT, listing_epoch INTEGER NOT NULL DEFAULT 0,

      state TEXT NOT NULL DEFAULT 'PENDING' CHECK(state IN
        ('PENDING','IN_PROGRESS','DONE','TRANSIENT','FAILED','BLOCKED','QUOTA_HOLD')),
      pinned INTEGER NOT NULL DEFAULT 0 CHECK(pinned IN (0,1)),
      retired INTEGER NOT NULL DEFAULT 0 CHECK(retired IN (0,1)),
      active_attempt_no INTEGER,
      attempts INTEGER NOT NULL DEFAULT 0 CHECK(attempts >= 0),
      reason TEXT NOT NULL DEFAULT '',
      next_retry TEXT NOT NULL DEFAULT '',
      created_at TEXT NOT NULL,
      verified_at TEXT,

      PRIMARY KEY(source_id, ext_chapter_id, page_index),
      FOREIGN KEY(source_id, ext_chapter_id)
        REFERENCES work_chapters(source_id, ext_chapter_id) ON DELETE CASCADE,

      -- Identity claims and identity data must agree, both directions.
      CHECK((page_key_kind = 'source_page_id')
            = (source_page_id IS NOT NULL AND length(trim(source_page_id)) > 0)),
      CHECK((page_key_kind = 'stable_url')
            = (stable_page_url IS NOT NULL AND length(trim(stable_page_url)) > 0)),
      CHECK(page_key_kind <> 'none' OR (source_page_id IS NULL AND stable_page_url IS NULL)),

      -- DONE is impossible without the evidence for it.
      CHECK(state <> 'DONE' OR (rel_path IS NOT NULL AND sha256 IS NOT NULL
            AND bytes IS NOT NULL AND mime IS NOT NULL AND width IS NOT NULL
            AND height IS NOT NULL AND verified_at IS NOT NULL)),
      CHECK(bytes IS NULL OR bytes > 0),
      CHECK(sha256 IS NULL OR (length(sha256) = 64 AND sha256 NOT GLOB '*[^0-9a-f]*')),
      CHECK(content_key IS NULL OR (length(content_key) = 64 AND content_key NOT GLOB '*[^0-9a-f]*')),
      CHECK(width IS NULL OR height IS NULL
            OR (width > 0 AND height > 0 AND width * height <= 50000000)),
      CHECK(verified_at IS NULL OR state = 'DONE'),
      CHECK(state <> 'IN_PROGRESS' OR active_attempt_no IS NOT NULL))
    """,
    """
    CREATE TABLE IF NOT EXISTS chapter_page_versions(
      source_id TEXT NOT NULL,
      ext_chapter_id TEXT NOT NULL,
      page_index INTEGER NOT NULL,
      rev INTEGER NOT NULL CHECK(rev >= 1),
      content_key TEXT NOT NULL,
      sha256 TEXT NOT NULL,
      bytes INTEGER NOT NULL CHECK(bytes > 0),
      mime TEXT NOT NULL,
      width INTEGER NOT NULL CHECK(width > 0),
      height INTEGER NOT NULL CHECK(height > 0),
      rel_path TEXT NOT NULL,
      superseded_at TEXT NOT NULL,
      superseded_by_rev INTEGER,
      PRIMARY KEY(source_id, ext_chapter_id, page_index, rev))
    """,
    """
    CREATE TABLE IF NOT EXISTS save_intents(
      source_id TEXT NOT NULL,
      ext_chapter_id TEXT NOT NULL,
      page_index INTEGER NOT NULL,
      attempt_no INTEGER NOT NULL CHECK(attempt_no >= 1),
      rel_path TEXT NOT NULL,
      part_path TEXT NOT NULL,
      expected_bytes INTEGER,
      started_at TEXT NOT NULL,
      owner_instance TEXT NOT NULL,
      fence_token INTEGER NOT NULL CHECK(fence_token >= 1),
      PRIMARY KEY(source_id, ext_chapter_id, page_index, attempt_no))
    """,
    """
    CREATE TABLE IF NOT EXISTS pending_unlinks(
      source_id TEXT NOT NULL,
      ext_chapter_id TEXT NOT NULL,
      page_index INTEGER NOT NULL,
      attempt_no INTEGER NOT NULL,
      rel_path TEXT NOT NULL,
      bytes INTEGER NOT NULL CHECK(bytes > 0),
      sha256 TEXT NOT NULL,
      expires_at TEXT NOT NULL,
      PRIMARY KEY(source_id, ext_chapter_id, page_index, attempt_no))
    """,
    """
    CREATE TABLE IF NOT EXISTS element_clearance(
      source_id TEXT NOT NULL,
      ext_work_id TEXT NOT NULL,
      ext_chapter_id TEXT NOT NULL,
      decision TEXT NOT NULL CHECK(decision IN ('CLEARED','WITHHELD','PENDING_REVIEW')),
      basis TEXT NOT NULL CHECK(basis IN ('operator-approval','policy-rule')),
      policy_rule TEXT,
      decided_by TEXT NOT NULL,
      evidence TEXT NOT NULL,
      policy_version TEXT NOT NULL,
      decided_at TEXT NOT NULL,
      PRIMARY KEY(source_id, ext_chapter_id),
      FOREIGN KEY(source_id, ext_work_id)
        REFERENCES works(source_id, ext_work_id) ON DELETE CASCADE,
      CHECK(basis <> 'policy-rule' OR (policy_rule IS NOT NULL AND length(trim(policy_rule)) > 0)))
    """,
)

_INDEXES = (
    "CREATE INDEX IF NOT EXISTS pg_by_srcid ON chapter_pages(source_id, ext_chapter_id, source_page_id)",
    "CREATE INDEX IF NOT EXISTS pg_by_stable ON chapter_pages(source_id, ext_chapter_id, stable_page_url)",
    "CREATE INDEX IF NOT EXISTS cpv_by_page ON chapter_page_versions(source_id, ext_chapter_id, page_index)",
    "CREATE INDEX IF NOT EXISTS wc_by_work ON work_chapters(source_id, ext_work_id, ordinal)",
)

#: (table, column, declaration). Applied only when the column is absent, so the
#: same list is safe on a brand new database - where CREATE TABLE already made
#: the column - and on an older one that predates it.
_MIGRATIONS = {
    2: (
        ("chapter_pages", "stable_page_url", "TEXT"),
        ("save_intents", "owner_instance", "TEXT"),
        ("save_intents", "fence_token", "INTEGER"),
    ),
}


def _columns(conn: sqlite3.Connection, table: str) -> set[str]:
    return {row[1] for row in conn.execute(f"PRAGMA table_info({table})")}


def _table_exists(conn: sqlite3.Connection, table: str) -> bool:
    return conn.execute(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (table,)
    ).fetchone() is not None


def connect(path) -> sqlite3.Connection:
    """Open the content database with the pragmas the schema depends on.

    ``foreign_keys`` is read back rather than assumed: the whole point of the
    foreign keys here is referential integrity, and a silently-off pragma would
    leave them decorative.
    """
    conn = sqlite3.connect(str(path), timeout=30, isolation_level=None)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA foreign_keys=ON")
    enabled = conn.execute("PRAGMA foreign_keys").fetchone()[0]
    if int(enabled) != 1:
        conn.close()
        raise RuntimeError("foreign key enforcement is required for the content schema")
    return conn


def _applied_versions(conn: sqlite3.Connection) -> set[int]:
    return {int(row[0]) for row in conn.execute("SELECT version FROM schema_version")}


def migrate(conn: sqlite3.Connection, *, applied_by: str = "content-store", now: str = "") -> int:
    """Create or upgrade the schema. Safe to run repeatedly.

    Returns the schema version in force afterwards. Re-running on an up-to-date
    database does nothing and does not fail, which is what makes it usable as a
    startup step rather than a one-shot installer.
    """
    stamp = now or "1970-01-01T00:00:00+00:00"
    for statement in _TABLES:
        conn.execute(statement)

    have = _applied_versions(conn)
    target = SCHEMA_VERSION
    if not have:
        conn.execute("BEGIN IMMEDIATE")
        try:
            conn.execute(
                "INSERT INTO schema_version(version,applied_at,applied_by,note) VALUES(?,?,?,?)",
                (1, stamp, applied_by, "initial content schema"),
            )
            have = {1}
            conn.execute("COMMIT")
        except Exception:
            conn.execute("ROLLBACK")
            raise

    for version in range(2, target + 1):
        if version in have:
            continue
        conn.execute("BEGIN IMMEDIATE")
        try:
            for table, column, declaration in _MIGRATIONS.get(version, ()):
                if not _table_exists(conn, table) or column in _columns(conn, table):
                    continue
                conn.execute(f"ALTER TABLE {table} ADD COLUMN {column} {declaration}")
            conn.execute(
                "INSERT INTO schema_version(version,applied_at,applied_by,note) VALUES(?,?,?,?)",
                (version, stamp, applied_by, f"migration to {version}"),
            )
            conn.execute("COMMIT")
        except Exception:
            conn.execute("ROLLBACK")
            raise

    # Indexes are created after the migrations, not before: an index over a
    # column that a migration is about to add cannot be built yet, and creating
    # it first is what made an upgraded database fail to open.
    for statement in _INDEXES:
        conn.execute(statement)

    # The lock row is permanent. Deleting it would reset the fencing token.
    conn.execute(
        "INSERT OR IGNORE INTO fetch_lock(lock_id,monotonic,owner_instance) VALUES(1,0,NULL)"
    )
    return target


def open_store(path, *, applied_by: str = "content-store", now: str = "") -> sqlite3.Connection:
    conn = connect(path)
    migrate(conn, applied_by=applied_by, now=now)
    return conn
