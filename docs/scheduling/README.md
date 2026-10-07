# Scheduling

`Scheduler.due(at, limit=250)` filters disabled, paused, dead and quarantined sources. Transient failures retry after six hours; after repeated failures they are checked daily, and ten consecutive failures move a source to DEAD. Success resets retry timing. Manual retry resumes PAUSED/RETRY_LATER, DEAD requires `--resume-dead`, and quarantine cannot be retried. `python -m shura_core.daemon` runs the loop with persistent timestamps; no production service manager/deployment is included.
