from html.parser import HTMLParser
from urllib.parse import urljoin,urlparse
from shura_core.crawler.base import Crawler,CrawlResult
from shura_core.models import Candidate
class ListingParser(HTMLParser):
    def __init__(self,max_links=20000):super().__init__(convert_charrefs=True);self.links=[];self.lang="und";self._current=None;self.max_links=max_links;self.links_dropped=0
    def handle_starttag(self,tag,attrs):
        a=dict(attrs)
        if tag=="html":self.lang=a.get("lang","und")
        if tag=="a" and a.get("href") and len(self.links)>=self.max_links:self.links_dropped+=1
        if tag=="a" and a.get("href") and len(self.links)<self.max_links:
            self._current={"href":a["href"],"attrs":a,"text":""};self.links.append(self._current)
    def handle_data(self,data):
        if self._current:self._current["text"]+=data
    def handle_endtag(self,tag):
        if tag=="a":self._current=None
class HtmlListingCrawler(Crawler):
    def __init__(self,http,max_pages=10):self.http=http;self.max_pages=max_pages
    def crawl(self,source,budget=None):
        allowed=set(self.http.allowed);seed=urlparse(source.url).hostname
        if seed not in allowed:raise ValueError("seed host must be explicitly allowed")
        max_pages=min(self.max_pages,(budget or {}).get("max_pages",self.max_pages));candidate_limit=max(0,(budget or {}).get("max_candidates",1000));queue=[source.url];visited=set();out=CrawlResult()
        while queue and len(visited)<max_pages and len(out.candidates)<candidate_limit:
            url=queue.pop(0)
            if url in visited:out.counters["knownPagesSkipped"]+=1;continue
            visited.add(url);body,headers,final=self.http.get(url);out.pages+=1;out.trace.append({"url":url,"final_url":final,"type":"html"})
            parser=ListingParser();parser.feed(body.decode(headers.get_content_charset() or "utf-8",errors="replace"));out.counters["listingLinksSkipped"]+=parser.links_dropped;host=urlparse(final).hostname
            for link in parser.links:
                target=urljoin(final,link["href"]);attrs=link["attrs"];path=urlparse(target).path.lower()
                isapk=path.endswith(".apk") or attrs.get("type","").lower()=="application/vnd.android.package-archive" or "shura-extension" in attrs.get("class","")
                if isapk:
                    meta={k[11:]:v for k,v in attrs.items() if k.startswith("data-shura-")};pkg=meta.get("package") or source.configuration.get("package");ver=meta.get("version") or source.configuration.get("version")
                    if pkg and ver and len(out.candidates)<candidate_limit:out.candidates.append(Candidate(source.source_id,pkg,ver,target,attrs.get("title") or link["text"].strip() or source.name,meta.get("language",parser.lang or source.language),meta.get("library",source.configuration.get("library","")),None,{"html_attributes":meta},{"page":final,"link":target}))
                elif urlparse(target).hostname==host and (attrs.get("rel","").lower().find("next")>=0 or attrs.get("rel","").lower().find("alternate")>=0):
                    if target not in visited and target not in queue:queue.append(target)
        return out
