# Generated Shura repository

This directory is the default output location for `shura publish --release`. Publication writes `index.json`, `index.min.json`, `repo.json`, and `index.pb` plus verified APKs. The `index.pb` uses Shura's own documented protobuf schema (`shura_core/publishing/index.proto`), not Mihon/Keiyoushi's format. No binary artifacts are committed; indexes are regenerated at runtime.
