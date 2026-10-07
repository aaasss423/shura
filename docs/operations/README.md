# Shura operations

## Setup

Python 3.11+ is required; the control plane uses only the standard library. Define source JSON, for example:

```json
[{"source_id":"arabic-index","name":"Arabic index","url":"https://sources.example/index.json","kind":"index","language":"ar","configuration":{"allowed_hosts":["sources.example","cdn.example"],"signing_key":"TRUSTED_CERTIFICATE_SHA256"}}]
```

`signing_key` is the public SHA-256 fingerprint of a trusted certificate. Do not trust signing metadata supplied by the listing itself. `aapt` and `apksigner` must be installed and on `PATH`; artifacts are not accepted if required package/signature verification is unavailable.

## CLI

```sh
python3 -m shura_core.cli --db shura.db configure sources.json
python3 -m shura_core.cli --db shura.db discover --limit 250
python3 -m shura_core.cli --db shura.db crawl --source-id arabic-index
python3 -m shura_core.cli --db shura.db pending
python3 -m shura_core.cli --db shura.db accept --source-id arabic-index --identity 'org.example.ext|1.2'
python3 -m shura_core.cli --db shura.db accept-source --source-id arabic-index --reason reviewed
python3 -m shura_core.cli --db shura.db publish --stage
python3 -m shura_core.cli --db shura.db publish --release
python3 -m shura_core.cli --db shura.db status --source-id arabic-index
python3 -m shura_core.cli --db shura.db stop --source-id arabic-index --reason maintenance
python3 -m shura_core.cli --db shura.db retry --source-id arabic-index
python3 -m shura_core.cli --db shura.db retry --source-id broken-source --resume-dead
```

Discovered GitHub proposals stay `PAUSED` and disabled until an operator configures package ID, trusted signing key and host policy, then explicitly enables them. Sources can be reviewed with `accept-source` which moves the source to the `ACCEPTED` state (otherwise `stop`/`retry`/`crawl` drive `PAUSED`/`ACTIVE`/`RETRY_LATER`). Acceptance of a *candidate* is separate from source acceptance and security scanning. Quarantine review is audit-only by default. An explicit `review-quarantine --resolve --reason ...` records an operator decision, resolves only that item, and returns the source to ACTIVE only when no unresolved quarantines remain; the artifact must pass the full pipeline again. `forget` removes source state/history but intentionally preserves quarantine and publication records.

`publish --release` writes `index.json`, `index.min.json`, `repo.json`, `index.pb` (Shura protobuf schema) and verified APKs into the repository directory (`SHURA_REPO_DIR`, default `repo/`). Exit code 0 means a clean stage/no-op; non-zero with `"refused": true` means some pending work was refused and needs operator attention.

`python3 -m shura_core.daemon --db shura.db` runs due sources continuously (default 15-minute wake interval, maximum 250 per batch). Disabled, paused, dead and quarantined sources are not crawled. Limits are applied to sources, total pages/candidates, response sizes, per-request timeouts and downloads per CLI run.

## Notifications

Set `SHURA_TELEGRAM_TOKEN` and `SHURA_TELEGRAM_CHAT_ID`. GitHub discovery token may be set as `GITHUB_TOKEN`. Both are optional. Telegram exceptions are caught; delivery is best-effort and not durably retried.

## Tests

Run `python3 -m unittest discover -s tests -v`. Live-network checks are opt-in with `SHURA_REAL_NETWORK=1` and a configured `SHURA_LIVE_TEST_URL`; deterministic fixtures do not depend on public network access.

## Security limits

HTTPS and exact hostname allowlisting are default. Redirects are bounded and revalidated, private/reserved hosts are blocked (SSRF guard), and body/artifact/page/candidate limits apply. APK checks verify archive bounds, manifest presence, package identity via `aapt`, SHA-256, and pinned signer via `apksigner`. This is not malware analysis. Do not deploy until a real malware engine/sandbox and independent security review are added.
