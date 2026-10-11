import json
import re
from shura_core.crawler.base import Crawler,CrawlResult
from shura_core.models import Candidate

#: Asset names that mean "one build for every architecture". Kept explicit: the
#: point is to recognise a name the publisher chose, not to infer an ABI.
_UNIVERSAL=re.compile(r"(universal|noabi|no[-_]abi|all[-_]abis)",re.I)

def select_release_asset(assets,configured=None):
    """Choose one APK from a release, or refuse the release.

    The version stays what the source says it is - the tag - and the identity
    stays ``package|version``. Several APKs in one release are *alternatives for
    the same version*, not separate versions and not a conflict.

    The order is conservative and stops at the first thing that is a stated
    intent rather than a guess:

    1. an asset the source configuration names explicitly;
    2. otherwise a single ``universal`` build;
    3. otherwise the release is unresolved, and it is refused.

    Never "take the first". Two builds of one release differ in bytes, so
    picking one arbitrarily would publish something nobody chose, and the
    publisher would see conflicting hashes under a single identity and refuse
    the lot anyway.
    """
    apks=[a for a in assets if str(a.get("name","")).lower().endswith(".apk")]
    if not apks:return None,None
    if len(apks)==1:return apks[0],None
    names=[str(a.get("name","")) for a in apks]
    if configured:
        chosen=[a for a in apks if str(a.get("name",""))==str(configured)]
        if len(chosen)==1:return chosen[0],None
        return None,{"reason":"configured asset not found among the release's APKs",
                     "requested":str(configured),"assets":names}
    universal=[a for a in apks if _UNIVERSAL.search(str(a.get("name","")))]
    if len(universal)==1:return universal[0],None
    if len(universal)>1:
        return None,{"reason":"several universal assets; name one in the source configuration",
                     "assets":names}
    return None,{"reason":"several APK builds and no universal one; name one in the source configuration",
                 "assets":names}

class GitHubReleasesCrawler(Crawler):
    def __init__(self,http):self.http=http
    def crawl(self,source,budget=None):
        data,_,final=self.http.get(source.url,{"Accept":"application/vnd.github+json"});releases=json.loads(data)
        if isinstance(releases,dict):releases=[releases]
        out=CrawlResult(pages=1,trace=[{"url":source.url,"final_url":final,"type":"github-releases"}])
        limit=(budget or {}).get("max_candidates",1000)
        configured=source.configuration.get("asset")
        for release in releases:
            if len(out.candidates)>=limit:break
            tag=str(release.get("tag_name") or release.get("name") or "")
            asset,refusal=select_release_asset(release.get("assets",[]),configured)
            if refusal is not None:
                # Recorded, with the names that were on offer, and nothing is
                # emitted for this release. No artifact is chosen by default.
                out.trace.append({"url":source.url,"final_url":final,"type":"github-releases",
                                  "release":tag,"refused":refusal})
                continue
            if asset is None:continue
            pkg=source.configuration.get("package")
            if not pkg:continue
            name=str(asset.get("name",""))
            out.candidates.append(Candidate(source.source_id,pkg,tag,asset.get("browser_download_url",""),source.name,source.language,source.configuration.get("library",""),source.configuration.get("signing_key"),{"asset":name,"release":tag},{"release_api":final,"asset_api":asset.get("url")}))
        return out