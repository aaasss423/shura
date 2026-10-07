import argparse,os,signal,threading
from shura_core.coordinator import CrawlCoordinator
from shura_core.notifications.telegram import TelegramNotifier
from shura_core.scheduling import Scheduler
from shura_core.state import StateStore

def main(argv=None):
    p=argparse.ArgumentParser(prog="shura-daemon");p.add_argument("--db",default=os.getenv("SHURA_STATE_DB","shura.db"));p.add_argument("--interval-seconds",type=int,default=900);p.add_argument("--max-sources",type=int,default=250);a=p.parse_args(argv)
    stop=threading.Event();signal.signal(signal.SIGTERM,lambda *_:stop.set());signal.signal(signal.SIGINT,lambda *_:stop.set())
    store=StateStore(a.db)
    try:
        notifier=TelegramNotifier(os.getenv("SHURA_TELEGRAM_TOKEN"),os.getenv("SHURA_TELEGRAM_CHAT_ID"));coordinator=CrawlCoordinator(store,max_sources=a.max_sources,notifier=notifier);Scheduler(store).serve(coordinator,a.interval_seconds,stop)
    finally:store.close()
if __name__=="__main__":main()
