from __future__ import annotations
import json
from urllib.parse import urlencode
from shura_core.models import Source,SourceState

class SourceDiscovery:
    """GitHub search-based proposal discovery. Results are persisted disabled for human trust setup."""
    QUERIES=(
        ("ar","manga extension arabic topic:manga"),
        ("ar","manhwa extension Arabic"),
        ("ko","manga source extension topic:manga"),
        ("ja","manga source extension topic:manga"),
        ("en","manga source extension topic:manga"),
    )
    def __init__(self,store,http,token=None,per_query=50,daily_limit=250):self.store=store;self.http=http;self.token=token;self.per_query=min(100,max(1,per_query));self.daily_limit=min(250,max(1,daily_limit))
    def discover(self):
        headers={"Accept":"application/vnd.github+json"}
        if self.token:headers["Authorization"]="Bearer "+self.token
        found={}
        for language,query in self.QUERIES:
            if len(found)>=self.daily_limit:break
            url="https://api.github.com/search/repositories?"+urlencode({"q":query,"sort":"updated","per_page":min(self.per_query,self.daily_limit-len(found))})
            body,_,final=self.http.get(url,headers)
            for repo in json.loads(body).get("items",[]):
                full=repo.get("full_name");html=repo.get("html_url")
                if not full or not html or full in found:continue
                # Search results are only suggestions. Never activate or crawl without operator validation.
                sid="github:"+full.lower()
                found[full]=Source(source_id=sid,name=repo.get("name") or full,url=f"https://api.github.com/repos/{full}/releases",kind="github-releases",language=language,enabled=False,state=SourceState.PAUSED,configuration={"allowed_hosts":["api.github.com"],"discovery_url":final,"repository_url":html,"package":"","signing_key":"","review_required":True})
        added=[]
        for source in found.values():
            existing=self.store.get_source(source.source_id)
            if existing:continue
            self.store.add_source(source);self.store.event(source.source_id,"source_discovered",{"url":source.configuration["repository_url"],"language":source.language});added.append(source)
        return added
