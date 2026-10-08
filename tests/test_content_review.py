import json, tempfile, threading, unittest, io
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from shura_core.models import Source,Candidate
from shura_core.state import StateStore
from shura_core.security.network import SafeHTTP
from shura_core.security.artifacts import ScanResult,ScanVerdict
from shura_core.pipeline.content import evaluate_work,MANIFEST_KEY,is_paid_chapter
from shura_core.pipeline.recheck import ManifestRechecker
from shura_core.pipeline.processor import CandidateProcessor
from shura_core.scheduling.policy import Scheduler
from shura_core.publishing.repository import RepositoryPublisher
from shura_core.models import ChapterStatus

PAGE_HOSTS=["127.0.0.1"]

class CleanScanner:
    def __init__(self):self.max_size=50_000_000
    def scan(self,path,expected_package=None,expected_certificate=None):
        return ScanResult(ScanVerdict.CLEAN,"a"*64,path.stat().st_size,"clean",expected_package or "org.example.ext",expected_certificate or "a"*64)

CState=type("CState",(),{"stale":set(),"transient":set(),"html":set(),"gated":set(),"missing":set()})
def _reset():
    CState.stale=set();CState.transient=set();CState.html=set();CState.gated=set();CState.missing=set()
CState.reset=_reset
MANIFESTS={}

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        p=self.path
        if p=="/apk":body=b"PK\x03\x04apk";kind="application/vnd.android.package-archive"
        elif p.startswith("/manifest/"):
            wid=p.split("/manifest/",1)[1].rstrip("/")
            if wid.endswith(".json"):wid=wid[:-5]
            m=MANIFESTS.get(wid)
            if not m:
                self.send_response(404);self.send_header("Content-Length","0");self.end_headers();return
            body=json.dumps(m).encode();kind="application/json"
        elif p.startswith("/pages/"):
            _,_,wid,cid,_=p.split("/")
            key=(wid,cid)
            if key in CState.gated:
                self.send_response(403);self.send_header("Content-Length","0");self.end_headers();return
            if key in CState.stale:
                self.send_response(404);self.send_header("Content-Length","0");self.end_headers();return
            if key in CState.transient:
                self.send_response(503);self.send_header("Content-Length","0");self.end_headers();return
            if key in CState.html:
                body=b"<html><body>Not Found</body></html>";self.send_response(200);self.send_header("Content-Type","text/html");self.send_header("Content-Length",str(len(body)));self.end_headers();self.wfile.write(body);return
            if key in CState.missing:
                self.send_response(410);self.send_header("Content-Length","0");self.end_headers();return
            body=b"\x89PNG-CONTENT";kind="image/png"
        elif p.startswith("/broken/"):
            self.send_response(404);self.send_header("Content-Length","0");self.end_headers();return
        else:
            self.send_response(404);self.send_header("Content-Length","0");self.end_headers();return
        self.send_response(200);self.send_header("Content-Type",kind);self.send_header("Content-Length",str(len(body)));self.end_headers()
        try:self.wfile.write(body)
        except BrokenPipeError:pass
    def log_message(self,*a):pass

def make_manifest(base,width="w1",chapter_ids=("c1","c2","c3","c4"),paid=(),gate=None,preview=()):
    chapters=[]
    for cid in chapter_ids:
        ch={"chapter_id":cid,"pages":[f"{base}/pages/{width}/{cid}/p0",f"{base}/pages/{width}/{cid}/p1"]}
        if cid in paid:ch={"chapter_id":cid,"gate":gate or "COIN_LOCKED"}
        if cid in preview:ch["previewOnly"]=True
        chapters.append(ch)
    return {"work_id":width,"work_name":"Work "+width,"provider":"fixture","chapters":chapters}

class ContentReviewTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        CState.reset()
        cls.server=ThreadingHTTPServer(("127.0.0.1",0),Handler);cls.port=cls.server.server_port;cls.thread=threading.Thread(target=cls.server.serve_forever,daemon=True);cls.thread.start();cls.base=f"http://127.0.0.1:{cls.port}"
    @classmethod
    def tearDownClass(cls):cls.server.shutdown();cls.thread.join()
    def setUp(self):
        CState.reset();MANIFESTS.clear();self.tmp=tempfile.TemporaryDirectory();self.store=StateStore(Path(self.tmp.name)/"s.db")
    def tearDown(self):self.store.close();self.tmp.cleanup()
    def http_factory(self):
        kw=dict(https_only=False,allow_nonstandard_ports=True,allow_private_hosts=True)
        return lambda hosts,*a,**k:SafeHTTP(hosts,*a,**k,**kw)
    def fetch(self,url):
        return SafeHTTP(PAGE_HOSTS,https_only=False,allow_nonstandard_ports=True,allow_private_hosts=True).get(url)
    def policy(self,**kw):
        p=dict(review_enabled=True,public_only=True,image_content_types=("image/",),fetch_timeout=5,max_page_bytes=2_000_000)
        p.update(kw);return p

    def test_work_with_broken_chapters_stays_accepted_and_records_each(self):
        CState.stale={("w1","c3")};CState.gated={("w1","c4")}
        manifest=make_manifest(self.base,"w1",("c1","c2","c3","c4"),paid=("c1",))
        review=evaluate_work("w1","Work 1",manifest["chapters"],self.fetch,self.policy())
        self.assertEqual(review.verdict,"ACCEPTED");self.assertEqual(review.totals.get("HEALTHY"),1)
        by={c.chapter_id:c for c in review.chapters}
        self.assertEqual(by["c1"].status,ChapterStatus.PAID)
        self.assertEqual(by["c2"].status,ChapterStatus.HEALTHY)
        self.assertEqual(by["c3"].status,ChapterStatus.STALE_LINK)
        self.assertEqual(by["c4"].status,ChapterStatus.UNAVAILABLE)
        self.store.record_chapters("s1",review)
        rows={c["chapter_id"]:c for c in self.store.content_chapters("s1","w1")}
        self.assertEqual(rows["c3"]["status"],"STALE_LINK");self.assertIn("page http 403: not publicly readable",rows["c4"]["reason"])
        self.assertEqual(self.store.content_work("s1","w1")["verdict"],"ACCEPTED")

    def test_healthy_chapters_keep_publishing_despite_problems_in_others(self):
        CState.transient={("w1","c3")};CState.html={("w1","c4")}
        manifest=make_manifest(self.base,"w1",("c1","c2","c3","c4"),paid=("c1",))
        review=evaluate_work("w1","Work 1",manifest["chapters"],self.fetch,self.policy())
        self.assertEqual(review.verdict,"ACCEPTED")
        healthy=[c.chapter_id for c in review.chapters if c.status==ChapterStatus.HEALTHY]
        self.assertEqual(healthy,["c2"])
        per={c.chapter_id:c.status for c in review.chapters}
        self.assertEqual(per["c3"],ChapterStatus.TEMPORARY_FAILURE);self.assertEqual(per["c4"],ChapterStatus.PARSE_ERROR)

    def test_temporary_failure_retries_not_final_reject(self):
        CState.transient={("w1","c2")}
        manifest=make_manifest(self.base,"w1",("c1","c2"),paid=("c1",))
        MANIFESTS["w1"]=manifest
        review=evaluate_work("w1","Work 1",manifest["chapters"],self.fetch,self.policy())
        self.assertEqual(review.verdict,"PENDING");self.assertIn("retry",review.reason)
        self.store.record_chapters("s1",review)
        self.store.add_source(self._source_with_review(manifest_url=self.base+"/manifest/w1.json"))
        due=self.store.due_chapters()
        self.assertEqual([d["chapter_id"] for d in due],["c2"])
        CState.transient.clear()
        result=Scheduler(self.store).recheck_due_chapters(ManifestRechecker(self.store,http_factory=self.http_factory()),limit=10)
        self.assertEqual(result["chapter_rechecks"],1);self.assertEqual(result["changed"],1)
        by={r["chapter_id"]:r for r in self.store.content_chapters("s1","w1")}
        self.assertEqual(by["c2"]["status"],"HEALTHY")
        self.assertEqual(self.store.content_work("s1","w1")["verdict"],"ACCEPTED")
        self.assertTrue(self.store.status("s1")["counts"].get("chapter_recheck"))

    def test_stale_link_recheck_resolves_to_healthy(self):
        CState.stale={("w1","c2")}
        manifest=make_manifest(self.base,"w1",("c1","c2"),paid=("c1",))
        MANIFESTS["w1"]=manifest
        review=evaluate_work("w1","Work 1",manifest["chapters"],self.fetch,self.policy())
        self.assertEqual(review.verdict,"PENDING");self.assertEqual(review.chapters[1].status,ChapterStatus.STALE_LINK)
        self.store.record_chapters("s1",review)
        self.store.add_source(self._source_with_review(manifest_url=self.base+"/manifest/w1.json"))
        CState.stale.clear()
        result=Scheduler(self.store).recheck_due_chapters(ManifestRechecker(self.store,http_factory=self.http_factory()),limit=10)
        self.assertEqual(result["changed"],1)
        self.assertEqual(self.store.content_chapters("s1","w1")[1]["status"],"HEALTHY")

    def test_truly_unavailable_chapter_recorded_without_deleting_work(self):
        CState.missing={("w1","c3")}
        manifest=make_manifest(self.base,"w1",("c2","c3"))
        review=evaluate_work("w1","Work 1",manifest["chapters"],self.fetch,self.policy())
        self.assertEqual(review.verdict,"ACCEPTED")
        self.store.record_chapters("s1",review)
        self.assertIsNotNone(self.store.content_work("s1","w1"))
        rows=self.store.content_chapters("s1","w1");by={r["chapter_id"]:r for r in rows}
        self.assertEqual(by["c3"]["status"],"UNAVAILABLE");self.assertEqual(by["c2"]["status"],"HEALTHY")

    def test_paid_chapter_excluded_without_blocking_rest(self):
        manifest=make_manifest(self.base,"w1",("c1","c2"),paid=("c1",))
        review=evaluate_work("w1","Work 1",manifest["chapters"],self.fetch,self.policy())
        self.assertEqual(review.verdict,"ACCEPTED")
        self.store.record_chapters("s1",review)
        by={r["chapter_id"]:r for r in self.store.content_chapters("s1","w1")}
        self.assertEqual(by["c1"]["status"],"PAID");self.assertEqual(by["c2"]["status"],"HEALTHY")
        self.assertNotIn("c1",[c for c in by if by[c]["status"]=="HEALTHY"])

    def test_all_paid_work_rejected_but_ledger_kept_for_recheck(self):
        manifest=make_manifest(self.base,"w1",("c1",),paid=("c1",))
        review=evaluate_work("w1","Work 1",manifest["chapters"],self.fetch,self.policy())
        self.assertEqual(review.verdict,"REJECTED");self.assertIn("no publicly available",review.reason)
        self.store.record_chapters("s1",review)
        self.assertEqual(self.store.content_chapters("s1","w1")[0]["status"],"PAID")
        self.assertEqual(self.store.content_work("s1","w1")["verdict"],"REJECTED")
        self.assertFalse(any(d["chapter_id"]=="c1" for d in self.store.due_chapters()))

    def test_gated_response_never_classified_as_paid(self):
        CState.gated={("w1","c2")}
        manifest=make_manifest(self.base,"w1",("c1","c2"))
        self.assertFalse(is_paid_chapter(manifest["chapters"][1],self.policy()))
        review=evaluate_work("w1","Work 1",manifest["chapters"],self.fetch,self.policy())
        self.assertEqual(review.chapters[1].status,ChapterStatus.UNAVAILABLE)

    def test_html_error_page_200_counts_as_parse_error(self):
        CState.html={("w1","c2")}
        manifest=make_manifest(self.base,"w1",("c1","c2"))
        review=evaluate_work("w1","Work 1",manifest["chapters"],self.fetch,self.policy())
        self.assertEqual(review.chapters[1].status,ChapterStatus.PARSE_ERROR)
        self.assertIn("content-type mismatch",review.chapters[1].reason)

    def test_preview_only_chapter_is_partial_not_healthy(self):
        manifest=make_manifest(self.base,"w1",("c1","c2"),preview=("c1",))
        review=evaluate_work("w1","Work 1",manifest["chapters"],self.fetch,self.policy())
        by={c.chapter_id:c for c in review.chapters}
        self.assertEqual(by["c1"].status,ChapterStatus.PARTIAL)
        self.assertIn("preview pages verified",by["c1"].reason)
        self.assertIn("not asserted",by["c1"].reason)
        self.assertEqual(by["c2"].status,ChapterStatus.HEALTHY)
        self.assertEqual(review.verdict,"ACCEPTED")
        self.assertEqual(review.totals.get("PARTIAL"),1)
        self.assertEqual(review.totals.get("HEALTHY"),1)
        self.store.record_chapters("s1",review)
        rows={r["chapter_id"]:r for r in self.store.content_chapters("s1","w1")}
        self.assertEqual(rows["c1"]["status"],"PARTIAL")
        self.assertEqual(self.store.content_work("s1","w1")["verdict"],"ACCEPTED")

    def test_preview_only_broken_page_counts_as_damage_not_partial(self):
        CState.stale={("w1","c1")}
        manifest=make_manifest(self.base,"w1",("c1",),preview=("c1",))
        review=evaluate_work("w1","Work 1",manifest["chapters"],self.fetch,self.policy())
        self.assertEqual(review.chapters[0].status,ChapterStatus.STALE_LINK)
        self.assertEqual(review.verdict,"PENDING")

    def test_all_partial_chapters_keep_work_accepted(self):
        manifest=make_manifest(self.base,"w1",("c1","c2"),preview=("c1","c2"))
        review=evaluate_work("w1","Work 1",manifest["chapters"],self.fetch,self.policy())
        self.assertEqual(review.verdict,"ACCEPTED")
        self.assertIn("partial",review.reason)
        self.assertEqual(review.totals.get("HEALTHY",0),0)
        self.assertEqual(review.totals.get("PARTIAL"),2)
        self.store.record_chapters("s1",review)
        self.assertEqual(self.store.content_work("s1","w1")["verdict"],"ACCEPTED")
        self.assertFalse(self.store.due_chapters())

    def test_full_manifest_without_marker_stays_healthy(self):
        manifest=make_manifest(self.base,"w1",("c1","c2"))
        review=evaluate_work("w1","Work 1",manifest["chapters"],self.fetch,self.policy())
        self.assertEqual({c.chapter_id:c.status for c in review.chapters},{"c1":ChapterStatus.HEALTHY,"c2":ChapterStatus.HEALTHY})
        self.assertEqual(review.totals.get("PARTIAL",0),0)

    def test_unresolvable_page_url_fails_closed_never_partial_or_healthy(self):
        # A Pro-chan virtual protected URL (__protected_page__#...) is not a direct image: it is only
        # materialized at runtime by the extension's proxy-plan reconstructor. If such an unusable link
        # ever entered a manifest, the chapter must fail closed - never HEALTHY nor PARTIAL.
        base=self.base
        chapter={"chapter_id":"c1","previewOnly":True,
                 "pages":[f"{base}/pages/w1/c1/p0",f"{base}/broken/w1/c1"]}
        review=evaluate_work("w1","Work 1",[chapter],self.fetch,self.policy())
        self.assertEqual(review.chapters[0].status,ChapterStatus.STALE_LINK)
        self.assertIn("404",review.chapters[0].reason)
        self.assertEqual(review.totals.get("HEALTHY",0),0)
        self.assertEqual(review.totals.get("PARTIAL",0),0)
        self.assertEqual(review.totals.get("STALE_LINK"),1)

    def test_published_repository_never_rewrites_or_leaks_page_urls(self):
        sha256=__import__("hashlib").sha256
        s=self._source_with_review();self.store.add_source(s)
        manifest=make_manifest(self.base,"w1",("c1","c2"))
        c=self._candidate(manifest)
        processor=self._processor()
        result=processor.process(c)
        self.assertEqual(result["verdict"],"ACCEPTED",result)
        self.store.accept_pending("s1",c.identity)
        root=Path(self.tmp.name)/"repo"
        RepositoryPublisher(self.store,root).publish(release=True)
        entries=json.loads((root/"index.json").read_text())
        self.assertEqual(len(entries),1)
        e=entries[0]
        self.assertEqual(sorted(e.keys()),["apk","code","lang","name","nsfw","pkg","sources","version"])
        apk_name=e["apk"]
        published=(root/"apk"/apk_name).read_bytes()
        artifact=c.metadata["_artifact_path"]
        self.assertEqual(published,Path(artifact).read_bytes())
        self.assertEqual(sha256(published).hexdigest(),sha256(Path(artifact).read_bytes()).hexdigest())
        for f in ("index.json","index.min.json","index.shura.json","repo.json"):
            self.assertNotIn("/pages/",(root/f).read_text())
            self.assertNotIn(MANIFEST_KEY,(root/f).read_text())

    def _source_with_review(self,sid="s1",manifest_url=None):
        cfg={"allowed_hosts":["127.0.0.1"],"signing_key":"a"*64,"content_policy":self.policy()}
        if manifest_url:cfg["content_policy"]["manifest_url"]=manifest_url
        return Source(sid,"review",self.base+"/apk","html","ar",configuration=cfg)

    def _candidate(self,manifest,sid="s1"):
        source=self.store.get_source(sid)
        return Candidate(sid,"org.example.ext","9.9",self.base+"/apk",name=str(manifest.get("work_name") or manifest.get("work_id") or "x"),language="ar",provenance={"page":"x"},metadata={MANIFEST_KEY:manifest})
    def _processor(self):
        from shura_core.security.network import validate_url
        return CandidateProcessor(self.store,artifact_dir=str(Path(self.tmp.name)/"artifacts"),scanner=CleanScanner(),malware_scanner=CleanScanner(),expected_hosts={"127.0.0.1"},http_factory=self.http_factory(),url_validator=lambda url,hosts: validate_url(url,hosts,https_only=False,allow_nonstandard_ports=True,allow_private_hosts=True))

    def test_processor_missing_manifest_is_pending_not_rejected(self):
        s=self._source_with_review();self.store.add_source(s)
        c=self._candidate({});del c.metadata[MANIFEST_KEY]
        result=self._processor().process(c)
        self.assertEqual(result["verdict"],"PENDING");self.assertIn("no content manifest",result["reason"])
        self.assertEqual(self.store.pending()[0]["stage"],"content-review-required")
        self.assertFalse(self.store.already_processed("s1",c.identity,"fp"))

    def test_processor_accepts_work_and_publishes_despite_problems(self):
        CState.stale={("w1","c3")}
        s=self._source_with_review();self.store.add_source(s)
        c=self._candidate(make_manifest(self.base,"w1",("c1","c2","c3"),paid=("c1",)))
        processor=self._processor()
        result=processor.process(c)
        self.assertEqual(result["verdict"],"ACCEPTED",result)
        self.store.accept_pending("s1",c.identity)
        pub=RepositoryPublisher(self.store,Path(self.tmp.name)/"repo").publish(release=True)
        self.assertEqual(pub["published"],1)
        by={r["chapter_id"]:r for r in self.store.content_chapters("s1","w1")}
        self.assertEqual(by["c1"]["status"],"PAID");self.assertEqual(by["c2"]["status"],"HEALTHY");self.assertEqual(by["c3"]["status"],"STALE_LINK")

    def test_processor_temporary_only_work_pending_and_retryable(self):
        CState.transient={("w1","c2")}
        manifest=make_manifest(self.base,"w1",("c1","c2"),paid=("c1",))
        c=Candidate("s1","org.example.ext","9.9",self.base+"/apk",name="x",language="ar",provenance={"page":"x"},metadata={MANIFEST_KEY:manifest})
        self.store.add_source(self._source_with_review())
        result=self._processor().process(c)
        self.assertEqual(result["verdict"],"PENDING",result)
        self.assertEqual(self.store.pending()[0]["stage"],"content-review")
        self.assertFalse(self.store.already_processed("s1",c.identity,"fp"))
        self.store.close();self.store=StateStore(Path(self.tmp.name)/"s.db")
        self.assertEqual([d["chapter_id"] for d in self.store.due_chapters()],["c2"])

    def test_processor_rejects_only_when_no_public_content_left(self):
        manifest=make_manifest(self.base,"w1",("c1",),paid=("c1",))
        c=self._candidate(manifest)
        self.store.add_source(self._source_with_review())
        result=self._processor().process(c)
        self.assertEqual(result["verdict"],"REJECTED");self.assertIn("content:",result["reason"])
        by={r["chapter_id"]:r for r in self.store.content_chapters("s1","w1")}
        self.assertEqual(by["c1"]["status"],"PAID")
        self.assertFalse(self.store.already_processed("s1",c.identity,"fp"))

    def test_content_disabled_behaves_exactly_as_before(self):
        s=Source("s0","plain",self.base+"/apk","html","ar",configuration={"allowed_hosts":["127.0.0.1"],"signing_key":"a"*64})
        self.store.add_source(s)
        c=Candidate("s0","org.example.ext","9.9",self.base+"/apk",name="x",language="ar",provenance={"page":"x"},metadata={MANIFEST_KEY:make_manifest(self.base)})
        result=self._processor().process(c)
        self.assertEqual(result["verdict"],"ACCEPTED",result)
        self.assertFalse(self.store.content_works())

    def test_rechecker_requires_manifest_and_ignores_unknown_work(self):
        CState.stale={("w1","c2")}
        manifest=make_manifest(self.base,"w1",("c2",))
        MANIFESTS["w1"]=manifest
        s=self._source_with_review(manifest_url=self.base+"/manifest/w1.json")
        self.store.add_source(s)
        review=evaluate_work("w1","Work 1",manifest["chapters"],self.fetch,self.policy())
        self.store.record_chapters("s1",review)
        CState.stale.clear()
        result=Scheduler(self.store).recheck_due_chapters(ManifestRechecker(self.store,http_factory=self.http_factory()),limit=10)
        self.assertEqual(result["chapter_rechecks"],1);self.assertEqual(result["changed"],1)
        self.assertEqual(self.store.content_chapters("s1","w1")[0]["status"],"HEALTHY")

if __name__=="__main__":unittest.main()