import os
import re
import unittest
from urllib.parse import urlparse
from shura_core.security.network import SafeHTTP
from shura_core.pipeline.content import evaluate_work
from shura_core.models import ChapterStatus

@unittest.skipUnless(os.getenv("SHURA_REAL_NETWORK") == "1", "set SHURA_REAL_NETWORK=1 to opt into live network")
class LiveNetworkTests(unittest.TestCase):
    def test_configured_live_url(self):
        url=os.getenv("SHURA_LIVE_TEST_URL","")
        self.assertTrue(url,"SHURA_LIVE_TEST_URL must be set when SHURA_REAL_NETWORK=1")
        host=urlparse(url).hostname
        body,_,_=SafeHTTP({host},timeout=10,max_bytes=1_000_000).get(url)
        self.assertGreater(len(body),0)

    def test_procomic_chapters_generic_classification_live(self):
        """Live proof of the generic per-chapter engine against procomic.net/pro (public surface only):
        paid chapters are classified and excluded without ever being fetched; a free chapter's public
        preview pages (appImages == publicImageCount slice) are verified reachable and recorded as
        PARTIAL - because the source serves the rest of the chapter via deferred/protected media that
        the manifest does not publish, full chapter health is never asserted. No tokens, sessions or
        bypass are ever attempted for gated content."""
        api=SafeHTTP({"procomic.net"},timeout=15,max_bytes=4_000_000)
        chapters=__import__("json").loads(api.get("https://procomic.net/api/chapters?contentId=506&page=1")[0])["chapters"]
        self.assertGreater(len(chapters),0)
        paid=[c for c in chapters if c.get("lockedByCoins")]
        self.assertGreater(len(paid),0)
        manifest_chapters=[]
        for c in chapters:
            if c.get("lockedByCoins"):
                manifest_chapters.append({"chapter_id":str(c["id"]),"num":c["chapter_number"],"meta":c,"gate":"COIN_LOCKED"})
        reader=SafeHTTP({"procomic.pro","app.procomic.pro"},timeout=15,max_bytes=4_000_000)
        free=max((c for c in chapters if not c.get("lockedByCoins")),key=lambda c:int(c["chapter_number"]))
        rsc,_,_=reader.get(f'https://procomic.pro/en/chapter/{free["chapter_number"]}-{free["id"]}')
        txt=rsc.decode("utf-8","replace")
        images=sorted(set(re.findall(r'https://app\.procomic\.pro[^\\"\']+desktop\.avif',txt)))
        declared=int(re.findall(r'publicImageCount[^\d]*(\d+)',txt)[0])
        self.assertEqual(len(images),declared)
        self.assertGreaterEqual(len(images),1)
        preview={"chapter_id":str(free["id"]),"num":free["chapter_number"],"meta":free,"gate":"FREE","pages":images,"previewOnly":True}
        manifest_chapters.append(preview)
        policy={"review_enabled":True,"public_only":True,"image_content_types":("image/",),"fetch_timeout":15,"max_page_bytes":4_000_000}
        review=evaluate_work(str(free["content_id"]),free["series_title"],manifest_chapters,lambda u: reader.get(u,max_bytes=policy["max_page_bytes"]),policy)
        self.assertEqual(review.verdict,"ACCEPTED",review.reason)
        self.assertEqual(review.totals.get("PAID"),len(paid))
        self.assertEqual(review.totals.get("PARTIAL"),1)
        paid_id={c.chapter_id for c in review.chapters if c.status==ChapterStatus.PAID}
        self.assertEqual(paid_id,{str(c["id"]) for c in paid})
        healthy_id={c.chapter_id for c in review.chapters if c.status==ChapterStatus.PARTIAL}
        self.assertEqual(healthy_id,{str(free["id"])})

if __name__=="__main__":unittest.main()
