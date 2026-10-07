from __future__ import annotations
import json,re
from pathlib import Path
from urllib.parse import urlparse
from shura_core.models import Source,SourceState
from shura_core.security.network import validate_url

class SourceConfigurationError(ValueError):pass
class SourceRegistry:
    KINDS={"index","github-releases","html"}
    def __init__(self,store):self.store=store
    @classmethod
    def parse_file(cls,path):
        try:data=json.loads(Path(path).read_text(encoding="utf-8"))
        except (OSError,json.JSONDecodeError) as e:raise SourceConfigurationError(f"cannot read source configuration: {e}") from e
        if not isinstance(data,list):raise SourceConfigurationError("source configuration must be a JSON array")
        sources=[]
        for item in data:
            if not isinstance(item,dict):raise SourceConfigurationError("each source must be an object")
            d=dict(item)
            if "state" in d:raise SourceConfigurationError("source state is runtime-managed; use stop/retry commands")
            d["state"]=SourceState.ACTIVE
            try:source=Source(**d)
            except TypeError as e:raise SourceConfigurationError(f"invalid source fields: {e}") from e
            cls.validate(source);sources.append(source)
        ids=[s.source_id for s in sources]
        if len(set(ids))!=len(ids):raise SourceConfigurationError("source_id values must be unique")
        return sources
    @classmethod
    def validate(cls,source):
        if source.kind not in cls.KINDS:raise SourceConfigurationError(f"unsupported source kind: {source.kind}")
        if not source.source_id or not re.fullmatch(r"[A-Za-z0-9_.:/-]{1,180}",source.source_id):raise SourceConfigurationError("invalid source_id")
        if not isinstance(source.name,str) or not source.name.strip() or len(source.name)>256:raise SourceConfigurationError("source name is required and limited to 256 characters")
        if not isinstance(source.enabled,bool):raise SourceConfigurationError("enabled must be a boolean")
        if not isinstance(source.language,str) or not re.fullmatch(r"(?:[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*|und)",source.language):raise SourceConfigurationError("language must be a language tag or und")
        if not isinstance(source.url,str) or len(source.url)>2048:raise SourceConfigurationError("source URL is required and limited to 2048 characters")
        if not isinstance(source.configuration,dict):raise SourceConfigurationError("configuration must be an object")
        hosts=source.configuration.get("allowed_hosts")
        if not isinstance(hosts,list) or not hosts or any(not isinstance(h,str) or not h for h in hosts):raise SourceConfigurationError("explicit configuration.allowed_hosts is required")
        try:validate_url(source.url,hosts)
        except Exception as e:raise SourceConfigurationError(f"unsafe source URL: {e}") from e
        if source.enabled:
            fingerprint=source.configuration.get("signing_key","").replace(":","")
            if not re.fullmatch(r"[0-9a-fA-F]{64}",fingerprint):raise SourceConfigurationError("enabled sources require trusted signing_key SHA-256 fingerprint")
            if source.kind=="github-releases" and not source.configuration.get("package"):raise SourceConfigurationError("enabled GitHub Releases sources require package")
        return source
    def load(self,path,update_state=False):
        sources=self.parse_file(path)
        for source in sources:self.store.add_source(source,update_state=update_state)
        return sources
