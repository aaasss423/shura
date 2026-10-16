import json
from urllib.parse import urljoin, urlparse
from shura_core.crawler.base import Crawler,CrawlResult
from .github import select_release_asset
from shura_core.models import Candidate
ARTIFACT_DIR="apk/"
def resolve_artifact_url(index_url,named):
    """Where an index entry's artifact actually lives, given where the index is.

    Absolute and scheme-qualified names are returned unchanged: only a bare
    filename is resolved, and it resolves the way Shura itself lays the
    repository out.
    """
    if not named:return named
    text=str(named)
    if urlparse(text).scheme:return text
    if text.startswith(ARTIFACT_DIR):return urljoin(index_url,text)
    return urljoin(index_url,ARTIFACT_DIR+text)
def _items(doc):
    """The extension list, whichever of the two published shapes carries it.

    Shura writes a flat ``extensions`` array. Keiyoushi and Mihon - the format a
    real reader already consumes - nest it under ``extensionList.extensions``.
    Reading only the first meant the largest index in the ecosystem was
    invisible to the crawler, which is why this repository could only ever hold
    what a single GitHub repository happened to release.
    """
    if isinstance(doc,list):return doc
    nested=(doc.get("extensionList") or {}).get("extensions")
    if isinstance(nested,list):return nested
    return doc.get("extensions",[])

def _field(item,*names):
    for name in names:
        value=item.get(name)
        if value not in (None,""):return value
    resources=item.get("resources") or {}
    return None

def _language(item,pkg,fallback):
    """An extension's language, which an index often does not state.

    The Tachiyomi package name encodes it: ``...extension.ar.comicverse`` is
    Arabic. Falling back to the source's language would file every extension in
    a multi-language index under whatever that one source happened to declare.
    """
    explicit=item.get("lang") or item.get("sourceLang") or item.get("language")
    if explicit:return str(explicit)
    parts=str(pkg or "").split(".")
    if "extension" in parts and parts.index("extension")+1<len(parts):
        return parts[parts.index("extension")+1]
    return str(fallback or "und")

class IndexCrawler(Crawler):
    def __init__(self,http):self.http=http
    def crawl(self,source,budget=None):
        raw,headers,final=self.http.get(source.url);doc=json.loads(raw);items=_items(doc)
        out=CrawlResult(pages=1,trace=[{"url":source.url,"final_url":final,"type":"index"}])
        limit=(budget or {}).get("max_candidates",1000)
        # A public index carries every language. Taking all of them would download
        # thousands of artifacts to keep a handful, so the source says which
        # languages it is for and the rest are never fetched.
        wanted={str(x).lower() for x in (source.configuration.get("languages") or [])}
        # A content warning the index states is a fact about the extension, not
        # an opinion the publisher gets to overrule. Without this the crawler
        # downloaded hundreds of adult extensions and the repository published
        # them, because the flag was overwritten on the way out.
        allow=source.configuration.get("content_warnings")
        allowed={str(x).upper() for x in allow} if allow is not None else {"CONTENT_WARNING_SAFE"}
        # Entries are grouped by identity first, because several entries can
        # carry the same package and version and still be different binaries.
        # Deciding per entry would make the outcome depend on list order.
        groups={}
        for i,item in enumerate(items):
            named=_field(item,"apk","apk_url","download") or ((item.get("resources") or {}).get("apkUrl"))
            pkg=_field(item,"pkg","package","packageName");version=str(_field(item,"version","versionName") or "")
            if not named or not pkg or not version:continue
            warning=str(item.get("contentWarning") or "").strip().upper()
            if warning not in allowed:continue
            language=_language(item,pkg,source.language)
            if wanted and language.lower() not in wanted:continue
            groups.setdefault((str(pkg),version),[]).append((i,item,named,language))
        # The same explicit-choice-or-refuse rule the release path uses, with the
        # same `asset` setting. No new setting is introduced.
        configured=source.configuration.get("asset")
        for (pkg,version),entries in groups.items():
            chosen,refusal=select_release_asset([{"name":str(n)} for _,_,n,_ in entries],configured)
            if refusal is not None:
                out.trace.append({"url":source.url,"final_url":final,"type":"index",
                                  "identity":f"{pkg}|{version}","refused":refusal})
                continue
            if chosen is None:continue
            picked=str(chosen["name"])
            for i,item,named,language in entries:
                if len(out.candidates)>=limit:break
                if str(named)!=picked:continue
                out.candidates.append(Candidate(source.source_id,pkg,version,resolve_artifact_url(final,named),str(item.get("name",pkg)),language,str(_field(item,"lib","extensionLib") or ""),item.get("signingKey"),item,{"index_url":final,"item":i}))
        return out