# ADR index

Architecture decision records for the platform phase. Each is short, states the
decision, and records what it costs and what would reverse it.

| # | Title | Status |
| --- | --- | --- |
| [0001](adr/0001-local-engine-is-core.md) | The local engine is the core; providers are optional | Accepted |
| [0002](adr/0002-model-selection.md) | TranslateGemma family, tiered, with MADLAD fallback | Accepted |
| [0003](adr/0003-storage-postgres-primary-sqlite-dev.md) | Postgres primary, SQLite for dev and edge | Accepted |
| [0004](adr/0004-research-runs-in-background.md) | Research is asynchronous and never blocks a response | Accepted |
| [0005](adr/0005-deduplicate-research-queue.md) | One research job per normalized key | Accepted |
| [0006](adr/0006-own-eval-set.md) | Build our own manga eval set before trusting any model claim | Accepted |
| [0007](adr/0007-learning-is-curated.md) | No self-training; only approved data enters the dataset | Accepted |
| [0008](adr/0008-byok-shares-the-secret-vault.md) | BYOK keys reuse the existing encrypted vault | Accepted |
| [0009](adr/0009-model-version-in-cache-key.md) | Model id and version participate in the cache key | Accepted |
| [0010](adr/0010-feature-flags.md) | Every non-core capability is behind a flag | Accepted |