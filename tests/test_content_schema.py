"""Local tests for the content schema, its locking and the attempt lifecycle.

Everything here runs against a real SQLite database in a temporary directory.
Nothing fetches, nothing opens a socket, and no repository file is touched.
"""

from __future__ import annotations

import sqlite3
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from shura_core.content import (Recovered, AttemptNotStartable, FenceLost, LockUnavailable, abandon_attempt,
                                acquire_lock, assert_fence, begin_attempt, commit_done,
                                connect, grant_clearance, migrate, open_store, recover_interrupted,
                                release_lock, renew_lock)
from shura_core.content.schema import LOCK_TTL_SECONDS, REGISTERED_POLICY_RULES

SHA = "a" * 64
KEY = "b" * 64


class _Proxy:
    """Forwards everything to a connection except statements it was told to fail.

    ``sqlite3.Connection.execute`` and ``.commit`` are read-only, so an instance
    cannot be patched; the seam has to be a wrapper. It carries the real
    connection's behaviour everywhere except the one statement under test.
    """

    def __init__(self, conn, fail_when=None, fail=sqlite3.OperationalError):
        self._conn = conn
        self._fail_when = fail_when or (lambda sql: False)
        self._fail = fail

    def execute(self, sql, *args):
        if self._fail_when(sql):
            raise self._fail("simulated failure: " + sql.strip().splitlines()[0])
        return self._conn.execute(sql, *args)

    def __getattr__(self, name):
        return getattr(self._conn, name)


class ContentStoreCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.db = self.root / "content.db"
        self.conn = open_store(self.db)

    def tearDown(self):
        self.conn.close()
        self.tmp.cleanup()

    def seed(self, source_id="s1", ext_work_id="w1", ext_chapter_id="c1", page_index=0):
        self.conn.execute(
            "INSERT INTO source_policy(source_id,kind,allowed_hosts,fetch_enabled,quota_bytes,"
            "retention_days,synced_at) VALUES(?,?,?,1,?,30,'now')",
            (source_id, "index", '["example.org"]', 1 << 30))
        self.conn.execute(
            "INSERT INTO works(source_id,ext_work_id,source_work_id,title,content_classification,"
            "content_policy_version,discovered_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",
            (source_id, ext_work_id, "src-work", "A work", "SAFE", "v1", "now", "now"))
        self.conn.execute(
            "INSERT INTO work_chapters(source_id,ext_chapter_id,ext_work_id,source_chapter_id,"
            "chapter_number,ordinal,first_seen,updated_at) VALUES(?,?,?,?,?,?,?,?)",
            (source_id, ext_chapter_id, ext_work_id, "src-ch", 1.0, 0, "now", "now"))
        self.conn.execute(
            "INSERT INTO chapter_pages(source_id,ext_chapter_id,page_index,ordinal,source_page_url,"
            "created_at) VALUES(?,?,?,?,?,?)",
            (source_id, ext_chapter_id, page_index, 0, "https://example.org/tmp-token.jpg", "now"))


class PageIdentityConstraintTests(ContentStoreCase):
    """The identity claim and the identity data must agree, both directions."""

    def insert_page(self, **overrides):
        cols = {"source_id": "s1", "ext_chapter_id": "c1", "page_index": 0, "ordinal": 0,
                "created_at": "now", "state": "PENDING"}
        cols.update(overrides)
        names = ",".join(cols)
        marks = ",".join("?" for _ in cols)
        self.conn.execute(f"INSERT INTO chapter_pages({names}) VALUES({marks})", tuple(cols.values()))

    def test_source_page_id_kind_requires_a_non_empty_id(self):
        self.seed()
        with self.assertRaises(sqlite3.IntegrityError):
            self.insert_page(page_index=1, page_key_kind="source_page_id", source_page_id="")
        with self.assertRaises(sqlite3.IntegrityError):
            self.insert_page(page_index=2, page_key_kind="source_page_id", source_page_id="   ")

    def test_source_page_id_kind_with_an_id_is_accepted(self):
        self.seed()
        self.insert_page(page_index=1, page_key_kind="source_page_id", source_page_id="p-9")
        row = self.conn.execute(
            "SELECT page_key_kind FROM chapter_pages WHERE page_index=1").fetchone()
        self.assertEqual(row[0], "source_page_id")

    def test_stable_url_kind_requires_a_non_empty_url(self):
        self.seed()
        with self.assertRaises(sqlite3.IntegrityError):
            self.insert_page(page_index=1, page_key_kind="stable_url", stable_page_url=None)
        with self.assertRaises(sqlite3.IntegrityError):
            self.insert_page(page_index=2, page_key_kind="stable_url", stable_page_url="  ")

    def test_stable_url_kind_with_a_url_is_accepted(self):
        self.seed()
        self.insert_page(page_index=1, page_key_kind="stable_url",
                         stable_page_url="https://example.org/p/1.jpg")
        self.assertEqual(self.conn.execute(
            "SELECT page_key_kind FROM chapter_pages WHERE page_index=1").fetchone()[0], "stable_url")

    def test_none_kind_must_not_carry_a_stable_identity(self):
        self.seed()
        with self.assertRaises(sqlite3.IntegrityError):
            self.insert_page(page_index=1, page_key_kind="none", source_page_id="p-1")
        with self.assertRaises(sqlite3.IntegrityError):
            self.insert_page(page_index=2, page_key_kind="none",
                             stable_page_url="https://example.org/p/2.jpg")

    def test_none_kind_is_the_default_and_allows_no_identity(self):
        self.seed()
        self.insert_page(page_index=1)
        row = self.conn.execute(
            "SELECT page_key_kind FROM chapter_pages WHERE page_index=1").fetchone()
        self.assertEqual(row[0], "none")

    def test_a_temporary_download_url_is_never_an_identity(self):
        """source_page_url may hold an expiring link and must not feed page_key_kind."""
        self.seed()
        self.conn.execute("UPDATE chapter_pages SET source_page_url=? WHERE page_index=0",
                          ("https://example.org/other-signed-token.jpg",))
        row = self.conn.execute(
            "SELECT page_key_kind, stable_page_url FROM chapter_pages WHERE page_index=0").fetchone()
        self.assertEqual(row[0], "none")
        self.assertIsNone(row[1], "a download URL must never be promoted to an identity")


class DoneEvidenceConstraintTests(ContentStoreCase):
    def test_done_without_evidence_is_refused(self):
        self.seed()
        with self.assertRaises(sqlite3.IntegrityError):
            self.conn.execute("UPDATE chapter_pages SET state='DONE' WHERE page_index=0")

    def test_done_with_full_evidence_is_accepted(self):
        self.seed()
        self.conn.execute(
            "UPDATE chapter_pages SET state='DONE', rel_path='p/0.img', sha256=?, bytes=10, "
            "mime='image/jpeg', width=800, height=1200, verified_at='now' WHERE page_index=0",
            (SHA,))
        self.assertEqual(self.conn.execute(
            "SELECT state FROM chapter_pages WHERE page_index=0").fetchone()[0], "DONE")

    def test_invalid_sizes_and_digests_are_refused(self):
        """One fresh page per case: a half-written DONE row is itself illegal."""
        self.seed()
        base = ("UPDATE chapter_pages SET state='DONE', rel_path='p', sha256=?, bytes=?, "
                "mime='image/jpeg', width=800, height=1200, verified_at='now' WHERE page_index=?")
        for index, (digest, size) in enumerate([(SHA, 0), ("short", 10), (SHA.upper(), 10)]):
            with self.subTest(digest=digest, size=size):
                self.insert_page_at(index + 1)
                with self.assertRaises(sqlite3.IntegrityError):
                    self.conn.execute(base, (digest, size, index + 1))

    def insert_page_at(self, index):
        self.conn.execute(
            "INSERT INTO chapter_pages(source_id,ext_chapter_id,page_index,ordinal,created_at) "
            "VALUES('s1','c1',?,0,'now')", (index,))

    def test_oversized_image_is_refused(self):
        self.seed()
        with self.assertRaises(sqlite3.IntegrityError):
            self.conn.execute(
                "UPDATE chapter_pages SET state='DONE', rel_path='p', sha256=?, bytes=10, "
                "mime='image/png', width=40000, height=40000, verified_at='now' WHERE page_index=0",
                (SHA,))

    def test_a_partial_done_row_is_refused(self):
        """verified_at with a non-DONE state is the shape of a half-finished commit."""
        self.seed()
        with self.assertRaises(sqlite3.IntegrityError):
            self.conn.execute(
                "UPDATE chapter_pages SET verified_at='now' WHERE page_index=0")


class LockAndFencingTests(ContentStoreCase):
    def test_first_acquisition_issues_token_one(self):
        lock = acquire_lock(self.conn, "instance-a", "host-a")
        self.assertEqual(lock.token, 1)

    def test_a_live_lock_blocks_a_second_owner(self):
        acquire_lock(self.conn, "instance-a", "host-a")
        with self.assertRaises(LockUnavailable):
            acquire_lock(self.conn, "instance-b", "host-b")

    def test_an_expired_lease_can_be_taken_over_with_a_higher_token(self):
        acquire_lock(self.conn, "instance-a", "host-a", ttl_seconds=LOCK_TTL_SECONDS)
        self.conn.execute("UPDATE fetch_lock SET lease_expires_at='1999-01-01T00:00:00+00:00'")
        lock = acquire_lock(self.conn, "instance-b", "host-b")
        self.assertEqual(lock.token, 2)

    def test_the_compare_and_swap_rejects_a_lost_race(self):
        """Two processes reading the same expired lease cannot both win."""
        acquire_lock(self.conn, "instance-a", "host-a")
        self.conn.execute("UPDATE fetch_lock SET lease_expires_at='1999-01-01T00:00:00+00:00'")
        first = acquire_lock(self.conn, "instance-b", "host-b")
        self.assertEqual(first.token, 2)
        with self.assertRaises(LockUnavailable):
            acquire_lock(self.conn, "instance-c", "host-c")

    def test_release_keeps_the_row_so_the_token_never_resets(self):
        lock = acquire_lock(self.conn, "instance-a", "host-a")
        self.assertTrue(release_lock(self.conn, lock))
        self.assertEqual(self.conn.execute("SELECT COUNT(*) FROM fetch_lock").fetchone()[0], 1)
        self.assertIsNone(self.conn.execute(
            "SELECT owner_instance FROM fetch_lock WHERE lock_id=1").fetchone()[0])
        again = acquire_lock(self.conn, "instance-b", "host-b")
        self.assertEqual(again.token, 2, "a clean exit must not reset the fencing token")

    def test_release_only_affects_the_holder(self):
        lock = acquire_lock(self.conn, "instance-a", "host-a")
        self.assertFalse(release_lock(self.conn, type(lock)(lock.token, "impostor", "host-x", "x")))

    def test_renewal_by_a_displaced_owner_fails(self):
        old = acquire_lock(self.conn, "instance-a", "host-a")
        self.conn.execute("UPDATE fetch_lock SET lease_expires_at='1999-01-01T00:00:00+00:00'")
        new = acquire_lock(self.conn, "instance-b", "host-b")
        with self.assertRaises(FenceLost):
            renew_lock(self.conn, old)
        self.assertEqual(new.token, 2)

    def test_a_displaced_owner_cannot_assert_its_fence(self):
        old = acquire_lock(self.conn, "instance-a", "host-a")
        self.conn.execute("UPDATE fetch_lock SET lease_expires_at='1999-01-01T00:00:00+00:00'")
        acquire_lock(self.conn, "instance-b", "host-b")
        with self.assertRaises(FenceLost):
            assert_fence(self.conn, old)


class AttemptLifecycleTests(ContentStoreCase):
    def setUp(self):
        super().setUp()
        self.seed()
        self.lock = acquire_lock(self.conn, "instance-a", "host-a")

    def start(self, page_index=0):
        return begin_attempt(self.conn, self.lock, source_id="s1", ext_chapter_id="c1",
                             page_index=page_index, slug="s1", ext_work_id="w1",
                             expected_bytes=1234)

    def test_an_attempt_records_one_number_used_everywhere(self):
        attempt = self.start()
        self.assertEqual(attempt.attempt_no, 1)
        page = self.conn.execute(
            "SELECT state, attempts, active_attempt_no FROM chapter_pages WHERE page_index=0"
        ).fetchone()
        self.assertEqual((page["state"], page["attempts"], page["active_attempt_no"]),
                         ("IN_PROGRESS", 1, 1))
        intent = self.conn.execute(
            "SELECT * FROM save_intents WHERE page_index=0").fetchone()
        self.assertEqual(intent["attempt_no"], 1)
        self.assertEqual(intent["fence_token"], self.lock.token)
        self.assertEqual(intent["owner_instance"], "instance-a")
        self.assertIn("g0001", intent["rel_path"])
        self.assertIn("g0001", attempt.rel_path)
        self.assertEqual(intent["rel_path"], attempt.rel_path)

    def test_a_second_attempt_is_refused_while_one_is_unresolved(self):
        self.start()
        with self.assertRaises(AttemptNotStartable) as ctx:
            self.start()
        message = str(ctx.exception)
        self.assertTrue("not startable" in message or "unresolved save intent" in message, message)

    def test_the_attempt_counter_does_not_move_when_refused(self):
        self.start()
        with self.assertRaises(AttemptNotStartable):
            self.start()
        self.assertEqual(self.conn.execute(
            "SELECT attempts FROM chapter_pages WHERE page_index=0").fetchone()[0], 1)
        self.assertEqual(self.conn.execute(
            "SELECT COUNT(*) FROM save_intents").fetchone()[0], 1)

    def test_a_displaced_owner_cannot_start_an_attempt(self):
        self.conn.execute("UPDATE fetch_lock SET lease_expires_at='1999-01-01T00:00:00+00:00'")
        acquire_lock(self.conn, "instance-b", "host-b")
        with self.assertRaises(FenceLost):
            self.start()
        self.assertEqual(self.conn.execute(
            "SELECT state FROM chapter_pages WHERE page_index=0").fetchone()[0], "PENDING")
        self.assertEqual(self.conn.execute("SELECT COUNT(*) FROM save_intents").fetchone()[0], 0)

    def test_a_failed_transaction_leaves_nothing_behind(self):
        """If the commit cannot happen, neither the state nor the counter moved."""
        proxy = _Proxy(self.conn, fail_when=lambda sql: sql.lstrip().upper().startswith(
            "INSERT INTO SAVE_INTENTS"))
        with self.assertRaises(sqlite3.OperationalError):
            begin_attempt(proxy, self.lock, source_id="s1", ext_chapter_id="c1",
                          page_index=0, slug="s1", ext_work_id="w1")
        page = self.conn.execute(
            "SELECT state, attempts, active_attempt_no FROM chapter_pages WHERE page_index=0"
        ).fetchone()
        self.assertEqual((page["state"], page["attempts"], page["active_attempt_no"]),
                         ("PENDING", 0, None))
        self.assertEqual(self.conn.execute("SELECT COUNT(*) FROM save_intents").fetchone()[0], 0)

    def test_commit_done_clears_the_attempt_and_its_intent(self):
        attempt = self.start()
        commit_done(self.conn, self.lock, source_id="s1", ext_chapter_id="c1", page_index=0,
                    attempt_no=attempt.attempt_no, rel_path=attempt.rel_path, sha256=SHA,
                    size=1234, mime="image/jpeg", width=800, height=1200, content_key=KEY)
        page = self.conn.execute(
            "SELECT state, active_attempt_no FROM chapter_pages WHERE page_index=0").fetchone()
        self.assertEqual((page["state"], page["active_attempt_no"]), ("DONE", None))
        self.assertEqual(self.conn.execute("SELECT COUNT(*) FROM save_intents").fetchone()[0], 0)

    def test_a_stale_attempt_number_cannot_commit(self):
        first = self.start()
        abandon_attempt(self.conn, self.lock, source_id="s1", ext_chapter_id="c1",
                        page_index=0, attempt_no=first.attempt_no, reason="test")
        second = self.start()
        self.assertEqual(second.attempt_no, 2)
        with self.assertRaises(AttemptNotStartable):
            commit_done(self.conn, self.lock, source_id="s1", ext_chapter_id="c1", page_index=0,
                        attempt_no=first.attempt_no, rel_path="x", sha256=SHA, size=1,
                        mime="image/jpeg", width=1, height=1, content_key=KEY)

    def test_a_displaced_owner_cannot_commit_a_result(self):
        attempt = self.start()
        self.conn.execute("UPDATE fetch_lock SET lease_expires_at='1999-01-01T00:00:00+00:00'")
        acquire_lock(self.conn, "instance-b", "host-b")
        with self.assertRaises(FenceLost):
            commit_done(self.conn, self.lock, source_id="s1", ext_chapter_id="c1", page_index=0,
                        attempt_no=attempt.attempt_no, rel_path="x", sha256=SHA, size=1,
                        mime="image/jpeg", width=1, height=1, content_key=KEY)
        self.assertEqual(self.conn.execute(
            "SELECT state FROM chapter_pages WHERE page_index=0").fetchone()[0], "IN_PROGRESS")


class RecoveryTests(ContentStoreCase):
    def test_a_file_after_replace_is_recovered_without_a_fresh_download(self):
        self.seed()
        lock = acquire_lock(self.conn, "instance-a", "host-a")
        attempt = begin_attempt(self.conn, lock, source_id="s1", ext_chapter_id="c1",
                                page_index=0, slug="s1", ext_work_id="w1")
        final = self.root / attempt.rel_path
        final.parent.mkdir(parents=True, exist_ok=True)
        final.write_bytes(b"image-bytes")
        # the process died here: no commit_done, intent still present
        resolved = recover_interrupted(self.conn, root=str(self.root))
        self.assertEqual(len(resolved), 1)
        self.assertEqual(resolved[0].attempt_no, attempt.attempt_no)
        self.assertEqual(resolved[0].rel_path, attempt.rel_path)
        self.assertEqual(resolved[0].size, len(b"image-bytes"))

    def test_an_interrupted_transfer_is_discarded_not_adopted(self):
        self.seed()
        lock = acquire_lock(self.conn, "instance-a", "host-a")
        attempt = begin_attempt(self.conn, lock, source_id="s1", ext_chapter_id="c1",
                                page_index=0, slug="s1", ext_work_id="w1")
        part = self.root / attempt.part_path
        part.parent.mkdir(parents=True, exist_ok=True)
        part.write_bytes(b"partial")
        resolved = recover_interrupted(self.conn, root=str(self.root))
        self.assertEqual(resolved, [])
        self.assertFalse(part.exists())
        row = self.conn.execute(
            "SELECT state, active_attempt_no FROM chapter_pages WHERE page_index=0").fetchone()
        self.assertEqual((row[0], row[1]), ("PENDING", None))

    def test_a_file_with_no_intent_is_never_adopted(self):
        self.seed()
        stray = self.root / "pages/s1/w1/c1/g0001/p0000.img"
        stray.parent.mkdir(parents=True, exist_ok=True)
        stray.write_bytes(b"orphan")
        resolved = recover_interrupted(self.conn, root=str(self.root))
        self.assertEqual(resolved, [], "an unexplained file must not be adopted")
        self.assertEqual(self.conn.execute(
            "SELECT state FROM chapter_pages WHERE page_index=0").fetchone()[0], "PENDING")

    def test_a_superseded_generation_is_dropped_not_adopted(self):
        self.seed()
        lock = acquire_lock(self.conn, "instance-a", "host-a")
        first = begin_attempt(self.conn, lock, source_id="s1", ext_chapter_id="c1",
                              page_index=0, slug="s1", ext_work_id="w1")
        stale = self.root / first.rel_path
        stale.parent.mkdir(parents=True, exist_ok=True)
        stale.write_bytes(b"old-generation")
        # the page moved on: attempt 2 is the active one
        self.conn.execute("UPDATE chapter_pages SET attempts=2, active_attempt_no=2 WHERE page_index=0")
        resolved = recover_interrupted(self.conn, root=str(self.root))
        self.assertEqual(resolved, [], "a dead generation must not be adopted")
        self.assertFalse(stale.exists(), "the superseded generation is cleaned up")
        self.assertEqual(self.conn.execute("SELECT COUNT(*) FROM save_intents").fetchone()[0], 0)

    def test_a_new_attempt_after_a_crash_uses_a_different_path(self):
        self.seed()
        lock = acquire_lock(self.conn, "instance-a", "host-a")
        first = begin_attempt(self.conn, lock, source_id="s1", ext_chapter_id="c1",
                              page_index=0, slug="s1", ext_work_id="w1")
        old = self.root / first.rel_path
        old.parent.mkdir(parents=True, exist_ok=True)
        old.write_bytes(b"stale-bytes")
        # The crash left an unresolved intent; it must be settled before another
        # attempt may start, which is the guard, not an obstacle to route around.
        with self.assertRaises(AttemptNotStartable):
            begin_attempt(self.conn, lock, source_id="s1", ext_chapter_id="c1",
                          page_index=0, slug="s1", ext_work_id="w1")
        abandon_attempt(self.conn, lock, source_id="s1", ext_chapter_id="c1",
                        page_index=0, attempt_no=first.attempt_no, reason="crashed")
        second = begin_attempt(self.conn, lock, source_id="s1", ext_chapter_id="c1",
                               page_index=0, slug="s1", ext_work_id="w1")
        self.assertNotEqual(first.rel_path, second.rel_path)
        self.assertIn("g0002", second.rel_path)


V2_ONLY_COLUMNS = (
    ("chapter_pages", "stable_page_url TEXT,"),
    ("save_intents", "owner_instance TEXT NOT NULL,"),
    ("save_intents", "fence_token INTEGER NOT NULL CHECK(fence_token >= 1),"),
)


def _strip(ddl, needle):
    """Remove whole lines mentioning ``needle``.

    A v1 table had neither the column nor the CHECK that mentions it, so the
    clause has to go too; SQLite refuses to drop or parse a column a constraint
    still refers to.
    """
    return chr(10).join(line for line in ddl.splitlines() if needle not in line)


def build_v1_database(conn):
    """A faithful v1 database: the shipped schema minus what v2 added.

    Hand-writing a legacy table drifts from the real v1, and then the migration
    looks complete when it is not - which is exactly the bug this fixture caught.
    Deriving v1 from the shipped DDL keeps it true by construction.
    """
    from shura_core.content import schema as schema_mod
    for statement in schema_mod._TABLES:
        conn.execute(statement)
    for statement in schema_mod._INDEXES:
        conn.execute(statement)
    conn.execute("DROP INDEX IF EXISTS pg_by_stable")
    # Each v1 table lost these columns together with the CHECK lines that mention
    # them; removing only the column would leave a dangling constraint.
    plan = {
        "chapter_pages": ("stable_page_url", "page_key_kind = 'stable_url'"),
        "save_intents": ("owner_instance", "fence_token"),
    }
    for table, needles in plan.items():
        ddl = conn.execute("SELECT sql FROM sqlite_master WHERE type='table' AND name=?",
                           (table,)).fetchone()[0]
        conn.execute(f"DROP TABLE {table}")
        stripped = ddl
        for needle in needles:
            stripped = _strip(stripped, needle)
        conn.execute(stripped)
    conn.execute("DELETE FROM schema_version")
    conn.execute("INSERT INTO schema_version VALUES(1,'then','test','legacy v1')")


class SchemaAndMigrationTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = Path(self.tmp.name) / "content.db"

    def tearDown(self):
        self.tmp.cleanup()

    def test_a_new_database_gets_every_table_and_index(self):
        conn = open_store(self.path)
        try:
            names = {r[0] for r in conn.execute(
                "SELECT name FROM sqlite_master WHERE type IN ('table','index')")}
            for expected in ("source_policy", "fetch_lock", "works", "work_chapters",
                             "chapter_pages", "chapter_page_versions", "save_intents",
                             "pending_unlinks", "element_clearance", "schema_version",
                             "pg_by_srcid", "pg_by_stable", "cpv_by_page"):
                self.assertIn(expected, names, expected)
        finally:
            conn.close()

    def test_active_attempt_no_is_declared_in_create_table(self):
        conn = open_store(self.path)
        try:
            ddl = conn.execute(
                "SELECT sql FROM sqlite_master WHERE name='chapter_pages'").fetchone()[0]
            self.assertIn("active_attempt_no INTEGER", ddl)
            self.assertNotIn("ALTER", ddl)
        finally:
            conn.close()

    def test_save_intents_declares_owner_and_fence_in_create_table(self):
        conn = open_store(self.path)
        try:
            ddl = conn.execute("SELECT sql FROM sqlite_master WHERE name='save_intents'").fetchone()[0]
            self.assertIn("owner_instance TEXT NOT NULL", ddl)
            self.assertIn("fence_token INTEGER NOT NULL", ddl)
        finally:
            conn.close()

    def test_the_lock_row_exists_after_migration(self):
        conn = open_store(self.path)
        try:
            row = conn.execute("SELECT lock_id, monotonic, owner_instance FROM fetch_lock").fetchone()
            self.assertEqual((row[0], row[1], row[2]), (1, 0, None))
        finally:
            conn.close()

    def test_migration_is_safe_to_run_again(self):
        conn = open_store(self.path)
        try:
            before = conn.execute("SELECT COUNT(*) FROM schema_version").fetchone()[0]
            migrate(conn)
            migrate(conn)
            after = conn.execute("SELECT COUNT(*) FROM schema_version").fetchone()[0]
            self.assertEqual(before, after, "re-running init must not add versions")
            self.assertEqual(conn.execute("PRAGMA foreign_keys").fetchone()[0], 1)
        finally:
            conn.close()

    def test_an_older_database_is_walked_forward_atomically(self):
        conn = connect(self.path)
        build_v1_database(conn)
        conn.close()
        conn = open_store(self.path)
        try:
            for table, column in (("chapter_pages", "stable_page_url"),
                                  ("save_intents", "owner_instance"),
                                  ("save_intents", "fence_token")):
                self.assertIn(column, {r[1] for r in conn.execute(f"PRAGMA table_info({table})")},
                              f"{table}.{column} was not migrated")
            self.assertEqual(sorted(r[0] for r in conn.execute("SELECT version FROM schema_version")),
                             [1, 2])
        finally:
            conn.close()

    def test_a_migration_that_fails_records_no_version_and_rolls_back(self):
        conn = connect(self.path)
        build_v1_database(conn)
        conn.close()
        from shura_core.content import schema as schema_mod
        # A column name that cannot be interpolated into valid SQL: the first
        # step of the migration really does run, so the rollback is exercised.
        broken = {2: (("chapter_pages", "stable_page_url", "TEXT"),
                      ("chapter_pages", "bad column name!", "TEXT"))}
        with mock.patch.object(schema_mod, "_MIGRATIONS", broken):
            with self.assertRaises(sqlite3.OperationalError):
                schema_mod.migrate(connect(self.path))
        conn = connect(self.path)
        try:
            self.assertEqual([r[0] for r in conn.execute("SELECT version FROM schema_version")], [1],
                             "a failed migration must not record its version")
            self.assertNotIn("stable_page_url",
                             {r[1] for r in conn.execute("PRAGMA table_info(chapter_pages)")},
                             "its partial work was rolled back")
        finally:
            conn.close()

    def test_foreign_key_enforcement_is_verified_not_assumed(self):
        conn = open_store(self.path)
        try:
            self.assertEqual(conn.execute("PRAGMA foreign_keys").fetchone()[0], 1)
            with self.assertRaises(sqlite3.IntegrityError):
                conn.execute("INSERT INTO works(source_id,ext_work_id,discovered_at,updated_at) "
                             "VALUES('unknown','w','now','now')")
        finally:
            conn.close()

    def test_connect_refuses_when_foreign_keys_cannot_be_enabled(self):
        """A silent no-op pragma would leave every foreign key decorative."""
        from shura_core.content import schema as schema_mod
        raw = sqlite3.connect(str(self.path), isolation_level=None)

        class _ForeignKeysStayOff(_Proxy):
            def execute(self, sql, *args):
                if "foreign_keys=ON" in sql:
                    return raw.execute("SELECT 1")
                return raw.execute(sql, *args)

        try:
            with mock.patch.object(sqlite3, "connect", return_value=_ForeignKeysStayOff(raw)):
                with self.assertRaises(RuntimeError):
                    schema_mod.connect(self.path)
        finally:
            raw.close()


class ClearancePolicyTests(ContentStoreCase):
    def setUp(self):
        super().setUp()
        self.seed()

    def test_operator_approval_can_clear_a_chapter(self):
        grant_clearance(self.conn, source_id="s1", ext_work_id="w1", ext_chapter_id="c1",
                        decision="CLEARED", basis="operator-approval", decided_by="operator",
                        evidence="manual review", policy_version="v1")
        row = self.conn.execute(
            "SELECT decision, basis FROM element_clearance").fetchone()
        self.assertEqual((row[0], row[1]), ("CLEARED", "operator-approval"))

    def test_policy_rule_is_refused_while_no_rule_is_implemented(self):
        self.assertEqual(REGISTERED_POLICY_RULES, frozenset(),
                         "a rule id may only appear here once it is implemented and tested")
        with self.assertRaises(ValueError):
            grant_clearance(self.conn, source_id="s1", ext_work_id="w1", ext_chapter_id="c1",
                            decision="CLEARED", basis="policy-rule", policy_rule="image-classifier",
                            decided_by="automation", evidence="n/a", policy_version="v1")
        self.assertEqual(self.conn.execute(
            "SELECT COUNT(*) FROM element_clearance").fetchone()[0], 0)

    def test_a_missing_clearance_row_leaves_the_chapter_unavailable(self):
        row = self.conn.execute(
            "SELECT COUNT(*) FROM element_clearance WHERE ext_chapter_id='c1'").fetchone()[0]
        self.assertEqual(row, 0, "absence is the safe default, not implicit approval")


if __name__ == "__main__":
    unittest.main()
