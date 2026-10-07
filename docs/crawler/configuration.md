# Source configuration

Each JSON source requires `source_id`, `name`, `url`, `kind` (`index`, `github-releases`, `html`), and a nonempty `configuration.allowed_hosts` list containing the seed host and intended artifact/CDN hosts. URLs must use HTTPS. Enabled sources require a trusted 64-hex SHA-256 certificate fingerprint in `configuration.signing_key`; GitHub release sources also require `configuration.package`. The fingerprint is public metadata, not a secret. Source state is runtime-managed: do not put `state` in the configuration file; use stop/retry/review commands.

HTML entries may use `data-shura-package` and `data-shura-version`; signing values in HTML are ignored. GitHub Releases sources point to a releases API URL and require the repository and release asset hosts in `allowed_hosts`. HTML traversal follows same-host rel=next/alternate links with page and listing caps.
