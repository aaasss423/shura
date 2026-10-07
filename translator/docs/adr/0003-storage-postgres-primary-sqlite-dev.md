# ADR 0003 — Postgres primary, SQLite for dev and edge

Status: Accepted · 2026-10

## Context

The knowledge base, glossary, translation memory, research queue, entitlements,
usage and audit logs all need transactions, concurrent writes and search. The
existing cache used a JSON file, which is fine for a cache and wrong for a
system of record.

Options considered: PostgreSQL, SQLite, MySQL, MongoDB, Elasticsearch,
ClickHouse.

| Option | Scale | Search | Transactions | Knowledge queries | Concurrent users | Maintenance | Self-hosting |
| --- | --- | --- | --- | --- | --- | --- | --- |
| PostgreSQL | excellent | good (FTS, trigram, pgvector) | excellent | excellent | excellent | moderate | trivial |
| SQLite | low–moderate | weak | excellent (single writer) | fine | poor at concurrency | near zero | trivial |
| MySQL | excellent | weak without extras | excellent | fine | excellent | moderate | trivial |
| MongoDB | excellent | decent | adequate | awkward (no joins) | excellent | moderate | moderate |
| Elasticsearch | excellent | best | **poor** (near-real-time) | keyword only | excellent | heavy | heavy |
| ClickHouse | analytics only | weak | batch only | no | excellent | heavy | heavy |

Knowledge retrieval needs both exact lookup (`normalized_term` → entry) and
ranking by `confidence`, `usage_count`, `verification_state`. Postgres gives
both, plus transactions for the research pipeline (claim → sources → entry) and
`pg_trgm` for fuzzy manga-phrase matching.

## Decision

**PostgreSQL is the system of record.** Access goes through repository interfaces
so the driver is swappable. **SQLite** backs the same schema for development,
tests and single-node edge deployments, using `node:sqlite` — built into Node, so
the platform keeps **zero runtime dependencies**.

Why SQLite for dev and not Postgres-in-Docker: the test suite must run anywhere
with `npm test` and no services. Both dialects run the same migrations.

Elasticsearch is rejected as a primary store: near-real-time indexing means a
just-learned term is invisible to the next request, which defeats the purpose of
a translation memory. It stays a legitimate *derived* index later.

## Consequences

- All schema lives in numbered migrations, dialect-aware, run at boot.
- Repositories are the only place SQL is written; services never see SQL.
- `SKIP LOCKED` gives us queue semantics for free on Postgres; SQLite serialises
  writers instead, which is fine at dev scale.
- No ORM. Hand-written SQL keeps the dependency count at zero and the behaviour
  explicit.

## Reversal

The repository interface is the seam. Moving SQLite → Postgres is a driver and
migration change, not a service change.
