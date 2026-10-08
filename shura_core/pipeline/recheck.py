"""Generic chapter rechecker for the production retry loop.

Any source can opt in by setting content_policy.manifest_url: a JSON content manifest
served by the source itself (or an operator mirror). Rechecks re-fetch the manifest,
re-resolve the chapter's public pages and re-classify; a stale link finds its fresh
replacement and a temporarily failed chapter can heal, without touching paid/gated content.
"""
from __future__ import annotations
import json
from typing import Any

from ..models import ChapterRecord
from ..security.network import SafeHTTP
from .content import evaluate_chapter

class ManifestRechecker:
    def __init__(self,store,http_factory=None):
        self.store=store;self.http_factory=http_factory or SafeHTTP
    def _fetch_manifest(self,source,policy):
        url=policy.get("manifest_url")
        if not url:return None
        hosts=set(source.configuration.get("allowed_hosts",[]))
        if "//" in url:hosts.add(url.split("//",1)[-1].split("/")[0])
        http=self.http_factory(hosts if hosts else {"manifest.invalid"},timeout=policy.get("fetch_timeout",15),max_bytes=policy.get("manifest_max_bytes",8_000_000))
        data,h,_=http.get(url,max_bytes=policy.get("manifest_max_bytes",8_000_000))
        try:return json.loads(data.decode("utf-8"))
        except Exception as e:raise ValueError(f"manifest is not valid JSON: {e}") from e
    @staticmethod
    def _find_work(manifest,work_id):
        if isinstance(manifest,dict):
            if isinstance(manifest.get("works"),list):
                return next((w for w in manifest["works"] if str(w.get("work_id") or w.get("id"))==work_id),None)
            if str(manifest.get("work_id") or manifest.get("id"))==work_id:return manifest
        return None
    def recheck(self,chapter_record,policy):
        """Return a fresh ChapterRecord for a due retryable chapter, or None if the source
        does not expose a manifest to re-resolve from."""
        sid=chapter_record["source_id"];wid=chapter_record["work_id"];cid=chapter_record["chapter_id"]
        source=self.store.get_source(sid)
        if not source:return None
        full_policy=source.configuration.get("content_policy",{}) if source.configuration else policy
        manifest=self._fetch_manifest(source,full_policy)
        if manifest is None:return None
        work=self._find_work(manifest,wid)
        if work is None:return None
        page_fetch=None
        page_hosts=set(full_policy.get("page_hosts",[]))
        if source.configuration.get("allowed_hosts"):page_hosts.update(source.configuration["allowed_hosts"])
        if page_hosts:
            http=self.http_factory(page_hosts,timeout=full_policy.get("fetch_timeout",15),max_bytes=full_policy.get("max_page_bytes",2_000_000))
            page_fetch=lambda url,h=http: h.get(url,max_bytes=full_policy.get("max_page_bytes",2_000_000))
        for ch in work.get("chapters",[]):
            cand=dict(ch);cand["work_id"]=wid
            if str(cand.get("chapter_id") or cand.get("id"))==cid:
                return evaluate_chapter(cand,page_fetch,full_policy)
        return None