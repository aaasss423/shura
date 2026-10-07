import json
import os
import re
import shutil
import tempfile
import time
from pathlib import Path
from shura_core.publishing import index_pb


class PublishRefused(RuntimeError):
    pass


_HEX64 = re.compile(r"^[0-9a-fA-F]{64}$")


def _normalize_version(version: str) -> str:
    """Mihon derives the extension lib version from ``version`` via
    ``version.substringBeforeLast('.')``; a leading "v" would fail the
    ``toDouble()`` parse and break the whole store load, so it is stripped."""
    return str(version).lstrip("vV")


def _version_code(version: str) -> int:
    """Deterministic, monotonic surrogate versionCode from the dotted numeric
    segments (e.g. "1.5.1" -> 10501). Mihon uses ``code`` solely for update
    ordering; deriving it keeps ordering stable across revisions."""
    digits = re.findall(r"\d+", _normalize_version(version))
    code = 0
    for index, segment in enumerate(digits[:3]):
        code += int(segment) * (100 ** (2 - index))
    return code


class RepositoryPublisher:
    def __init__(self, store, root):
        self.store = store
        self.root = Path(root)

    def publish(self, stage=False, release=False):
        pending = self.store.pending()
        published = self.store.published()
        if not pending and not published:
            return {"published": 0, "noop": True, "refused": False, **self.store.record_noop_publication()}
        eligible = [x for x in pending if x.get("stage") == "accepted" and not self.store.is_source_quarantined(x["source_id"])]
        refused = [x for x in pending if x.get("stage") != "accepted" or self.store.is_source_quarantined(x["source_id"])]
        if stage:
            return {"published": 0, "staged": len(pending), "refused": bool(refused), "refused_count": len(refused), "publications_today": self.store.publication_count_today(), "published_this_run": 0, "publication_charged": False}
        if not release:
            raise PublishRefused("release flag required")
        existing = {x["identity"]: x for x in published}
        groups = {}
        for item in eligible:
            groups.setdefault(item["identity"], []).append(item)
        selected = []
        conflicts = []
        aliases = {}
        already = []
        for identity, items in groups.items():
            if identity in existing:
                old_hash = existing[identity].get("metadata", {}).get("_security", {}).get("sha256")
                if old_hash and all(x.get("metadata", {}).get("_security", {}).get("sha256") == old_hash for x in items):
                    already.extend(items)
                else:
                    conflicts.extend(items)
                continue
            hashes = {x.get("metadata", {}).get("_security", {}).get("sha256") for x in items}
            if len(hashes) > 1:
                conflicts.extend(items)
                continue
            selected.append(items[0])
            aliases[identity] = items[1:]
        refused.extend(conflicts)
        for item in already:
            self.store.forget_pending(item["source_id"], item["identity"])
        if not selected:
            if published:
                # Republish/refresh the whole repository so the on-disk layout
                # always matches the ledger (e.g. after a format upgrade).
                fingerprint, rollback, cleanup = self._materialize(published)
                cleanup()
                ledger = self.store.record_noop_publication() if not refused else {"published_this_run": 0, "publications_today": self.store.publication_count_today(), "publication_charged": False}
                return {"published": 0, "noop": not bool(refused), "refused": bool(refused), "refused_count": len(refused), "signing_key_fingerprint": fingerprint, **ledger}
            ledger = self.store.record_noop_publication() if not refused else {"published_this_run": 0, "publications_today": self.store.publication_count_today(), "publication_charged": False}
            return {"published": 0, "noop": not bool(refused), "refused": bool(refused), "refused_count": len(refused), **ledger}
        self.root.mkdir(parents=True, exist_ok=True)
        for item in selected:
            if not re.fullmatch(r"[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+", item.get("package", "")) or not re.fullmatch(r"[A-Za-z0-9._+-]+", item.get("version", "")):
                raise PublishRefused(f"unsafe package/version path for {item.get('identity', 'unknown')}")
            artifact = Path(item.get("metadata", {}).get("_artifact_path", ""))
            if not artifact.is_file():
                raise PublishRefused(f"verified APK missing for {item['identity']}")
        from shura_core.models import Candidate
        fingerprint, rollback, cleanup = self._materialize(published + selected)
        try:
            ledger = self.store.publish([Candidate(**{k: v for k, v in item.items() if k in Candidate.__dataclass_fields__}) for item in selected])
        except Exception:
            rollback()
            raise
        finally:
            cleanup()
        for identity, duplicates in aliases.items():
            for duplicate in duplicates:
                self.store.forget_pending(duplicate["source_id"], identity)
                self.store.event(duplicate["source_id"], "duplicate_publication", {"identity": identity, "canonical_source": next(x["source_id"] for x in selected if x["identity"] == identity)})
        return {"published": ledger["published_this_run"], "noop": False, "refused": bool(refused), "refused_count": len(refused), "signing_key_fingerprint": fingerprint, **ledger}

    @staticmethod
    def _apk_filename(item: dict) -> str:
        version = str(item.get("version", ""))
        return f"{str(item.get('package', '')).replace('.', '_')}-{version}.apk"

    @staticmethod
    def _consumer_entry(item: dict, base_url: str = "") -> dict:
        """Legacy ``index.json``/``index.min.json`` entry exactly as Mihon's
        ``NetworkLegacyExtension`` reads it. Extra keys are avoided because
        kotlinx rejects unknown keys unless ignoreUnknownKeys is enabled."""
        metadata = item.get("metadata", {}) or {}
        apk = metadata.get("_published_apk", "") or RepositoryPublisher._apk_filename(item)
        return {
            "name": item.get("name", ""),
            "pkg": item["package"],
            "apk": apk,
            "lang": item.get("language", "und"),
            "code": _version_code(item.get("version", "")),
            "version": _normalize_version(item.get("version", "")),
            "nsfw": 0,
            "sources": [],
        }

    @staticmethod
    def _audit_entry(item: dict) -> dict:
        metadata = item.get("metadata", {}) or {}
        security = metadata.get("_security", {}) or {}
        return {
            "pkg": item["package"],
            "version": item.get("version", ""),
            "apk": metadata.get("_published_apk", "") or RepositoryPublisher._apk_filename(item),
            "lang": item.get("language", "und"),
            "source_id": item["source_id"],
            "identity": item.get("identity") or f"{item['package']}|{item.get('version', '')}",
            "provenance": item.get("provenance", {}),
            "artifact_sha256": security.get("sha256", ""),
            "signing_certificate_sha256": security.get("certificate", ""),
            "malware": security.get("malware", {}),
        }

    def _signing_fingerprint(self, items: list[dict]) -> str:
        """Derive the repository signing key and fail closed on any absence or
        disagreement. Mihon/Keiyoushi trust one SHA-256 certificate fingerprint
        per repository (``meta.signingKeyFingerprint`` / proto ``signingKey``),
        so every published APK must share the same signer."""
        fingerprints = set()
        for item in items:
            security = (item.get("metadata", {}) or {}).get("_security", {}) or {}
            certificate = str(security.get("certificate") or "").lower().replace(":", "")
            if not _HEX64.fullmatch(certificate):
                raise PublishRefused(
                    f"refusing to publish: {item.get('identity', 'unknown')} has no verified signing certificate fingerprint; "
                    "Mihon/Keiyoushi require a SHA-256 (64 hex) signing key"
                )
            source = self.store.get_source(item["source_id"])
            expected = str(source.configuration.get("signing_key") or "").lower().replace(":", "") if source else ""
            if expected and _HEX64.fullmatch(expected) and expected != certificate:
                raise PublishRefused(
                    f"refusing to publish: signing key mismatch for {item.get('identity', 'unknown')}; "
                    f"certificate {certificate} differs from configured signing_key {expected}"
                )
            fingerprints.add(certificate)
        if len(fingerprints) > 1:
            raise PublishRefused(
                "refusing to publish: repository contains APKs signed by multiple keys "
                f"{', '.join(sorted(fingerprints))}; Mihon/Keiyoushi trust exactly one signing fingerprint per repo"
            )
        return fingerprints.pop()

    def _materialize(self, items: list[dict]):
        """Write the consumable repository (apk/, index files) for ``items``.

        The signing gate runs before any bytes are written, so an absent or
        mismatched signing key leaves the repository untouched. On any failure
        previously written files are rolled back to their prior content.

        Returns ``(signing_key_fingerprint, rollback_callable)``.
        """
        fingerprint = self._signing_fingerprint(items)
        base_url = os.getenv("SHURA_REPO_BASE_URL", "").rstrip("/")
        website = os.getenv("SHURA_REPO_WEBSITE", "").rstrip("/")
        self.root.mkdir(parents=True, exist_ok=True)
        apk_dir = self.root / "apk"
        apk_dir.mkdir(parents=True, exist_ok=True)
        # The staging directory also holds the pre-existing-file backups, so its
        # lifetime outlives this call: the caller must run cleanup() once the
        # ledger write has committed (or after rolling back).
        tmpdir = Path(tempfile.mkdtemp(prefix=".shura-publish-", dir=self.root))
        staged = []
        backups = []
        installed = []
        output = {}

        def install_content():
            for i, (dest, value) in enumerate(output.items()):
                tmp = tmpdir / str(i)
                if isinstance(value, Path):
                    shutil.copyfile(value, tmp)
                else:
                    tmp.write_bytes(value)
                staged.append((tmp, dest))
            for i, (_, dest) in enumerate(staged):
                if dest.exists():
                    backup = tmpdir / f"backup-{i}"
                    shutil.copyfile(dest, backup)
                    backups.append((backup, dest))
            for tmp, dest in staged:
                tmp.replace(dest)
                installed.append(dest)

        def rollback():
            for dest in installed:
                backup = next((b for b, d in backups if d == dest), None)
                if backup and backup.exists():
                    shutil.copyfile(backup, dest)
                else:
                    dest.unlink(missing_ok=True)

        try:
            for item in items:
                artifact = Path(item.get("metadata", {}).get("_artifact_path", ""))
                if not artifact.is_file():
                    raise PublishRefused(f"verified APK missing for {item.get('identity', 'unknown')}")
                filename = self._apk_filename(item)
                item.setdefault("metadata", {})["_published_apk"] = filename
                staged_file = tmpdir / ("apk-" + filename)
                shutil.copyfile(artifact, staged_file)
                output[apk_dir / filename] = staged_file
            entries = [self._consumer_entry(x, base_url) for x in items]
            generated_at = int(time.time())
            output[self.root / "index.json"] = json.dumps(entries, ensure_ascii=False, indent=2).encode("utf-8")
            output[self.root / "index.min.json"] = json.dumps(entries, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
            audit = {
                "repo": "Shura",
                "generated_at": generated_at,
                "signing_key_fingerprint": fingerprint,
                "packages": [self._audit_entry(x) for x in items],
            }
            output[self.root / "index.shura.json"] = json.dumps(audit, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
            repo_doc = {
                "meta": {
                    "name": "Shura",
                    "website": website,
                    "signingKeyFingerprint": fingerprint,
                }
            }
            if base_url:
                repo_doc["index_v2"] = base_url + "/index.pb"
            output[self.root / "repo.json"] = json.dumps(repo_doc, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
            proto_entries = [
                {
                    "name": consumer["name"],
                    "pkg": consumer["pkg"],
                    "apk": consumer["apk"],
                    "version": consumer["version"],
                    "code": consumer["code"],
                    "nsfw": consumer["nsfw"],
                    "sources": consumer["sources"],
                    "apk_url": base_url + "/apk/" + consumer["apk"] if base_url else "apk/" + consumer["apk"],
                    "icon_url": base_url + "/icon/" + consumer["pkg"] + ".png" if base_url else "",
                }
                for consumer in entries
            ]
            output[self.root / "index.pb"] = index_pb.encode_index(
                proto_entries,
                repo="Shura",
                generated_at=generated_at,
                signing_key=fingerprint,
                website=website,
            )
            install_content()
        except Exception:
            rollback()
            shutil.rmtree(tmpdir, ignore_errors=True)
            raise
        return fingerprint, rollback, (lambda: shutil.rmtree(tmpdir, ignore_errors=True))