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
    def _provenance_sources(item: dict) -> list[dict]:
        """The sources the crawled index declared for this extension.

        An index publishes them and this threw them away, so every entry went
        out with an empty source list - a field a reader has always populated
        for a working repository. Only what was actually crawled is emitted; an
        extension whose index listed nothing stays empty rather than being given
        an invented source.
        """
        provenance = item.get("metadata") or {}
        raw = provenance.get("sources")
        if not isinstance(raw, list):
            return []
        out = []
        for source in raw:
            if not isinstance(source, dict):
                continue
            name = str(source.get("name") or "").strip()
            if not name:
                continue
            out.append({"id": source.get("id") or 0,
                        "name": name,
                        "language": str(source.get("language") or source.get("lang") or "und"),
                        "home_url": str(source.get("homeUrl") or source.get("url") or "")})
        return out

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
            "sources": RepositoryPublisher._provenance_sources(item),
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
            # Shura Core's own derivations, published so the Android reader has
            # something real to compare the artifact against. These were
            # absent, so the reader silently fell back to null and 0 and every
            # comparison it makes was against a value that was never published.
            "index_version_code": _version_code(str(item.get("version", ""))),
            "index_extension_lib": index_pb.extension_lib_for_version(
                str(item.get("version", ""))),
        }

    def _signing_fingerprint(self, items: list[dict]) -> tuple[str, list[str]]:
        """Derive the repository signing key and fail closed on any absence or
        disagreement.

        Returns the primary fingerprint and the full set of publishers the
        repository carries.

        The single-fingerprint rule came from Mihon/Keiyoushi, which trust one
        key per repository. Shura is not Mihon: its client checks each artifact
        against the publisher trusted for that artifact's own package, and the
        repository anchor only has to be one of the publishers it declares. That
        distinction is what lets one repository carry both Shura's own signed
        extensions and a third-party Arabic source, instead of structurally
        being able to hold exactly one publisher's work.
        """
        fingerprints = set()
        fingerprints_count: dict[str, int] = {}
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
            fingerprints_count[certificate] = fingerprints_count.get(certificate, 0) + 1
        ordered = sorted(fingerprints)
        # The primary is the one that signs the majority, so a repository that
        # is mostly Shura's own work still pins Shura's key first and stays
        # compatible with a client that reads the singular field.
        primary = max(ordered, key=lambda f: fingerprints_count.get(f, 0))
        return primary, ordered

    def _content_gate(self, items: list[dict]) -> None:
        """Final publication gate on the content classification.

        The pipeline already classifies, but a publisher that trusts the pipeline
        has no gate at all: anything that reaches ``stage='accepted'`` ships. This
        re-derives the refusal from the decision recorded on the candidate, so a
        direct call to ``publish`` - or an item promoted by some other route -
        cannot put unclassified or explicit content into the public repository.

        Refuses: no recorded decision, NSFW, QUARANTINED, MIXED, UNKNOWN, and any
        decision whose policy fingerprint no longer matches the source's current
        configuration. There is deliberately no approval escape hatch.
        """
        from shura_core.quality.adult import SourceClass

        publishable = {SourceClass.SAFE.value, SourceClass.MATURE.value}
        # MIXED is absent from `publishable` and is never granted by an approval
        # field: approval is text, not proof that forbidden items are excluded
        # from what the client can reach.
        for item in items:
            identity = item.get("identity", "unknown")
            decision = (item.get("metadata", {}) or {}).get("_content_policy")
            if not decision:
                raise PublishRefused(
                    f"refusing to publish: {identity} carries no content classification; "
                    "an unclassified item must not reach the public repository"
                )
            classification = str(decision.get("classification") or "")
            if classification in (SourceClass.NSFW.value, SourceClass.QUARANTINED.value):
                raise PublishRefused(
                    f"refusing to publish: {identity} is classified {classification}; "
                    "explicit content is not admitted to the public repository"
                )
            if classification not in publishable:
                raise PublishRefused(
                    f"refusing to publish: {identity} is classified {classification or 'UNKNOWN'}; "
                    "only SAFE or MATURE may reach the public repository"
                )
            recorded = decision.get("policy_fingerprint")
            source = self.store.get_source(item.get("source_id", ""))
            if recorded and source and recorded != source.fingerprint():
                raise PublishRefused(
                    f"refusing to publish: {identity} was approved under a different content "
                    "policy; the source configuration changed after the decision"
                )

    def _security_gate(self, items: list[dict]) -> None:
        """Refuse to publish an artifact whose recorded malware verdict is not CLEAN.

        Defense in depth behind the pipeline: an artifact only reaches the ledger
        with a recorded verdict, and anything the scanner could not decide (engine
        missing, signature database stale, scan error) must never be served. An
        absent ``malware`` record means the item predates the malware gate and is
        left alone, so this cannot strand a previously published repository.
        """
        for item in items:
            security = (item.get("metadata", {}) or {}).get("_security", {}) or {}
            verdict = str((security.get("malware") or {}).get("verdict", "") or "")
            if verdict and verdict.upper() != "CLEAN":
                raise PublishRefused(
                    f"refusing to publish: {item.get('identity', 'unknown')} has malware verdict "
                    f"{verdict}; only CLEAN-scanned artifacts may be published"
                )

    def _materialize(self, items: list[dict]):
        """Write the consumable repository (apk/, index files) for ``items``.

        The signing gate runs before any bytes are written, so an absent or
        mismatched signing key leaves the repository untouched. On any failure
        previously written files are rolled back to their prior content.

        Returns ``(signing_key_fingerprint, rollback_callable)``.
        """
        fingerprint, publishers = self._signing_fingerprint(items)
        self._content_gate(items)
        self._security_gate(items)
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
                # The full set of publishers this repository carries. Kept here
                # rather than in repo.json on purpose: repo.json is the
                # Keiyoushi-compatible shape, which the client contract tests
                # pin exactly, and adding a field to it would break that for
                # every other client. index.shura.json is Shura's own file.
                "signing_key_fingerprints": publishers,
                "generated_at": generated_at,
                "signing_key_fingerprint": fingerprint,
                "packages": [self._audit_entry(x) for x in items],
            }
            output[self.root / "index.shura.json"] = json.dumps(audit, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
            repo_doc = {
                "meta": {
                    "name": "Shura",
                    "website": website,
                    # Kept for clients that read one key, and made the primary
                    # publisher so it is never the empty or surprising value.
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