# Generated Shura repository

This directory is the default output location for `shura publish --release`. It
mimics the layout Mihon/Keiyoushi clients actually read:

- `repo.json` — trust metadata: `meta.{name, website, signingKeyFingerprint}`
  (64-hex SHA-256 of the certificate that signs the shipped APKs).
- `index.json` / `index.min.json` — the legacy index: a JSON *array* of
  `NetworkLegacyExtension` entries whose `apk` resolves to `<base>/apk/<name>`.
- `index.pb` — gzip-compressed protobuf `NetworkExtensionStore` (Mihon v2 field
  numbering, `shura_core/publishing/index.proto`).
- `index.shura.json` — Shura audit trail (provenance, artifact sha256, signing
  certificate, malware verdict) for every published package. Not read by clients.
- `apk/` — the signed extension APKs, byte-identical to the accepted artifacts.

Signing is fail-closed: a publish refuses to proceed unless every published
package carries the same configured signing certificate fingerprint, so the
published fingerprint always matches the actual APK signers.