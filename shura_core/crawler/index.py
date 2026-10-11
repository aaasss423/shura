import json
from urllib.parse import urljoin,urlparse
from shura_core.crawler.base import Crawler,CrawlResult
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
class IndexCrawler(Crawler):
    def __init__(self,http):self.http=http
    def crawl(self,source,budget=None):
        raw,headers,final=self.http.get(source.url);doc=json.loads(raw);items=doc if isinstance(doc,list) else doc.get("extensions",[])
        out=CrawlResult(pages=1,trace=[{"url":source.url,"final_url":final,"type":"index"}])
        for i,item in enumerate(items):
            if budget and len(out.candidates)>=budget.get("max_candidates",100):break
            named=item.get("apk",item.get("apk_url",item.get("download")))
            # An index entry names its artifact by filename, and the
            # repositories Shura publishes keep those files under apk/. Taking
            # the name verbatim produced a hostless URL that every later gate
            # refused; resolving it against the index alone produced a valid URL
            # pointing at a file that is not there. The apk/ prefix is not a
            # guess: _materialize writes there and the protobuf index states the
            # same rule, so the crawler now follows the layout this project
            # actually publishes. An absolute URL is left untouched.
            url=resolve_artifact_url(final,named)
            pkg=item.get("pkg",item.get("package"));version=str(item.get("version",""))
            if not url or not pkg or not version:continue
            out.candidates.append(Candidate(source.source_id,str(pkg),version,url,str(item.get("name",pkg)),str(item.get("lang",source.language)),str(item.get("lib","")),item.get("signingKey"),item,{"index_url":final,"item":i}))
        return out
