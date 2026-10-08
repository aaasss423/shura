import argparse,os,signal,threading
from shura_core.coordinator import CrawlCoordinator
from shura_core.notifications.telegram import TelegramNotifier
from shura_core.pipeline import CandidateProcessor
from shura_core.pipeline.recheck import ManifestRechecker
from shura_core.scheduling import Scheduler
from shura_core.state import StateStore

def main(argv=None):
    p=argparse.ArgumentParser(prog="shura-daemon");p.add_argument("--db",default=os.getenv("SHURA_STATE_DB","shura.db"));p.add_argument("--interval-seconds",type=int,default=900);p.add_argument("--max-sources",type=int,default=250);p.add_argument("--max-downloads",type=int,default=250);p.add_argument("--artifact-dir",default=os.getenv("SHURA_ARTIFACT_DIR","artifacts"));a=p.parse_args(argv)
    stop=threading.Event();signal.signal(signal.SIGTERM,lambda *_:stop.set());signal.signal(signal.SIGINT,lambda *_:stop.set())
    store=StateStore(a.db)
    try:
        notifier=TelegramNotifier(os.getenv("SHURA_TELEGRAM_TOKEN"),os.getenv("SHURA_TELEGRAM_CHAT_ID"));coordinator=CrawlCoordinator(store,max_sources=a.max_sources,notifier=notifier)
        # The daemon drives the whole critical path: discovered candidates are validated,
        # downloaded, security/malware-scanned and content-reviewed in the same pass instead of
        # piling up in "discovered". Acceptance and publication stay explicit operator steps.
        processor=CandidateProcessor(store,artifact_dir=a.artifact_dir);rechecker=ManifestRechecker(store)
        Scheduler(store).serve(coordinator,a.interval_seconds,stop,rechecker=rechecker,processor=processor,max_downloads=a.max_downloads,notifier=notifier)
    finally:store.close()
if __name__=="__main__":main()