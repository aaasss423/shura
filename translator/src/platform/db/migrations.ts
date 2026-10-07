/**
 * Schema migrations.
 *
 * Numbered, ordered, and dialect-aware. They hold the system of record:
 * knowledge, translation memory, glossary, characters, research, entitlements,
 * API keys, usage, jobs, audit. Migrations are append-only; nothing here is ever
 * edited after release.
 *
 * Dialect notes are inline where a statement cannot be shared verbatim.
 */

import type { Dialect } from './database';

export interface Migration {
  version: number;
  name: string;
  up: string;
}

/** `now()` text timestamps work on both dialects. */
const NOW = "TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))";

export const MIGRATIONS: Migration[] = [
  {
    version: 1,
    name: 'core_reference',
    up: `
      -- ------------------------------------------------------------------
      -- Knowledge base. Language namespaced, provenance-bearing, versioned.
      -- ------------------------------------------------------------------
      CREATE TABLE IF NOT EXISTS knowledge (
        id                 INTEGER PRIMARY KEY AUTOINCREMENT,
        source_language    TEXT NOT NULL,
        target_language    TEXT NOT NULL,
        category           TEXT NOT NULL,
        term               TEXT NOT NULL,
        normalized_term    TEXT NOT NULL,
        meaning            TEXT,
        translation        TEXT NOT NULL,
        context            TEXT,
        -- Narrow scoping so a term can mean different things per series.
        series_id          TEXT,
        character_id       TEXT,
        genre              TEXT,
        confidence         REAL NOT NULL DEFAULT 0,
        source             TEXT,
        source_type        TEXT NOT NULL DEFAULT 'manual',
        verification_state TEXT NOT NULL DEFAULT 'unverified',
        -- Bumped on every edit so the cache key can invalidate.
        version            INTEGER NOT NULL DEFAULT 1,
        usage_count        INTEGER NOT NULL DEFAULT 0,
        last_used_at       TEXT,
        created_at         ${NOW},
        updated_at         ${NOW}
      );

      -- One authoritative entry per (pair, category, term, scope). Contextual
      -- variants differ by their scope columns and are excluded here.
      CREATE UNIQUE INDEX IF NOT EXISTS ux_knowledge_global
        ON knowledge (source_language, target_language, category, normalized_term)
        WHERE series_id IS NULL AND character_id IS NULL;
      CREATE UNIQUE INDEX IF NOT EXISTS ux_knowledge_series
        ON knowledge (source_language, target_language, category, normalized_term, series_id)
        WHERE series_id IS NOT NULL AND character_id IS NULL;
      CREATE UNIQUE INDEX IF NOT EXISTS ux_knowledge_character
        ON knowledge (source_language, target_language, category, normalized_term, character_id)
        WHERE character_id IS NOT NULL;
      CREATE INDEX IF NOT EXISTS ix_knowledge_lookup
        ON knowledge (source_language, target_language, normalized_term);
      CREATE INDEX IF NOT EXISTS ix_knowledge_rank
        ON knowledge (target_language, confidence DESC, usage_count DESC);

      -- ------------------------------------------------------------------
      -- Translation memory: curated, high-confidence previous translations.
      -- ------------------------------------------------------------------
      CREATE TABLE IF NOT EXISTS translation_memory (
        id              INTEGER PRIMARY KEY AUTOINCREMENT,
        source_language TEXT NOT NULL,
        target_language TEXT NOT NULL,
        source_text     TEXT NOT NULL,
        normalized_text TEXT NOT NULL,
        target_text     TEXT NOT NULL,
        context         TEXT,
        series_id       TEXT,
        character_id    TEXT,
        glossary_version INTEGER NOT NULL DEFAULT 0,
        engine          TEXT,
        model_id        TEXT,
        model_version   TEXT,
        confidence      REAL NOT NULL DEFAULT 0,
        provenance      TEXT NOT NULL DEFAULT 'approved',
        usage_count     INTEGER NOT NULL DEFAULT 0,
        created_at      ${NOW},
        updated_at      ${NOW}
      );
      CREATE UNIQUE INDEX IF NOT EXISTS ux_tm
        ON translation_memory (source_language, target_language, normalized_text);
      CREATE INDEX IF NOT EXISTS ix_tm_rank
        ON translation_memory (source_language, target_language, confidence DESC, usage_count DESC);

      -- ------------------------------------------------------------------
      -- Glossary: enforced terminology.
      -- ------------------------------------------------------------------
      CREATE TABLE IF NOT EXISTS glossary (
        id                INTEGER PRIMARY KEY AUTOINCREMENT,
        source_language   TEXT NOT NULL,
        target_language   TEXT NOT NULL,
        term              TEXT NOT NULL,
        normalized_term   TEXT NOT NULL,
        default_translation TEXT,
        forbidden         TEXT,
        -- Known wrong renderings the model tends to produce; used to repair
        -- output after the fact. Prevention is the prompt instruction.
        variants          TEXT,
        -- 'force' rewrites the term in the output; 'prefer' only nudges.
        mode              TEXT NOT NULL DEFAULT 'prefer',
        category          TEXT NOT NULL DEFAULT 'term',
        aliases           TEXT,
        context           TEXT,
        series_id         TEXT,
        genre             TEXT,
        priority          INTEGER NOT NULL DEFAULT 0,
        confidence        REAL NOT NULL DEFAULT 0,
        version           INTEGER NOT NULL DEFAULT 1,
        enabled           INTEGER NOT NULL DEFAULT 1,
        created_at        ${NOW},
        updated_at        ${NOW}
      );
      CREATE UNIQUE INDEX IF NOT EXISTS ux_glossary
        ON glossary (source_language, target_language, normalized_term);
      CREATE INDEX IF NOT EXISTS ix_glossary_scope
        ON glossary (source_language, target_language, enabled, priority DESC);

      -- ------------------------------------------------------------------
      -- Character / series names. Never translated as ordinary words.
      -- ------------------------------------------------------------------
      CREATE TABLE IF NOT EXISTS series (
        id         INTEGER PRIMARY KEY AUTOINCREMENT,
        slug       TEXT NOT NULL UNIQUE,
        title      TEXT,
        source_language TEXT,
        created_at ${NOW},
        updated_at ${NOW}
      );

      CREATE TABLE IF NOT EXISTS characters (
        id             INTEGER PRIMARY KEY AUTOINCREMENT,
        series_id      INTEGER REFERENCES series(id) ON DELETE CASCADE,
        slug           TEXT NOT NULL UNIQUE,
        display_name   TEXT NOT NULL,
        aliases        TEXT,
        romanization   TEXT,
        official_name  TEXT,
        preferred_arabic_name TEXT,
        gender         TEXT,
        honorific      TEXT,
        notes          TEXT,
        created_at     ${NOW},
        updated_at     ${NOW}
      );
      CREATE INDEX IF NOT EXISTS ix_characters_series ON characters (series_id);

      -- ------------------------------------------------------------------
      -- Research: queue, sources, and the knowledge they produced.
      -- ------------------------------------------------------------------
      CREATE TABLE IF NOT EXISTS research_jobs (
        id              INTEGER PRIMARY KEY AUTOINCREMENT,
        normalized_key  TEXT NOT NULL,
        source_language TEXT NOT NULL,
        target_language TEXT NOT NULL,
        term            TEXT NOT NULL,
        category_hint   TEXT,
        status          TEXT NOT NULL DEFAULT 'queued',
        priority        INTEGER NOT NULL DEFAULT 5,
        attempts        INTEGER NOT NULL DEFAULT 0,
        max_attempts    INTEGER NOT NULL DEFAULT 3,
        waiters         INTEGER NOT NULL DEFAULT 0,
        context         TEXT,
        series_id       TEXT,
        critical        INTEGER NOT NULL DEFAULT 0,
        error           TEXT,
        scheduled_at    TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
        created_at      ${NOW},
        updated_at      ${NOW},
        completed_at    TEXT,
        -- Postgres uses a partial unique index; this constraint is the
        -- equivalent guarantee and holds on both dialects.
        CONSTRAINT ux_research_live UNIQUE (normalized_key)
      );
      CREATE INDEX IF NOT EXISTS ix_research_claim ON research_jobs (status, priority DESC, scheduled_at);

      CREATE TABLE IF NOT EXISTS research_sources (
        id            INTEGER PRIMARY KEY AUTOINCREMENT,
        job_id        INTEGER NOT NULL REFERENCES research_jobs(id) ON DELETE CASCADE,
        url           TEXT,
        title         TEXT,
        kind          TEXT NOT NULL DEFAULT 'unknown',
        credibility   REAL NOT NULL DEFAULT 0,
        snippet       TEXT,
        retrieved_at  ${NOW},
        -- Dedup: the same source never counted twice for one job.
        fingerprint   TEXT NOT NULL,
        UNIQUE (job_id, fingerprint)
      );
      CREATE INDEX IF NOT EXISTS ix_research_sources_job ON research_sources (job_id, credibility DESC);

      -- ------------------------------------------------------------------
      -- Platform: plans, entitlements, users, API keys, usage, audit.
      -- ------------------------------------------------------------------
      CREATE TABLE IF NOT EXISTS plans (
        id               TEXT PRIMARY KEY,
        name             TEXT NOT NULL,
        daily_requests   INTEGER,
        monthly_requests INTEGER,
        max_parallel     INTEGER NOT NULL DEFAULT 2,
        max_chars_per_request INTEGER NOT NULL DEFAULT 5000,
        priority         INTEGER NOT NULL DEFAULT 5,
        allows_byok      INTEGER NOT NULL DEFAULT 0,
        unlimited_product INTEGER NOT NULL DEFAULT 0,
        notes            TEXT,
        created_at       ${NOW},
        updated_at       ${NOW}
      );

      CREATE TABLE IF NOT EXISTS users (
        id            TEXT PRIMARY KEY,
        email         TEXT UNIQUE,
        display_name  TEXT,
        plan_id       TEXT NOT NULL REFERENCES plans(id),
        kind          TEXT NOT NULL DEFAULT 'FREE',
        status        TEXT NOT NULL DEFAULT 'active',
        disabled_at   TEXT,
        disabled_reason TEXT,
        daily_override INTEGER,
        monthly_override INTEGER,
        max_parallel_override INTEGER,
        created_at    ${NOW},
        updated_at    ${NOW}
      );

      CREATE TABLE IF NOT EXISTS entitlements (
        id           INTEGER PRIMARY KEY AUTOINCREMENT,
        user_id      TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        capability   TEXT NOT NULL,
        allowed      INTEGER NOT NULL DEFAULT 0,
        limit_value  INTEGER,
        expires_at   TEXT,
        UNIQUE (user_id, capability)
      );

      -- API keys: hash and prefix only. Never a recoverable value.
      CREATE TABLE IF NOT EXISTS api_keys (
        id           INTEGER PRIMARY KEY AUTOINCREMENT,
        user_id      TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        name         TEXT NOT NULL,
        prefix       TEXT NOT NULL UNIQUE,
        key_hash     TEXT NOT NULL UNIQUE,
        scopes       TEXT NOT NULL DEFAULT 'translate',
        status       TEXT NOT NULL DEFAULT 'active',
        daily_limit  INTEGER,
        last_used_at TEXT,
        usage_count  INTEGER NOT NULL DEFAULT 0,
        expires_at   TEXT,
        rotated_from INTEGER REFERENCES api_keys(id) ON DELETE SET NULL,
        created_at   ${NOW},
        revoked_at   TEXT
      );
      CREATE INDEX IF NOT EXISTS ix_api_keys_user ON api_keys (user_id, status);

      CREATE TABLE IF NOT EXISTS usage (
        id             INTEGER PRIMARY KEY AUTOINCREMENT,
        user_id        TEXT NOT NULL,
        api_key_id     INTEGER,
        day            TEXT NOT NULL,
        requests       INTEGER NOT NULL DEFAULT 0,
        characters     INTEGER NOT NULL DEFAULT 0,
        errors         INTEGER NOT NULL DEFAULT 0,
        cache_hits     INTEGER NOT NULL DEFAULT 0,
        updated_at     ${NOW},
        UNIQUE (user_id, day)
      );
      CREATE INDEX IF NOT EXISTS ix_usage_day ON usage (day);

      -- ------------------------------------------------------------------
      -- Translation history: candidates for the learning pipeline (ADR 0007).
      -- ------------------------------------------------------------------
      CREATE TABLE IF NOT EXISTS translations (
        id              INTEGER PRIMARY KEY AUTOINCREMENT,
        user_id         TEXT,
        source_language TEXT NOT NULL,
        target_language TEXT NOT NULL,
        source_text     TEXT NOT NULL,
        target_text     TEXT NOT NULL,
        engine          TEXT,
        model_id        TEXT,
        model_version   TEXT,
        glossary_version INTEGER NOT NULL DEFAULT 0,
        quality_score   REAL,
        confidence      REAL,
        approval_state  TEXT NOT NULL DEFAULT 'candidate',
        from_cache      INTEGER NOT NULL DEFAULT 0,
        series_id       TEXT,
        character_id    TEXT,
        context_json    TEXT,
        created_at      ${NOW}
      );
      CREATE INDEX IF NOT EXISTS ix_translations_approval
        ON translations (approval_state, created_at);
      CREATE INDEX IF NOT EXISTS ix_translations_pair
        ON translations (source_language, target_language);

      -- ------------------------------------------------------------------
      -- Async jobs.
      -- ------------------------------------------------------------------
      CREATE TABLE IF NOT EXISTS jobs (
        id             TEXT PRIMARY KEY,
        user_id        TEXT,
        status         TEXT NOT NULL DEFAULT 'queued',
        kind           TEXT NOT NULL,
        priority       INTEGER NOT NULL DEFAULT 5,
        payload        TEXT NOT NULL,
        result         TEXT,
        error          TEXT,
        progress       TEXT,
        attempts       INTEGER NOT NULL DEFAULT 0,
        max_attempts   INTEGER NOT NULL DEFAULT 3,
        dedup_key      TEXT,
        cancel_requested INTEGER NOT NULL DEFAULT 0,
        scheduled_at   TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
        created_at     ${NOW},
        updated_at     ${NOW},
        started_at     TEXT,
        completed_at   TEXT
      );
      CREATE UNIQUE INDEX IF NOT EXISTS ux_jobs_dedup
        ON jobs (dedup_key) WHERE dedup_key IS NOT NULL;
      CREATE INDEX IF NOT EXISTS ix_jobs_claim ON jobs (status, priority DESC, scheduled_at);
      CREATE INDEX IF NOT EXISTS ix_jobs_user ON jobs (user_id, created_at DESC);

      -- ------------------------------------------------------------------
      -- Model / engine registry (ops view of what is loaded).
      -- ------------------------------------------------------------------
      CREATE TABLE IF NOT EXISTS engines (
        id            TEXT PRIMARY KEY,
        kind          TEXT NOT NULL DEFAULT 'local',
        model_id      TEXT,
        model_version TEXT,
        endpoint      TEXT,
        enabled       INTEGER NOT NULL DEFAULT 1,
        notes         TEXT,
        updated_at    ${NOW}
      );

      CREATE TABLE IF NOT EXISTS model_routes (
        id          INTEGER PRIMARY KEY AUTOINCREMENT,
        tier        TEXT NOT NULL,
        source_language TEXT NOT NULL DEFAULT '*',
        target_language TEXT NOT NULL DEFAULT '*',
        engine_id   TEXT NOT NULL,
        priority    INTEGER NOT NULL DEFAULT 0,
        weight      INTEGER NOT NULL DEFAULT 100,
        enabled     INTEGER NOT NULL DEFAULT 1,
        UNIQUE (tier, source_language, target_language, engine_id)
      );

      -- ------------------------------------------------------------------
      -- Prewarming corpus.
      -- ------------------------------------------------------------------
      CREATE TABLE IF NOT EXISTS prewarm_items (
        id               INTEGER PRIMARY KEY AUTOINCREMENT,
        batch_id         TEXT NOT NULL,
        source_language  TEXT NOT NULL,
        target_language  TEXT NOT NULL,
        category         TEXT NOT NULL,
        term             TEXT NOT NULL,
        normalized_term  TEXT NOT NULL,
        status           TEXT NOT NULL DEFAULT 'pending',
        research_job_id  INTEGER REFERENCES research_jobs(id) ON DELETE SET NULL,
        result           TEXT,
        created_at       ${NOW},
        updated_at       ${NOW},
        UNIQUE (batch_id, source_language, category, normalized_term)
      );
      CREATE INDEX IF NOT EXISTS ix_prewarm_batch ON prewarm_items (batch_id, status);

      CREATE TABLE IF NOT EXISTS audit_logs (
        id          INTEGER PRIMARY KEY AUTOINCREMENT,
        actor       TEXT NOT NULL,
        action      TEXT NOT NULL,
        subject_type TEXT,
        subject_id  TEXT,
        detail      TEXT,
        ip          TEXT,
        created_at  ${NOW}
      );
      CREATE INDEX IF NOT EXISTS ix_audit_created ON audit_logs (created_at DESC);
      CREATE INDEX IF NOT EXISTS ix_audit_subject ON audit_logs (subject_type, subject_id);

      CREATE TABLE IF NOT EXISTS metrics_counters (
        name        TEXT NOT NULL,
        labels      TEXT NOT NULL DEFAULT '',
        value       REAL NOT NULL DEFAULT 0,
        updated_at  ${NOW},
        PRIMARY KEY (name, labels)
      );
    `,
  },
];

export function loadMigrations(): Migration[] {
  return [...MIGRATIONS].sort((a, b) => a.version - b.version);
}

/** Dialect-specific extras applied after migrations (e.g. Postgres trigram). */
export const DIALECT_EXTRAS: Record<Dialect, string[]> = {
  sqlite: [],
  postgres: [
    // Fuzzy manga-phrase matching. Requires the extension; failure is tolerable
    // because the pipeline degrades to exact lookup.
    `CREATE EXTENSION IF NOT EXISTS pg_trgm;`,
    `CREATE INDEX IF NOT EXISTS ix_knowledge_trgm ON knowledge USING gin (normalized_term gin_trgm_ops);`,
    `CREATE INDEX IF NOT EXISTS ix_tm_trgm ON translation_memory USING gin (normalized_text gin_trgm_ops);`,
  ],
};