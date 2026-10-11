"""Content storage: schema, locking, and the local fetch stage.

Nothing here reaches the internet on its own. Production wires the repository's
existing SafeHTTP through a transport; tests wire a local fixture. There is
no switch that relaxes a network guard.
"""

from .fetcher import (Budget, Budgets, FetchResult, HOST_LIMITER, PageFetcher,
                      SharedRateLimiter, SOURCE_FILE_NAME, chapter_is_fetchable,
                      fetchable_sources, load_source_config, sync_source_policy,
                      work_is_downloadable)
from .schema import SCHEMA_VERSION, connect, migrate, open_store
from .ingest import (MANIFEST_VERSION, ImportRejected, import_manifest, import_payload,
                     manifest_summary)
from .mangadex import (DiscoveryRejected, MangaDexSource,
                     SOURCE_ID as MANGADEX_SOURCE_ID)
from .cycle import CycleReport, run_content_cycle
from .runner import FetchRunReport, default_identity, run_fetch_run
from .storage import (PageRejected, ValidatedPage, cleanup_stale_parts, digest_of, usage_bytes,
                      validate_page, write_bounded)
from .transport import SafeHTTPTransport
from .store import (Attempt, AttemptNotStartable, FenceLost, Lock, LockUnavailable, Recovered,
                    abandon_attempt, acquire_lock, assert_fence, begin_attempt, commit_done,
                    grant_clearance, recover_interrupted, release_lock, renew_lock)

__all__ = ["SCHEMA_VERSION", "connect", "migrate", "open_store", "Attempt", "Recovered",
           "AttemptNotStartable", "FenceLost", "LockUnavailable", "acquire_lock",
           "abandon_attempt", "assert_fence", "begin_attempt", "commit_done",
           "grant_clearance", "recover_interrupted", "release_lock", "renew_lock",
           "Budget", "Budgets", "FetchResult", "PageFetcher", "SharedRateLimiter",
           "HOST_LIMITER", "SOURCE_FILE_NAME", "load_source_config", "sync_source_policy",
           "fetchable_sources", "work_is_downloadable", "chapter_is_fetchable",
           "PageRejected", "ValidatedPage",
           "validate_page", "digest_of", "usage_bytes", "cleanup_stale_parts", "write_bounded",
           "SafeHTTPTransport", "run_fetch_run", "FetchRunReport", "default_identity",
           "import_manifest", "import_payload", "manifest_summary", "ImportRejected",
           "MANIFEST_VERSION", "MangaDexSource", "MANGADEX_SOURCE_ID",
           "DiscoveryRejected", "run_content_cycle", "CycleReport"]
