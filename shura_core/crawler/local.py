"""Publishes an extension this project built itself.

## Why this exists

Shura generates an Android extension from a site it discovered and accepted.
That module could then go nowhere: the crawler had three kinds - `index`,
`github-releases` and `html` - and every one of them *downloads* an artifact
somebody else published. There was no way to hand the pipeline an artifact
Shura built, so a working Shura source stayed a local APK that no repository
ever listed, and the app's store could only ever show what a third party had
published.

So the two halves of the project were disconnected: the builder produced
extensions, and the publisher published extensions, with nothing between them.

This kind closes that gap. It performs no download, because there is nothing to
download - the artifact is already built and signed on this machine. Everything
downstream is unchanged and still applies in full: the content gate, the
malware scan, the artifact and signature check, the publisher's own
re-validation. A locally built artifact gets no smaller a check than a
downloaded one, because it goes through exactly the same gates.

The one thing it does add is a check of its own: the artifact must exist and
must be inside the working tree, so a configuration cannot point at an
arbitrary path on the machine and have it shipped.
"""

from __future__ import annotations

import hashlib
from pathlib import Path

from shura_core.crawler.base import Crawler, CrawlResult
from shura_core.models import Candidate


class LocalArtifactCrawler(Crawler):
    """Emits the already-built, already-signed extension for a source."""

    def __init__(self, http=None):
        # Takes the HTTP client so it matches the other crawlers' shape. It is
        # unused on purpose: there is nothing to fetch.
        self.http = http

    def crawl(self, source, budget=None) -> CrawlResult:
        out = CrawlResult(pages=1, trace=[{"url": source.url, "type": "local-artifact"}])
        configured = str(source.configuration.get("artifact_path") or "").strip()
        if not configured:
            out.trace[-1]["refused"] = (
                "a local-artifact source must name configuration.artifact_path"
            )
            return out

        path = Path(configured)
        if not path.is_absolute():
            path = Path.cwd() / path
        path = path.resolve()

        # Only something inside the working tree may be published. Without this
        # a source configuration could address any file on the machine and have
        # it appear in a public repository.
        root = Path.cwd().resolve()
        if root not in path.parents:
            out.trace[-1]["refused"] = (
                f"{path} is outside the working tree and cannot be published"
            )
            return out

        if not path.is_file():
            out.trace[-1]["refused"] = f"{path} does not exist; build it first"
            return out

        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        out.candidates.append(
            Candidate(
                source_id=source.source_id,
                package=str(source.configuration.get("package") or source.source_id),
                version=str(source.configuration.get("version") or "1.0"),
                # The site it reads, not an artifact URL: there is no download.
                apk_url=source.url,
                name=source.name,
                language=source.language,
                provenance={"local_build": str(path)},
                metadata={
                    "_artifact_path": str(path),
                    "_local_sha256": digest,
                },
            )
        )
        return out