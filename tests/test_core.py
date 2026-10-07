import json, tempfile, threading, unittest, io, zipfile, os, stat
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import time
from pathlib import Path
from datetime import datetime,timezone,timedelta
from shura_core.models import Source,Candidate,SourceState
from shura_core.state import StateStore
from shura_core.security.network import SafeHTTP,NetworkPolicyError
from shura_core.crawler import IndexCrawler,HtmlListingCrawler,GitHubReleasesCrawler
from shura_core.scheduling import Scheduler
from shura_core.publishing.repository import RepositoryPublisher
from shura_core.pipeline import CandidateProcessor
from shura_core.coordinator import CrawlCoordinator
from shura_core.quality.ranking import source_quality
from shura_core.sources import SourceRegistry,SourceConfigurationError
from shura_core.cli import main as cli_main
from contextlib import redirect_stdout,redirect_stderr
from shura_core.discovery import SourceDiscovery
from shura_core.notifications.telegram import TelegramNotifier
from shura_core.security.artifacts import ArtifactScanner,ScanVerdict
from unittest.mock import patch

APK_BODY=b""
class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path=="/index-redirect":self.send_response(302);self.send_header("Location","/index");self.end_headers();return
        elif self.path=="/index":body=json.dumps({"extensions":[{"pkg":"org.example.ext","version":"1.2","apk":"http://127.0.0.1:1/x.apk"}]}).encode();kind="application/json"
        elif self.path=="/releases":body=json.dumps([{"tag_name":"v2.1","assets":[{"name":"source.apk","browser_download_url":"https://github.com/org/repo/releases/download/v2.1/source.apk","url":"https://api.github.com/assets/1"}]}]).encode();kind="application/json"
        elif self.path=="/list":body=b'<html lang="ar"><a href="/x.apk" class="shura-extension" data-shura-package="org.example.ext" data-shura-version="1.2">Arabic</a><a rel="next" href="/next">Next</a></html>';kind="text/html"
        elif self.path=="/next":body=b'<html><a rel="next" href="/list">Loop</a></html>';kind="text/html"
        elif self.path in ("/fixture.apk","/x.apk"):body=APK_BODY;kind="application/vnd.android.package-archive"
        elif self.path=="/large":body=b'x'*100;kind="application/octet-stream"
        elif self.path=="/offsite":self.send_response(302);self.send_header("Location","https://evil.invalid/redirected");self.end_headers();return
        elif self.path=="/slow":time.sleep(.3);body=b"ok";kind="text/plain"
        else:body=b'{}';kind="application/json"
        self.send_response(200);self.send_header("Content-Type",kind);self.send_header("Content-Length",str(len(body)));self.end_headers()
        try:self.wfile.write(body)
        except BrokenPipeError:pass
    def log_message(self,*a):pass
class CoreTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server=ThreadingHTTPServer(("127.0.0.1",0),Handler);cls.port=cls.server.server_port;cls.thread=threading.Thread(target=cls.server.serve_forever,daemon=True);cls.thread.start();cls.base=f"http://127.0.0.1:{cls.port}"
    @classmethod
    def tearDownClass(cls):cls.server.shutdown();cls.thread.join()
    def setUp(self):self.tmp=tempfile.TemporaryDirectory();self.store=StateStore(Path(self.tmp.name)/"s.db");self.source=Source("s1","fixture",self.base+"/list","html","ar",configuration={"allowed_hosts":["127.0.0.1"],"signing_key":"public-test-fingerprint"});self.store.add_source(self.source)
    def tearDown(self):self.store.close();self.tmp.cleanup()
    def test_state_persists_and_paused_is_not_eligible(self):
        self.store.transition("s1",SourceState.PAUSED,"operator");self.assertFalse(self.store.eligible(self.store.get_source("s1")))
        self.store.close();self.store=StateStore(Path(self.tmp.name)/"s.db");self.assertEqual(self.store.get_source("s1").state,SourceState.PAUSED)
    def test_dead_and_quarantine_cannot_manual_retry(self):
        self.store.transition("s1",SourceState.DEAD,"broken")
        with self.assertRaises(ValueError):Scheduler(self.store).retry("s1")
    def test_retry_after_six_hours(self):
        self.store.record_attempt("s1",False,"timeout");s=self.store.get_source("s1");self.assertFalse(self.store.eligible(s));self.assertTrue(self.store.eligible(s,datetime.now(timezone.utc)+timedelta(hours=7)))
        self.store.record_attempt("s1",False,"timeout");self.store.record_attempt("s1",False,"timeout");s=self.store.get_source("s1");self.assertFalse(self.store.eligible(s,datetime.now(timezone.utc)+timedelta(hours=7)));self.assertTrue(self.store.eligible(s,datetime.now(timezone.utc)+timedelta(hours=25)))
    def test_guarded_transport_and_size(self):
        http=SafeHTTP(["127.0.0.1"],max_bytes=10,allow_private_hosts=True)
        with self.assertRaises(NetworkPolicyError):http.get("http://127.0.0.1/large")
        with self.assertRaises(NetworkPolicyError):http.get("http://127.0.0.1/large",max_bytes=1000)
        with self.assertRaises(NetworkPolicyError):http.get("http://evil.invalid/")
    def test_index_and_github_parsing(self):
        http=SafeHTTP(["127.0.0.1"],https_only=False,allow_nonstandard_ports=True,allow_private_hosts=True)
        s=Source("idx","idx",self.base+"/index","index",configuration={"allowed_hosts":["127.0.0.1"],"signing_key":"public-test-fingerprint"})
        self.assertEqual(IndexCrawler(http).crawl(s).candidates[0].identity,"org.example.ext|1.2")
        redirected=Source("redir","redir",self.base+"/index-redirect","index")
        self.assertEqual(IndexCrawler(http).crawl(redirected).trace[0]["final_url"],self.base+"/index")
        api=Source("gh","gh",self.base+"/releases","github-releases",configuration={"package":"org.example.ext"})
        release=GitHubReleasesCrawler(http).crawl(api)
        self.assertEqual(release.candidates[0].identity,"org.example.ext|v2.1")
        self.assertEqual(release.candidates[0].provenance["release_api"],self.base+"/releases")
    def test_html_pagination_and_loop(self):
        http=SafeHTTP(["127.0.0.1"],https_only=False,allow_nonstandard_ports=True,allow_private_hosts=True);out=HtmlListingCrawler(http,5).crawl(self.source)
        self.assertEqual(len(out.candidates),1);self.assertEqual(out.pages,2)
    def test_pending_stage_does_not_publish_and_noop_safe(self):
        c=Candidate("s1","org.example.ext","1.0","https://127.0.0.1/x.apk",name="x",language="ar",provenance={"page":"x"});self.store.put_pending(c,"validated")
        pub=RepositoryPublisher(self.store,Path(self.tmp.name)/"repo");r=pub.publish(stage=True);self.assertTrue(r["refused"]);self.assertFalse((Path(self.tmp.name)/"repo").exists())
        r=pub.publish(release=True);self.assertTrue(r["refused"])
    def test_only_security_passed_candidate_publishes(self):
        c=Candidate("s1","org.example.ext","1.0","https://example.org/x.apk",name="x",language="ar",provenance={"page":"x"},metadata={"_artifact_path":str(Path(self.tmp.name)/"x.apk")});Path(self.tmp.name,"x.apk").write_bytes(b"verified") ;self.store.put_pending(c,"security-passed");self.store.accept_pending(c.source_id,c.identity)
        r=RepositoryPublisher(self.store,Path(self.tmp.name)/"repo").publish(release=True);self.assertEqual(r["published"],1);self.assertTrue((Path(self.tmp.name)/"repo/index.json").exists());self.assertTrue((Path(self.tmp.name)/"repo/org_example_ext-1.0.apk").exists())
    def test_quarantine_record_and_forget_cannot_bypass(self):
        c=Candidate("s1","org.example.ext","bad","https://example.org/x.apk",provenance={"page":"x"});self.store.quarantine_item(c,"signature mismatch","deadbeef");self.assertEqual(self.store.get_source("s1").state,SourceState.QUARANTINED)
        with self.assertRaises(ValueError):Scheduler(self.store).retry("s1")
        self.store.forget_source("s1");self.assertTrue(self.store.is_quarantined("s1",c.identity))
        self.store.add_source(Source("s1","re-added",self.base+"/list","html",configuration={"allowed_hosts":["127.0.0.1"],"signing_key":"a"*64}))
        result=CandidateProcessor(self.store).process(c);self.assertEqual(result["verdict"],"QUARANTINED")
    def test_source_provenance_and_configuration_recheck(self):
        a=self.store.get_source("s1");self.store.mark_processed("s1","org.manga.one|1","fp-a","rejected")
        b=Source("s2","other",self.base+"/list","html",configuration={"allowed_hosts":["127.0.0.1"]});self.store.add_source(b)
        self.assertTrue(self.store.already_processed("s1","org.manga.one|1","fp-a"));self.assertFalse(self.store.already_processed("s2","org.manga.one|1","fp-a"))
        a.configuration["signing_key"]="fixed";a.configuration_fingerprint=a.fingerprint();self.store.add_source(a)
        self.assertFalse(self.store.already_processed("s1","org.manga.one|1",a.configuration_fingerprint))
    def test_redirect_timeout_and_malformed_apk(self):
        http=SafeHTTP(["127.0.0.1"],https_only=False,timeout=.05,allow_private_hosts=True)
        with self.assertRaises(Exception):http.get(self.base+"/offsite")
        with self.assertRaises(Exception):http.get(self.base+"/slow")
        bad=Path(self.tmp.name)/"bad.apk";bad.write_bytes(b"not an apk")
        self.assertEqual(ArtifactScanner().scan(bad).verdict,ScanVerdict.REJECTED)
    def test_cli_dead_retry_requires_explicit_confirmation(self):
        self.store.transition("s1",SourceState.DEAD,"broken")
        with self.assertRaises(ValueError):Scheduler(self.store).retry("s1")
        Scheduler(self.store).retry("s1",resume_dead=True);self.assertEqual(self.store.get_source("s1").state,SourceState.ACTIVE)
    def test_telegram_failure_is_isolated(self):
        notifier=TelegramNotifier("token","chat")
        with patch("urllib.request.urlopen",side_effect=OSError("offline")):self.assertFalse(notifier.send("test"))
        self.assertFalse(TelegramNotifier().send("disabled"))
        class Response:
            status=200
            def __enter__(self):return self
            def __exit__(self,*args):pass
            def read(self):return b'{"ok":true}'
        with patch("urllib.request.urlopen",return_value=Response()):self.assertTrue(notifier.send("delivered"))
    def test_discovery_prioritizes_arabic_and_persists_paused(self):
        class FakeHTTP:
            def __init__(self):self.calls=0
            def get(self,url,headers):
                self.calls+=1
                return json.dumps({"items":[{"full_name":f"org/repo{self.calls}","name":f"repo{self.calls}","html_url":f"https://github.com/org/repo{self.calls}"}]}).encode(),{},url
        discovered=SourceDiscovery(self.store,FakeHTTP(),daily_limit=2).discover()
        self.assertEqual(len(discovered),2);self.assertTrue(all(not x.enabled and x.state==SourceState.PAUSED for x in discovered));self.assertTrue(all(x.language=="ar" for x in discovered))

    def test_end_to_end_crawl_security_publish(self):
        global APK_BODY
        data=io.BytesIO()
        with zipfile.ZipFile(data,"w") as z:z.writestr("AndroidManifest.xml",b"manifest")
        APK_BODY=data.getvalue()
        source=Source("e2e","fixture",self.base+"/list","html","ar",configuration={"allowed_hosts":["127.0.0.1"],"signing_key":"a"*64})
        self.store.add_source(source)
        http_factory=lambda hosts,**kw:SafeHTTP(hosts,https_only=False,allow_nonstandard_ports=True,allow_private_hosts=True,**kw)
        class Recorder:
            def __init__(self):self.messages=[]
            def send(self,message):self.messages.append(message);return True
        recorder=Recorder()
        candidates,counters=CrawlCoordinator(self.store,http_factory=http_factory,notifier=recorder).run({"e2e"})
        self.assertEqual(len(candidates),1);self.assertEqual(counters["sourcesAccounted"],1);self.assertTrue(any("crawl success" in x for x in recorder.messages))
        tool=Path(self.tmp.name)/"apksigner";tool.write_text("#!/bin/sh\nprintf 'Signer #1 certificate SHA-256 digest: "+"a"*64+"\n'\n") ;tool.chmod(tool.stat().st_mode|stat.S_IXUSR)
        aapt=Path(self.tmp.name)/"aapt";aapt.write_text("#!/bin/sh\nprintf '%s\\n' \"package: name='org.example.ext' versionCode=1\"\n");aapt.chmod(aapt.stat().st_mode|stat.S_IXUSR)
        old_path=os.environ.get("PATH","");os.environ["PATH"]=str(self.tmp.name)+os.pathsep+old_path
        try:
            processor=CandidateProcessor(self.store,Path(self.tmp.name)/"artifacts",expected_hosts={"127.0.0.1"},http_factory=http_factory,url_validator=lambda url,hosts: __import__("shura_core.security.network",fromlist=["validate_url"]).validate_url(url,hosts,https_only=False,allow_nonstandard_ports=True,allow_private_hosts=True))
            result=processor.process(candidates[0]);self.assertEqual(result["verdict"],"ACCEPTED",result);self.assertGreaterEqual(result["quality_score"],0);self.store.accept_pending(candidates[0].source_id,candidates[0].identity)
        finally:os.environ["PATH"]=old_path
        published=RepositoryPublisher(self.store,Path(self.tmp.name)/"repository").publish(release=True)
        self.assertEqual(published["published"],1);self.assertEqual(published["published_this_run"],1);self.assertTrue(published["publication_charged"]);self.assertEqual(published["publications_today"],1)
        repo=json.loads((Path(self.tmp.name)/"repository/index.json").read_text());self.assertTrue(repo["packages"][0]["apk"])
        noop=RepositoryPublisher(self.store,Path(self.tmp.name)/"repository").publish(release=True);self.assertTrue(noop["noop"]);self.assertEqual(len(json.loads((Path(self.tmp.name)/"repository/index.json").read_text())["packages"]),1)

    def test_publisher_refuses_path_traversal(self):
        c=Candidate("s1","org.example.ext","../escape","https://example.org/x.apk",name="x",language="ar",provenance={"page":"x"},metadata={"_artifact_path":str(Path(self.tmp.name)/"x.apk")})
        Path(self.tmp.name,"x.apk").write_bytes(b"x");self.store.put_pending(c,"security-passed");self.store.accept_pending(c.source_id,c.identity)
        with self.assertRaises(Exception):RepositoryPublisher(self.store,Path(self.tmp.name)/"repo").publish(release=True)
        self.assertTrue(self.store.has_pending_for(c.source_id,c.identity))
    def test_publisher_rolls_back_files_when_ledger_fails(self):
        root=Path(self.tmp.name)/"repo";root.mkdir();(root/"index.json").write_text("old")
        artifact=Path(self.tmp.name)/"x.apk";artifact.write_bytes(b"apk")
        c=Candidate("s1","org.example.ext","1.0","https://example.org/x.apk",name="x",language="ar",provenance={"page":"x"},metadata={"_artifact_path":str(artifact)})
        self.store.put_pending(c,"security-passed");self.store.accept_pending(c.source_id,c.identity)
        original=self.store.publish
        def fail(_):raise RuntimeError("ledger unavailable")
        self.store.publish=fail
        try:
            with self.assertRaises(RuntimeError):RepositoryPublisher(self.store,root).publish(release=True)
        finally:self.store.publish=original
        self.assertEqual((root/"index.json").read_text(),"old");self.assertFalse((root/"org_example_ext-1.0.apk").exists());self.assertTrue(self.store.has_pending_for(c.source_id,c.identity))

    def test_config_reload_cannot_unpause_operator_stopped_source(self):
        self.store.transition("s1",SourceState.PAUSED,"operator stop")
        incoming=Source("s1","fixture updated",self.base+"/list","html","ar",configuration={"allowed_hosts":["127.0.0.1"],"signing_key":"b"*64})
        self.store.add_source(incoming)
        self.assertEqual(self.store.get_source("s1").state,SourceState.PAUSED)
        self.assertFalse(self.store.eligible(self.store.get_source("s1")))
    def test_long_broken_source_dead_and_explicit_resume_resets_counter(self):
        for _ in range(10):self.store.record_attempt("s1",False,"broken")
        self.assertEqual(self.store.get_source("s1").state,SourceState.DEAD)
        Scheduler(self.store).retry("s1",resume_dead=True)
        self.store.record_attempt("s1",False,"still broken")
        self.assertEqual(self.store.get_source("s1").state,SourceState.RETRY_LATER)

    def test_quality_prefers_arabic_and_security_quarantine_is_gate(self):
        ar=source_quality(language="ar",successes=10,attempts=10,valid=10,fresh_days=1)
        unknown=source_quality(language="und",successes=10,attempts=10,valid=10,fresh_days=1)
        quarantined=source_quality(language="ar",successes=10,attempts=10,valid=10,quarantined=1,fresh_days=1)
        self.assertGreater(ar,unknown);self.assertLess(quarantined,ar)
    def test_quarantine_blocks_re_registered_source(self):
        c=Candidate("s1","org.example.ext","9","https://example.org/a.apk",provenance={"page":"x"});self.store.quarantine_item(c,"signature mismatch","a"*64)
        self.store.forget_source("s1")
        self.store.add_source(Source("s1","again",self.base+"/list","html",configuration={"allowed_hosts":["127.0.0.1"],"signing_key":"c"*64}),update_state=True)
        self.assertEqual(self.store.get_source("s1").state,SourceState.QUARANTINED)

    def test_apk_package_and_certificate_mismatch_quarantine_verdict(self):
        data=io.BytesIO()
        with zipfile.ZipFile(data,"w") as z:z.writestr("AndroidManifest.xml",b"binary manifest fixture")
        apk=Path(self.tmp.name)/"candidate.apk";apk.write_bytes(data.getvalue())
        aapt=Path(self.tmp.name)/"aapt";aapt.write_text("#!/bin/sh\nprintf '%s\\n' \"package: name='org.example.ext' versionCode=1\"\n");aapt.chmod(aapt.stat().st_mode|stat.S_IXUSR)
        signer=Path(self.tmp.name)/"apksigner";signer.write_text("#!/bin/sh\nprintf 'Signer #1 certificate SHA-256 digest: "+"b"*64+"\n'\n");signer.chmod(signer.stat().st_mode|stat.S_IXUSR)
        old_path=os.environ.get("PATH","");os.environ["PATH"]=str(self.tmp.name)+os.pathsep+old_path
        try:
            scanner=ArtifactScanner(expected_certificate="a"*64)
            self.assertEqual(scanner.scan(apk,expected_package="org.other.ext").verdict,ScanVerdict.REJECTED)
            mismatch=scanner.scan(apk,expected_package="org.example.ext")
            self.assertEqual(mismatch.verdict,ScanVerdict.REJECTED);self.assertIn("certificate mismatch",mismatch.reason)
        finally:os.environ["PATH"]=old_path

    def test_source_registry_requires_allowlist_and_trusted_key_for_enabled_source(self):
        invalid=Source("invalid","Bad",self.base+"/list","html","ar",configuration={"allowed_hosts":["127.0.0.1"]})
        with self.assertRaises(SourceConfigurationError):SourceRegistry.validate(invalid)
        safe=Source("safe","Good","https://example.org/index.json","index","ar",configuration={"allowed_hosts":["example.org"],"signing_key":"d"*64})
        self.assertIs(SourceRegistry.validate(safe),safe)
    def test_observability_report_is_durable_shape(self):
        from shura_core.observability import source_report
        self.store.event("s1","discovered",{"identity":"org.manga.one|1"})
        report=source_report(self.store,"s1")
        self.assertEqual(report["discovered_count"],1);self.assertEqual(report["state"],"ACTIVE");self.assertIn("configuration_fingerprint",report)

    def test_quarantine_needs_explicit_audited_resolution(self):
        c=Candidate("s1","org.example.ext","12","https://example.org/a.apk",provenance={"page":"x"})
        self.store.quarantine_item(c,"signing mismatch","c"*64)
        with self.assertRaises(ValueError):self.store.review_quarantine("s1",c.identity,resolve=True)
        self.store.review_quarantine("s1",c.identity,reason="Reviewed signer and rotated trusted certificate",resolve=True)
        self.assertFalse(self.store.is_quarantined("s1",c.identity));self.assertEqual(self.store.get_source("s1").state,SourceState.ACTIVE)
        self.assertTrue(self.store.status("s1")["counts"].get("quarantine_review",0))

    def test_publication_duplicate_identity_hash_conflict_stays_pending(self):
        one=Candidate("s1","org.example.ext","1.0","https://example.org/a.apk",name="x",language="ar",provenance={"page":"a"},metadata={"_artifact_path":str(Path(self.tmp.name)/"a.apk"),"_security":{"sha256":"a"*64}})
        two=Candidate("s2","org.example.ext","1.0","https://example.org/b.apk",name="x",language="ar",provenance={"page":"b"},metadata={"_artifact_path":str(Path(self.tmp.name)/"b.apk"),"_security":{"sha256":"b"*64}})
        Path(self.tmp.name,"a.apk").write_bytes(b"a");Path(self.tmp.name,"b.apk").write_bytes(b"b")
        self.store.add_source(Source("s2","other",self.base+"/list","html",configuration={"allowed_hosts":["127.0.0.1"],"signing_key":"e"*64}))
        for c in (one,two):self.store.put_pending(c,"security-passed");self.store.accept_pending(c.source_id,c.identity)
        result=RepositoryPublisher(self.store,Path(self.tmp.name)/"repo").publish(release=True)
        self.assertTrue(result["refused"]);self.assertFalse((Path(self.tmp.name)/"repo/index.json").exists())
        self.assertTrue(self.store.has_pending_for("s1",one.identity));self.assertTrue(self.store.has_pending_for("s2",two.identity))

    def test_cli_config_stop_status_retry_real_path(self):
        config=Path(self.tmp.name)/"sources.json"
        config.write_text(json.dumps([{"source_id":"cli-source","name":"CLI fixture","url":"https://example.org/index.json","kind":"index","language":"ar","configuration":{"allowed_hosts":["example.org"],"signing_key":"f"*64}}]))
        db=Path(self.tmp.name)/"cli.db";out=io.StringIO()
        with redirect_stdout(out),redirect_stderr(io.StringIO()):self.assertEqual(cli_main(["--db",str(db),"configure",str(config)]),0)
        with redirect_stdout(out),redirect_stderr(io.StringIO()):self.assertEqual(cli_main(["--db",str(db),"stop","--source-id","cli-source","--reason","test"]),0)
        with redirect_stdout(out),redirect_stderr(io.StringIO()):self.assertEqual(cli_main(["--db",str(db),"status","--source-id","cli-source"]),0)
        self.assertIn('"state": "PAUSED"',out.getvalue())
        with redirect_stdout(out),redirect_stderr(io.StringIO()):self.assertEqual(cli_main(["--db",str(db),"retry","--source-id","cli-source"]),0)
    def test_cli_stage_refused_returns_nonzero_and_empty_is_success(self):
        db=Path(self.tmp.name)/"publish-cli.db";out=io.StringIO()
        with redirect_stdout(out),redirect_stderr(io.StringIO()):self.assertEqual(cli_main(["--db",str(db),"publish","--stage"]),0)
        store=StateStore(db);c=Candidate("s1","org.example.ext","2","https://example.org/x.apk",name="x",language="ar",provenance={"page":"x"});store.add_source(self.source);store.put_pending(c,"validated");store.close()
        with redirect_stdout(out),redirect_stderr(io.StringIO()):self.assertEqual(cli_main(["--db",str(db),"publish","--stage"]),3)
        self.assertIn('"refused": true',out.getvalue())

if __name__=="__main__":unittest.main()
