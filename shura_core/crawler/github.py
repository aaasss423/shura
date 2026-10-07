import json
from shura_core.crawler.base import Crawler,CrawlResult
from shura_core.models import Candidate
class GitHubReleasesCrawler(Crawler):
    def __init__(self,http):self.http=http
    def crawl(self,source,budget=None):
        data,_,final=self.http.get(source.url,{"Accept":"application/vnd.github+json"});releases=json.loads(data)
        if isinstance(releases,dict):releases=[releases]
        out=CrawlResult(pages=1,trace=[{"url":source.url,"final_url":final,"type":"github-releases"}])
        limit=(budget or {}).get("max_candidates",1000)
        for release in releases:
            if len(out.candidates)>=limit:break
            tag=str(release.get("tag_name") or release.get("name") or "")
            for asset in release.get("assets",[]):
                if len(out.candidates)>=limit:break
                name=asset.get("name","")
                if not name.lower().endswith(".apk"):continue
                pkg=source.configuration.get("package")
                if not pkg:continue
                out.candidates.append(Candidate(source.source_id,pkg,tag,asset.get("browser_download_url",""),source.name,source.language,source.configuration.get("library",""),source.configuration.get("signing_key"),{"asset":name,"release":tag},{"release_api":final,"asset_api":asset.get("url")}))
        return out
