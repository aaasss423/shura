/**
 * Database access.
 *
 * One contract, two dialects (ADR 0003):
 *   - Postgres in production (multi-writer, SKIP LOCKED, pg_trgm)
 *   - SQLite via node:sqlite for development, tests and single-node edge
 *
 * Repositories are the only place SQL is written; services never see SQL, which
 * is what makes the dialect swappable. SQLite keeps the platform at zero runtime
 * dependencies because node:sqlite ships with Node.
 */

import { DatabaseSync } from 'node:sqlite';
import type { SQLInputValue } from 'node:sqlite';
import { loadMigrations, type Migration } from './migrations';

export type Dialect = 'sqlite' | 'postgres';

export interface QueryResult<T> {
  rows: T[];
  changes: number;
}

/**
 * Minimal database contract. A Postgres adapter implements the same shape with
 * `pg`; nothing above this interface knows the difference.
 */
export interface Database {
  readonly dialect: Dialect;
  exec(sql: string): void;
  all<T = Record<string, unknown>>(sql: string, params?: unknown[]): T[];
  get<T = Record<string, unknown>>(sql: string, params?: unknown[]): T | undefined;
  run(sql: string, params?: unknown[]): { changes: number };
  /** Runs `fn` inside a transaction. Rolls back on throw. */
  transaction<T>(fn: () => T): T;
  close(): void;
}

export interface SqliteDatabaseOptions {
  /** File path, or ':memory:'. */
  filename: string;
  /** Apply migrations at open. Default true. */
  migrate?: boolean;
}

function toSqlParams(params: unknown[] = []): SQLInputValue[] {
  return params.map((value) => {
    if (value === undefined || value === null) {
      return null;
    }
    if (typeof value === 'boolean') {
      return value ? 1 : 0;
    }
    if (typeof value === 'number' || typeof value === 'string' || typeof value === 'bigint') {
      return value;
    }
    return JSON.stringify(value);
  });
}

class SqliteDatabase implements Database {
  readonly dialect = 'sqlite' as const;
  private readonly db: DatabaseSync;

  constructor(filename: string) {
    this.db = new DatabaseSync(filename);
    // WAL keeps readers from blocking the writer; foreign keys are off by
    // default in SQLite and must be enabled per connection.
    if (filename !== ':memory:') {
      try {
        this.db.exec('PRAGMA journal_mode = WAL;');
      } catch {
        // Read-only or unsupported filesystem: the default journal is fine.
      }
    }
    this.db.exec('PRAGMA foreign_keys = ON;');
    this.db.exec('PRAGMA busy_timeout = 5000;');
  }

  exec(sql: string): void {
    this.db.exec(sql);
  }

  all<T = Record<string, unknown>>(sql: string, params: unknown[] = []): T[] {
    const statement = this.db.prepare(sql);
    return statement.all(...toSqlParams(params)) as T[];
  }

  get<T = Record<string, unknown>>(sql: string, params: unknown[] = []): T | undefined {
    const statement = this.db.prepare(sql);
    return statement.get(...toSqlParams(params)) as T | undefined;
  }

  run(sql: string, params: unknown[] = []): { changes: number } {
    const statement = this.db.prepare(sql);
    const result = statement.run(...toSqlParams(params));
    return { changes: Number(result.changes ?? 0) };
  }

  transaction<T>(fn: () => T): T {
    // Nested calls join the outer transaction rather than failing.
    if (this.db.isTransaction) {
      return fn();
    }
    this.db.exec('BEGIN IMMEDIATE');
    try {
      const result = fn();
      this.db.exec('COMMIT');
      return result;
    } catch (error) {
      try {
        this.db.exec('ROLLBACK');
      } catch {
        // Rollback failure must not mask the original error.
      }
      throw error;
    }
  }

  close(): void {
    this.db.close();
  }
}

export interface OpenDatabaseOptions extends SqliteDatabaseOptions {
  dialect?: Dialect;
}

/** Opens the database and applies migrations. */
export function openDatabase(options: OpenDatabaseOptions): Database {
  if (options.dialect === 'postgres') {
    throw new Error(
      'Postgres requires the pg driver. The Database interface is the seam (ADR 0003); ' +
        'deployments that need Postgres must provide an adapter implementing it.',
    );
  }
  const db = new SqliteDatabase(options.filename);
  if (options.migrate !== false) {
    applyMigrations(db);
  }
  return db;
}

export function openMemoryDatabase(): Database {
  return openDatabase({ filename: ':memory:' });
}

/** Applies pending migrations, recording them so they run once. */
export function applyMigrations(db: Database, migrations: Migration[] = loadMigrations()): number {
  db.exec(`
    CREATE TABLE IF NOT EXISTS schema_migrations (
      version     INTEGER PRIMARY KEY,
      name        TEXT NOT NULL,
      applied_at  TEXT NOT NULL
    );
  `);

  const applied = new Set(
    db.all<{ version: number }>('SELECT version FROM schema_migrations').map((r) => r.version),
  );

  let count = 0;
  for (const migration of migrations) {
    if (applied.has(migration.version)) {
      continue;
    }
    db.transaction(() => {
      db.exec(migration.up);
      db.run('INSERT INTO schema_migrations (version, name, applied_at) VALUES (?, ?, ?)', [
        migration.version,
        migration.name,
        new Date().toISOString(),
      ]);
    });
    count += 1;
  }
  return count;
}