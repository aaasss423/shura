import argparse,os,signal,sys,threading
from shura_core.coordinator import CrawlCoordinator
from shura_core.notifications.telegram import TelegramNotifier
from shura_core.pipeline import CandidateProcessor
from shura_core.pipeline.recheck import ManifestRechecker
from shura_core.scheduling import Scheduler
from shura_core.security.malware import MalwareScanner
from shura_core.state import StateStore

def main(argv=None):
    p=argparse.ArgumentParser(prog="shura-daemon");p.add_argument("--db",default=os.getenv("SHURA_STATE_DB","shura.db"));p.add_argument("--interval-seconds",type=int,default=900);p.add_argument("--max-sources",type=int,default=250);p.add_argument("--max-downloads",type=int,default=250);p.add_argument("--artifact-dir",default=os.getenv("SHURA_ARTIFACT_DIR","artifacts"));p.add_argument("--require-malware-scanner",action="store_true",help="exit instead of crawling when the malware engine cannot scan");a=p.parse_args(argv)
    stop=threading.Event();signal.signal(signal.SIGTERM,lambda *_:stop.set());signal.signal(signal.SIGINT,lambda *_:stop.set())
    store=StateStore(a.db)
    try:
        notifier=TelegramNotifier(os.getenv("SHURA_TELEGRAM_TOKEN"),os.getenv("SHURA_TELEGRAM_CHAT_ID"))
        # Preflight: without a working engine the pipeline defers every artifact as
        # security-blocked, so crawling would burn the run's budget for nothing. This
        # is reported loudly instead of failing one candidate at a time.
        scanner=MalwareScanner();status=scanner.preflight()
        if status.available:
            notifier.send(f"Shura malware preflight OK: {status.version or 'clamav'}, {status.signatures} signatures")
        else:
            notifier.send(f"Shura malware preflight FAILED: {status.reason}. Artifacts will be deferred as security-blocked, not published.")
            if a.require_malware_scanner:
                print(f"malware scanner unavailable: {status.reason}",file=sys.stderr);return 2
        coordinator=CrawlCoordinator(store,max_sources=a.max_sources,notifier=notifier)
        # The daemon drives the whole critical path: discovered candidates are validated,
        # downloaded, security/malware-scanned and content-reviewed in the same pass instead of
        # piling up in "discovered". Acceptance and publication stay explicit operator steps.
        processor=CandidateProcessor(store,artifact_dir=a.artifact_dir,malware_scanner=scanner);rechecker=ManifestRechecker(store)
        Scheduler(store).serve(coordinator,a.interval_seconds,stop,rechecker=rechecker,processor=processor,max_downloads=a.max_downloads,notifier=notifier)
    finally:store.close()
    return 0
if __name__=="__main__":raise SystemExit(main() or 0)