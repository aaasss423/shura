import json
from urllib.parse import urlparse
from shura_core.crawler.base import Crawler,CrawlResult
from shura_core.models import Candidate
class IndexCrawler(Crawler):
    def __init__(self,http):self.http=http
    def crawl(self,source,budget=None):
        raw,headers,final=self.http.get(source.url);doc=json.loads(raw);items=doc if isinstance(doc,list) else doc.get("extensions",[])
        out=CrawlResult(pages=1,trace=[{"url":source.url,"final_url":final,"type":"index"}])
        for i,item in enumerate(items):
            if budget and len(out.candidates)>=budget.get("max_candidates",100):break
            url=item.get("apk",item.get("apk_url",item.get("download")))
            pkg=item.get("pkg",item.get("package"));version=str(item.get("version",""))
            if not url or not pkg or not version:continue
            out.candidates.append(Candidate(source.source_id,str(pkg),version,url,str(item.get("name",pkg)),str(item.get("lang",source.language)),str(item.get("lib","")),item.get("signingKey"),item,{"index_url":final,"item":i}))
        return out
