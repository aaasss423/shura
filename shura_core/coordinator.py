from collections import Counter
from time import monotonic
from urllib.parse import urlparse
from shura_core.crawler import IndexCrawler,GitHubReleasesCrawler,HtmlListingCrawler
from shura_core.security.network import SafeHTTP

class CrawlCoordinator:
    def __init__(self,store,max_sources=250,max_candidates=1000,max_pages=1000,max_seconds=1800,notifier=None,http_factory=None):
        self.store=store;self.max_sources=max(0,min(int(max_sources),250));self.max_candidates=max(0,min(int(max_candidates),5000));self.max_pages=max(0,min(int(max_pages),5000));self.max_seconds=max(1,min(float(max_seconds),3600));self.notifier=notifier;self.http_factory=http_factory or SafeHTTP;self.counters=Counter()
    def run(self,source_ids=None):
        self.counters=Counter()
        eligible=[s for s in self.store.sources() if (source_ids is None or s.source_id in source_ids) and self.store.eligible(s)]
        selected=eligible[:self.max_sources];results=[];seen=set();deadline=monotonic()+self.max_seconds
        self.counters["sourcesBudgetSkipped"]+=max(0,len(eligible)-len(selected))
        for source in selected:
            if monotonic()>=deadline or self.counters["pages"]>=self.max_pages or self.counters["discovered"]>=self.max_candidates:
                self.counters["sourcesBudgetSkipped"]+=1;continue
            self.counters["sourcesAttempted"]+=1
            try:
                hosts=set(source.configuration.get("allowed_hosts",[]))
                if not hosts:raise ValueError("source must configure explicit allowed_hosts")
                http=self.http_factory(hosts,timeout=source.configuration.get("timeout",15),max_bytes=source.configuration.get("max_response_bytes",8_000_000))
                crawler={"index":IndexCrawler,"github-releases":GitHubReleasesCrawler,"html":HtmlListingCrawler}.get(source.kind)
                if not crawler:raise ValueError(f"unsupported source kind: {source.kind}")
                page_left=max(0,self.max_pages-self.counters["pages"]);candidate_left=max(0,self.max_candidates-self.counters["discovered"])
                instance=crawler(http) if source.kind!="html" else crawler(http,page_left)
                result=instance.crawl(source,{"max_pages":page_left,"max_candidates":candidate_left})
                self.counters.update(result.counters);self.counters["pages"]+=result.pages
                fresh=[]
                for c in result.candidates:
                    self.counters["discovered"]+=1;self.store.event(c.source_id,"discovered",{"identity":c.identity})
                    key=(c.source_id,c.identity)
                    if key in seen:self.counters["duplicateCandidates"]+=1;self.counters["duplicatesSkipped"]+=1;self.store.event(c.source_id,"duplicate",{"identity":c.identity});continue
                    seen.add(key)
                    if self.store.already_processed(c.source_id,c.identity,source.configuration_fingerprint):self.counters["knownEntriesSkipped"]+=1;self.store.event(c.source_id,"known_entry_skipped",{"identity":c.identity});continue
                    if self.store.has_pending_for(c.source_id,c.identity):
                        # Re-expose pending work so a failed pipeline/download can be retried.
                        self.counters["pendingResumed"]+=1
                    else:self.store.put_pending(c,"discovered")
                    fresh.append(c)
                self.store.record_attempt(source.source_id,True);self.counters["sourcesSucceeded"]+=1;results.extend(fresh)
                if self.notifier:self.notifier.send(f"Shura crawl success: {source.name} ({len(fresh)} candidates)")
            except Exception as e:
                self.store.record_attempt(source.source_id,False,str(e));self.counters["sourcesFailed"]+=1
                if self.notifier:self.notifier.send(f"Shura crawl failure: {source.name}: {e}")
            finally:self.counters["sourcesAccounted"]+=1
        return results,dict(self.counters)
