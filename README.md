# Shura

Shura is an independent, security-gated system for discovering manga sources, validating extension artifacts, maintaining durable source history, and publishing an extension repository. Mihon/Keiyoushi compatibility may be added behind adapters; neither is a runtime core dependency.

## Current implementation

The Python control plane is runnable with only the Python standard library: SQLite state, guarded HTTP access with private-host SSRF protection, index/GitHub release/HTML crawlers, candidate validation, artifact hashing and APK signature inspection (when Android SDK build-tools are installed), quarantine, ClamAV malware scanning with a fail-closed engine/database preflight, generic per-chapter content review with retry, the full PAUSED/RETRY_LATER/DEAD/QUARANTINED/ACCEPTED lifecycle, scheduling, a Mihon/Keiyoushi-compatible repository publisher (JSON plus a protobuf `index.pb` whose field numbers match the upstream client contract), and an optional Telegram notifier. See [operations](docs/operations/README.md) for commands and limitations.

The Android modules establish a source API/host boundary and a Shura reader shell. Translation is represented by an opt-in interface and reader settings model; no translation engine or screen overlay is implemented.

## Quick start

```sh
python3 -m unittest discover -s tests -v
python3 -m shura_core.cli --help
python3 -m shura_core.cli --db shura.db pending
```

Configure sources in a JSON file; see `docs/crawler/configuration.md`. Do not supply private signing keys, Telegram credentials, or API keys in the source listing. The trusted certificate fingerprint is public metadata; keep API tokens in environment variables.

## Safety

Crawler requests are HTTPS-only by default, validate every redirect against an explicit host allowlist, and have timeout/size/page budgets. Publication accepts only candidates passing validation/security gates. APK static scanning is not malware detection; a real antivirus/sandbox verdict requires an external scanner adapter and is not claimed here. Telegram is optional and failure-isolated.

## Project status

This repository is an initial functional implementation, not a claim of production readiness. Android SDK/device testing, live source coverage and the 250/day throughput target, operational Telegram delivery, an extension build/test sandbox, signed repository metadata, crash-recovery across process death, and independent security review remain environment/deployment work. See [the status matrix](docs/operations/status.md).
