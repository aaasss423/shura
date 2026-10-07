# Contributing

- Keep crawler, security, state, publishing, Android, and translation boundaries separate.
- Add deterministic local fixtures for network behavior; live-network checks must be opt-in.
- Every network source must use the shared guarded transport and explicit host policy.
- Never commit tokens, signing keys, private URLs, or downloaded APKs.
- Security quarantine must require an explicit operator review action; retries/config changes cannot clear it.
- Run `python3 -m unittest discover -s tests -v` and `git diff --check` for Python changes.
- Keep changes reviewable and document migrations and recovery behavior.
