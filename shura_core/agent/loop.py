"""The loop: a site becomes a published extension, and every step is remembered.

The pieces this stitches together all existed and none of them were connected.
A site could be discovered and evaluated (``crawler.arabic``), read
(``content.htmlsite``), converted into a Gradle module (``extension.converter``)
and built into an APK (``extension.builder``) - and then the artifact stopped,
because nothing turned it into something the crawl pipeline would publish. The
only route was a hand-written ``sources.json`` entry naming the APK and the
64-hex certificate it had been signed with, which is exactly the step a loop
cannot do by hand every time.

:class:`AgentLoop` closes that. It runs one site at a time through the real
gates - never around them - and writes a ``sources.json`` entry for whatever it
built, so the artifact re-enters the ordinary crawl, scan and publish path with
the certificate it was actually signed with. The loop decides *whether* to try a
site; the pipeline it feeds still decides whether the result may ship.

Every step is written to :mod:`shura_core.agent.knowledge` first, so a run that
fails is the next run's input.
"""

from __future__ import annotations

import json
import os
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlparse

from ..content.htmlsite import HtmlCatalogueSource, SiteDescriptor
from ..extension.builder import BuildFailure, build_extension
from ..extension.converter import build_plan, write_module
from ..security.network import SafeHTTP

from .diagnose import classify, classify_build
from .knowledge import Attempt, Cause, Knowledge

__all__ = ["AgentLoop", "SiteResult"]


@dataclass
class SiteResult:
    """What one site produced in one run."""

    source_id: str
    stage: str
    outcome: str
    cause: str = ""
    gate: str = ""
    blockers: list[str] = field(default_factory=list)
    evidence: dict = field(default_factory=dict)
    apk: str = ""
    package: str = ""
    certificate: str = ""
    published: bool = False
    error: str = ""

    def as_dict(self) -> dict:
        return {
            "source_id": self.source_id, "stage": self.stage,
            "outcome": self.outcome, "cause": self.cause, "gate": self.gate,
            "blockers": self.blockers, "evidence": self.evidence, "apk": self.apk,
            "package": self.package, "certificate": self.certificate,
            "published": self.published, "error": self.error,
        }


class AgentLoop:
    """Convert accepted sites into published extensions, and learn as it goes."""

    def __init__(self, store, *, sources_path="sources.json",
                 android_root="android", jdk=None, build_tools=None,
                 gradle="gradle", timeout=1800, http_factory=None,
                 works=3, chapters_per_work=2, pages_per_chapter=3,
                 build=True, evidence_only=False):
        self.store = store
        self.sources_path = Path(sources_path)
        self.android_root = Path(android_root)
        self.jdk = jdk or "/usr/lib/jvm/java-17-openjdk"
        self.build_tools = build_tools or "/opt/android-sdk/build-tools/35.0.0"
        self.gradle = gradle
        self.timeout = timeout
        self.build = build
        self.evidence_only = evidence_only
        self.works = max(1, works)
        self.chapters_per_work = max(1, chapters_per_work)
        self.pages_per_chapter = max(1, pages_per_chapter)
        # The JDK is resolved the same way the artifact scanner resolves its
        # signer: a JDK that cannot start is not a build toolchain, and passing
        # one produces a Gradle error that reads like a compile failure.
        self.jdk = self._working_jdk(jdk) if jdk is None else jdk
        self._http_factory = http_factory or SafeHTTP
        self.knowledge = Knowledge(store)

    @staticmethod
    def _working_jdk(preferred):
        from ..security.artifacts import _discover_java_home
        return str(preferred or _discover_java_home() or "/usr/lib/jvm/java-17-openjdk")

    # -- one site ---------------------------------------------------------
    def run_site(self, source) -> SiteResult:
        """Take one configured source all the way to a publishable artifact."""
        source_id = source.source_id
        retry, why_not = self.knowledge.is_worth_retrying(source_id)
        if not retry:
            return SiteResult(source_id=source_id, stage="skip", outcome="skipped",
                              cause="known_unconvertible", gate="knowledge",
                              error=why_not)

        cfg = source.configuration or {}
        hosts = [str(h) for h in (cfg.get("allowed_hosts") or [])]
        if not hosts:
            return self._record(source_id, SiteResult(
                source_id=source_id, stage="evaluate", outcome="refused",
                cause=Cause.SITE_REFUSED, gate="sources:allowed_hosts",
                error="the source names no allowed_hosts, so it cannot be read"))

        evidence, reader_error = self._gather(source, hosts, cfg)
        if reader_error:
            cause, gate = classify([reader_error])
            return self._record(source_id, SiteResult(
                source_id=source_id, stage="evaluate", outcome="refused",
                cause=cause, gate=gate, error=reader_error))

        host = urlparse(source.url).hostname or ""
        descriptor = SiteDescriptor.from_record(
            {"source_id": source_id, "name": source.name,
             "language": source.language, "url": source.url,
             "discovery_evidence": {"url": source.url, "domain": host,
                                    "origin": source.url}},
            source_id=source_id)

        plan = build_plan(descriptor, observed_hrefs=evidence["work_hrefs"],
                          observed_page_images=evidence["page_images"],
                          page_images_observed=bool(evidence["page_images"]),
                          chapter_url=evidence["chapter_url"],
                          work_url=evidence["work_url"],
                          observed_chapter_urls=evidence["chapter_urls"])
        counts = {"work_hrefs": len(evidence["work_hrefs"]),
                  "chapter_urls": len(evidence["chapter_urls"]),
                  "page_images": len(evidence["page_images"])}

        if not plan.publishable:
            cause, gate = classify(list(plan.blockers))
            return self._record(source_id, SiteResult(
                source_id=source_id, stage="convert", outcome="refused",
                cause=cause, gate=gate, blockers=list(plan.blockers),
                evidence=counts, package=plan.package))

        if self.evidence_only or not self.build:
            return self._record(source_id, SiteResult(
                source_id=source_id, stage="convert", outcome="planned",
                cause=Cause.OK, gate="-", evidence=counts, package=plan.package,
                apk="", blockers=[]))

        module = write_module(self.android_root, plan)
        try:
            built = build_extension(self.android_root, plan.module_name,
                                    jdk=self.jdk, build_tools=self.build_tools,
                                    gradle=self.gradle, timeout=self.timeout)
        except BuildFailure as exc:
            cause, gate = classify_build(str(exc))
            return self._record(source_id, SiteResult(
                source_id=source_id, stage="build", outcome="failed",
                cause=cause, gate=gate, evidence=counts, package=plan.package,
                error=str(exc)))

        # The bridge that did not exist before: name the artifact we just built
        # in the crawl configuration, with the certificate it was *actually*
        # signed with. The publisher refuses a mismatch, so this is the only
        # value that can be correct here.
        registered = self._register(source, plan, built)
        result = SiteResult(
            source_id=source_id, stage="registered", outcome="built",
            cause=Cause.OK, gate="-", evidence=counts, package=plan.package,
            apk=str(built.apk), certificate=built.certificate_sha256)
        result.error = "" if registered else "built but could not be added to the crawl configuration"
        return self._record(source_id, result, module=str(module))

    # -- evidence ---------------------------------------------------------
    def _gather(self, source, hosts, cfg) -> tuple[dict, str]:
        """Read the site and collect what it actually served.

        Everything returned is something the site served, not something guessed:
        the entry shape, the chapter shape and the page prefix are all derived
        from these, so an invented value here becomes an extension that finds
        nothing at runtime.
        """
        reader = HtmlCatalogueSource(
            SiteDescriptor.from_record(
                {"source_id": source.source_id, "name": source.name,
                 "language": source.language, "url": source.url,
                 "discovery_evidence": {"url": source.url,
                                        "domain": urlparse(source.url).hostname or "",
                                        "origin": source.url}},
                source_id=source.source_id),
            self._http_factory(hosts, timeout=cfg.get("timeout", 15),
                               max_bytes=2_000_000))
        work_hrefs: list[str] = []
        chapter_urls: list[str] = []
        page_images: list[str] = []
        work_url = chapter_url = ""
        for work in reader.works(limit=self.works):
            # A chapter-only catalogue still proves its listing shape through the
            # chapter links, so those count as observed hrefs too. Refusing them
            # would reject exactly the Arabic sites this loop exists to serve.
            if work.get("url") and not work_url:
                work_url = work["url"]
                work_hrefs.append(work["url"])
            for chapter in reader.chapters(work)[:self.chapters_per_work]:
                if chapter["url"]:
                    chapter_urls.append(chapter["url"])
                    work_hrefs.append(chapter["url"])
                if not chapter_url:
                    chapter_url = chapter["url"]
                for page in reader.pages(chapter)[:self.pages_per_chapter]:
                    if page.get("url"):
                        page_images.append(page["url"])
        return ({"work_hrefs": list(dict.fromkeys(work_hrefs)),
                 "chapter_urls": list(dict.fromkeys(chapter_urls)),
                 "page_images": list(dict.fromkeys(page_images)),
                 "work_url": work_url, "chapter_url": chapter_url},
                "")

    # -- registration -----------------------------------------------------
    def _register(self, source, plan, built) -> bool:
        """Add or update this site in the crawl configuration.

        Written back to the same JSON file the operator reads, so a generated
        extension is crawled exactly like any other source instead of needing a
        hand-maintained entry to stay in sync with what was actually built.
        """
        try:
            entries = json.loads(self.sources_path.read_text(encoding="utf-8")) \
                if self.sources_path.exists() else []
        except (OSError, ValueError):
            entries = []
        if not isinstance(entries, list):
            entries = []
        configuration = dict(source.configuration or {})
        # Same containment rule the crawler enforces, on resolved paths: a
        # sibling directory sharing this one's name prefix ("/workspace-backup"
        # against "/workspace") passes a string test and is not inside the tree.
        root = Path.cwd().resolve()
        apk_path = Path(built.apk).resolve()
        if root not in apk_path.parents:
            return False
        configuration.update({
            "artifact_path": str(apk_path.relative_to(root)),
            "package": plan.package,
            "version": plan.version_name,
            "signing_key": built.certificate_sha256,
            "library": "shura",
        })
        # The artifact lives inside the working tree or LocalArtifactCrawler
        # refuses it; saying so here beats failing silently in the next crawl.
        entry = {"source_id": source.source_id, "name": plan.name or source.name,
                 "url": source.url, "kind": "local-artifact",
                 "language": plan.language or source.language,
                 "region": source.region, "enabled": True,
                 "configuration": configuration}
        replaced = False
        for i, existing in enumerate(entries):
            if isinstance(existing, dict) and existing.get("source_id") == source.source_id:
                entries[i] = entry
                replaced = True
                break
        if not replaced:
            entries.append(entry)
        # Written atomically: this file is the operator's hand-maintained
        # configuration, and a plain write interrupted halfway would truncate
        # every other source in it, not just the one being added.
        payload = json.dumps(entries, ensure_ascii=False, indent=2) + "\n"
        try:
            tmp = self.sources_path.with_name(self.sources_path.name + ".tmp")
            tmp.write_text(payload, encoding="utf-8")
            os.replace(tmp, self.sources_path)
        except OSError:
            return False
        try:
            self.store.add_source(source.__class__(**{
                **{f: getattr(source, f) for f in
                   ("source_id", "name", "url", "kind", "language", "region", "enabled")},
                "configuration": configuration}))
        except Exception:                                  # noqa: BLE001
            pass                                            # the file is the truth
        return True

    # -- memory -----------------------------------------------------------
    def _record(self, source_id, result: SiteResult, module: str = "") -> SiteResult:
        detail = {"error": result.error}
        if module:
            detail["module"] = module
        if result.certificate:
            detail["certificate"] = result.certificate
        self.knowledge.record(Attempt(
            source_id=source_id, stage=result.stage, outcome=result.outcome,
            cause=result.cause, package=result.package, blockers=result.blockers,
            evidence=result.evidence, detail=detail))
        return result

    # -- many sites -------------------------------------------------------
    def run(self, sources) -> dict:
        """Run every configured source and report what happened."""
        results = [self.run_site(s) for s in sources]
        published = [r for r in results if r.outcome in ("built", "planned")]
        return {
            "attempted": len(results),
            "succeeded": len(published),
            "refused": len([r for r in results if r.outcome in ("refused", "failed")]),
            "skipped": len([r for r in results if r.outcome == "skipped"]),
            "results": [r.as_dict() for r in results],
            "learning": self.knowledge.blame(),
            "at": datetime.now(timezone.utc).isoformat(),
        }