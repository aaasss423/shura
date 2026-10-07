# Status matrix

## DONE: verified in this workspace

- Repository foundation, `main` branch, Python packaging, GitHub Actions, contribution/security guidance, and secret-free environment template.
- Typed source/candidate/result models; SQLite WAL persistence with source-scoped identity/configuration fingerprinting, pending work, quarantine, event history, and publication ledger.
- PAUSED, RETRY_LATER, DEAD, and QUARANTINED have separate behavior. Transient retry is six hours, repeated failures move to daily checks, and ten consecutive failures move to DEAD. Manual DEAD recovery is explicit; quarantine has no bypass.
- Shared HTTPS transport validates seed and each redirect host, limits redirects/time/bytes, and supports deterministic local test injection. Private/reserved hosts (loopback, link-local, RFC1918, localhost) are blocked by default and require explicit operator opt-in only for controlled local fixtures. Index, GitHub Releases API, and paged HTML crawlers return common candidates.
- Coordinator applies source/page/candidate/time ceilings, deduplicates by source+package+version, retains and re-exposes pending work, and accounts for attempts in `finally`.
- Pipeline validates metadata/URLs, downloads bounded APKs, hashes them, validates ZIP/Manifest presence and decompression limits, invokes `aapt` for package identity and `apksigner` for signature verification when configured. Missing trust tools/key or uncertainty cannot reach acceptance. Quarantine survives forget/re-registration.
- Candidate must pass security and be explicitly accepted before local repository publication. Publisher refuses unsafe paths, atomically stages files with rollback on ledger errors, preserves pending on refusal, and charges publication only when entries are committed. It emits `index.json`, `index.min.json`, `repo.json`, and a Shura-schema `index.pb` (documented `proto3` schema in `shura_core/publishing/index.proto`).
- Source discovery can query GitHub and stores results as disabled PAUSED proposals, with Arabic queries first. It does not auto-trust or crawl proposals. A discovered/stopped source moves to ACCEPTED only through an explicit operator `accept-source`, giving the requested state machine a real reviewer step.
- Scheduler has a long-running daemon entry point. CLI supports configuration, discovery, crawl, pending, accept, accept-source, quarantine review, stop, retry, status, forget, and stage/release publishing.
- Quality ranking function, source history/status events, and optional Telegram send paths for crawl, discovery/extension review, quarantine, stop, and publication.
- Android API/host modules define source interfaces and a bounded HTTPS downloader. Android app has Compose navigation and a reader shell; translation flag defaults off and no overlay permission/system exists. A reader-local translation planner (FULL ahead-of-reading vs FOLLOW_READING bounded look-ahead) and a persisted mode store are implemented, with pure JVM unit tests authored; the engine itself is intentionally absent.
- Python deterministic regression suite passes and includes a local end-to-end crawl→artifact validation→accept→publish path.

## PARTIAL

- Discovery uses GitHub repository search and yields disabled proposals only. It does not deliver 250 trusted/working sources a day; source package ID and trusted signing fingerprint still need independent review/configuration.
- GitHub Releases crawler reads a configured releases endpoint; it does not infer package identity or trusted certificate from an APK.
- APK validation requires Android `aapt` and `apksigner`. ZIP/manifest checks and certificate pinning are implemented; there is no malware engine or sandbox.
- Repository emits Shura-protobuf `index.pb` plus JSON indexes. Mihon-compatible protobuf/signing metadata and signed repository metadata are not implemented.
- Manual candidate acceptance is a CLI/audit event, not a review web UI or multi-user authorization system. Build/test of downloaded extensions is absent.
- Scheduler runs as a daemon but has not been exercised as a deployed service. Coordinator is sequential; large-scale throughput/memory/concurrency and 250/day have not been measured.
- Telegram is optional and failure isolated; delivery is best-effort, with no durable notification outbox/retry. Some event types remain unnotified.
- Android has source API/host and a downloader, but no concrete manga source, persistence/database, chapter downloader integration, or library/catalog implementation. Reader can import/read one local image chapter and persist progress; source browsing/network chapters and multi-chapter library remain absent. Gesture/offline Android instrumentation tests are authored but unrun; JVM is unavailable in this workspace (grsecurity/PaX), so no Kotlin/Gradle test result is claimed.
- Translation API boundary, off-by-default UI flag, mode store, and FULL/FOLLOW_READING planner exist. Full/Follow execution, translated cache/offline chapter, and translation UX are not implemented, and no touch interception or screen overlay is used.
- CI is configured to compile/test Android, but the local JVM cannot start (grsecurity/PaX) and Gradle dependency downloads are not runtime tests; no Android build result is claimed.

## NOT DONE / production blockers

- Production deployment and verification with real source catalog and trusted signing fingerprints.
- Real malware scanning, extension build/test sandbox, signed repository/protobuf index, rollback/crash-recovery journal across process death, and independent security review.
- Functional Android reader from real sources, downloads/library/offline reading, device gesture tests, and translation engine/workflows.
- Live network source test and performance test at 250 sources/day.
