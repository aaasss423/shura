# State and recovery

SQLite WAL stores source configuration/runtime state, history events, source-scoped processed identity/configuration fingerprints, pending candidates, quarantine, publications, daily totals, and per-run publication charge records. Initial schema is version 1; a newer unsupported schema is rejected. Back up the DB before upgrades.

Paused/dead/quarantined sources are ineligible. Transient failures retry after six hours, repeated failures are checked daily, and ten consecutive failures move a source to DEAD. Explicit `retry --resume-dead` resets the broken-source count. Configuration reload preserves runtime state. Security quarantine survives source `forget` and re-registration; only explicit audited review resolution with a reason marks the reviewed item resolved, and it must pass the pipeline again.
