# ADR 0008 — BYOK keys reuse the existing encrypted secret vault

Status: Accepted · 2026-10

## Context

BYOK means users supply their own provider key. That is a secret belonging to one
user, so it cannot live in the shared configuration or in a per-user plaintext
column.

## Decision

BYOK keys go through the **existing** `SecretStore` abstraction
(`src/security/secretStore.ts`), namespaced per user:
`<userId>:<provider>`. Environment variables keep priority for platform-level
keys; a user's stored key is only consulted for that user.

Non-negotiables: never plaintext, never logged, never in an API response, never
in the database, never in Git or source. Status endpoints return
`configured` and a fingerprint only.

## Consequences

- One vault implementation, one audit surface, one rotation story.
- A per-user key needs per-user file or database storage; the vault gains a
  namespaced storage backend rather than gaining a second secret mechanism.
- BYOK requires no entitlement to *product* quota — it consumes the user's own
  provider quota — but infrastructure limits still apply (ADR 0010 flag
  `ENABLE_BYOK`).
- Revoking a user's key is a vault delete plus an audit record.

## Reversal

Only by introducing a hardware-backed vault adapter, which is a drop-in change
behind the same interface.
