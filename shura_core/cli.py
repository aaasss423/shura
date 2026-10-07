from __future__ import annotations
import argparse,json,os,sys
from shura_core.models import Source,SourceState
from shura_core.state import StateStore
from shura_core.coordinator import CrawlCoordinator
from shura_core.pipeline import CandidateProcessor
from shura_core.publishing.repository import RepositoryPublisher,PublishRefused
from shura_core.notifications.telegram import TelegramNotifier
from shura_core.scheduling import Scheduler
from shura_core.discovery import SourceDiscovery
from shura_core.security.network import SafeHTTP
from shura_core.sources import SourceRegistry
from shura_core.observability import source_report

def main(argv=None):
 p=argparse.ArgumentParser(prog="shura");p.add_argument("--db",default=os.getenv("SHURA_STATE_DB","shura.db"));sub=p.add_subparsers(dest="command",required=True)
 q=sub.add_parser("configure");q.add_argument("file")
 q=sub.add_parser("crawl");q.add_argument("--source-id",action="append");q.add_argument("--config",default="sources.json");q.add_argument("--max-sources",type=int,default=250);q.add_argument("--max-downloads",type=int,default=250)
 q=sub.add_parser("publish");q.add_argument("--repo",default=os.getenv("SHURA_REPO_DIR","repo"));q.add_argument("--stage",action="store_true");q.add_argument("--release",action="store_true")
 q=sub.add_parser("discover");q.add_argument("--limit",type=int,default=250)
 for cmd in ("status","stop","retry","forget","accept","pending","review-quarantine","accept-source"):
  q=sub.add_parser(cmd);q.add_argument("source_id",nargs="?");q.add_argument("--source-id",dest="source_id_option");
  if cmd=="stop":q.add_argument("--reason",required=True)
  if cmd in ("accept","review-quarantine"):q.add_argument("--identity",required=True)
  if cmd=="forget":q.add_argument("--identity")
  if cmd=="review-quarantine":q.add_argument("--reason",default="");q.add_argument("--resolve",action="store_true")
  if cmd=="retry":q.add_argument("--resume-dead",action="store_true")
  if cmd=="accept-source":q.add_argument("--reason",default="")
 args=p.parse_args(argv);store=StateStore(args.db)
 if args.command in ("status","stop","retry","forget","accept","review-quarantine","accept-source"):
  args.source_id=args.source_id_option or args.source_id
  if not args.source_id and args.command!="pending":p.error(f"{args.command} requires SOURCE_ID or --source-id")
 try:
  if args.command=="configure":
   sources=SourceRegistry.parse_file(args.file)
   for source in sources:store.add_source(source)
   print(f"configured {len(sources)} sources");return 0
  elif args.command=="crawl":
   if os.path.exists(args.config):
    for source in SourceRegistry.parse_file(args.config):store.add_source(source)
   notifier=TelegramNotifier(os.getenv("SHURA_TELEGRAM_TOKEN"),os.getenv("SHURA_TELEGRAM_CHAT_ID"));coord=CrawlCoordinator(store,args.max_sources,notifier=notifier)
   candidates,counters=coord.run({sid for item in args.source_id or [] for sid in item.split(",") if sid} or None);proc=CandidateProcessor(store)
   notifier=TelegramNotifier(os.getenv("SHURA_TELEGRAM_TOKEN"),os.getenv("SHURA_TELEGRAM_CHAT_ID"));downloads=0
   for c in candidates:
    if downloads>=min(max(args.max_downloads,0),250):
     counters["downloadsBudgetSkipped"]=counters.get("downloadsBudgetSkipped",0)+1;continue
    downloads+=1;counters["downloadsAttempted"]=downloads
    result=proc.process(c);store.event(c.source_id,"candidate_"+result["verdict"].lower(),{"identity":c.identity,"reason":result["reason"]})
    if result["verdict"]=="QUARANTINED":notifier.send(f"Shura security quarantine: {c.identity}: {result['reason']}")
    if result["verdict"]=="ACCEPTED":notifier.send(f"Shura candidate ready for review: {c.identity}")
    print(json.dumps({"identity":c.identity,"source_id":c.source_id,"processing":result},ensure_ascii=False))
   print(json.dumps(counters,ensure_ascii=False));return 0 if counters.get("sourcesFailed",0)==0 else 2
  elif args.command=="discover":
   proposals=SourceDiscovery(store,SafeHTTP(["api.github.com"],timeout=15,max_bytes=2_000_000),os.getenv("GITHUB_TOKEN"),daily_limit=args.limit).discover()
   notifier=TelegramNotifier(os.getenv("SHURA_TELEGRAM_TOKEN"),os.getenv("SHURA_TELEGRAM_CHAT_ID"))
   for proposal in proposals:notifier.send(f"Shura source proposal discovered: {proposal.name} ({proposal.language}); operator review required")
   print(json.dumps({"discovered":len(proposals),"sources":[{"source_id":x.source_id,"name":x.name,"language":x.language,"state":x.state.value,"enabled":x.enabled} for x in proposals]},ensure_ascii=False));return 0
  elif args.command=="publish":
   result=RepositoryPublisher(store,args.repo).publish(args.stage,args.release);print(json.dumps(result))
   if result.get("published"):TelegramNotifier(os.getenv("SHURA_TELEGRAM_TOKEN"),os.getenv("SHURA_TELEGRAM_CHAT_ID")).send(f"Shura publication succeeded: {result['published']} entries")
   return 3 if result.get("refused") else 0
  elif args.command=="status":
   data=source_report(store,args.source_id)
   if not data:print("source not found",file=sys.stderr);return 2
   print(json.dumps(data,ensure_ascii=False));return 0
  elif args.command=="stop":
   store.transition(args.source_id,SourceState.PAUSED,args.reason);TelegramNotifier(os.getenv("SHURA_TELEGRAM_TOKEN"),os.getenv("SHURA_TELEGRAM_CHAT_ID")).send(f"Shura source stopped: {args.source_id}: {args.reason}");return 0
  elif args.command=="retry":Scheduler(store).retry(args.source_id,args.resume_dead);return 0
  elif args.command=="forget":
   if args.identity:
    store.event(args.source_id,"candidate_forgotten",{"identity":args.identity,"operator":"local-operator"});store.forget_pending(args.source_id,args.identity)
   else:store.forget_source(args.source_id)
   return 0
  elif args.command=="accept-source":
   store.accept_source(args.source_id,reason=args.reason);TelegramNotifier(os.getenv("SHURA_TELEGRAM_TOKEN"),os.getenv("SHURA_TELEGRAM_CHAT_ID")).send(f"Shura source accepted: {args.source_id}");return 0
  elif args.command=="accept":store.accept_pending(args.source_id,args.identity);return 0
  elif args.command=="review-quarantine":store.review_quarantine(args.source_id,args.identity,reason=args.reason,resolve=args.resolve);return 0
  elif args.command=="pending":
   rows=store.pending()
   if args.source_id:rows=[x for x in rows if x["source_id"]==args.source_id]
   print(json.dumps(rows,ensure_ascii=False));return 0
 except (ValueError,KeyError,PublishRefused) as e:print(str(e),file=sys.stderr);return 2
 except Exception as e:
  message=f"critical {type(e).__name__}: {e}";print(message,file=sys.stderr)
  TelegramNotifier(os.getenv("SHURA_TELEGRAM_TOKEN"),os.getenv("SHURA_TELEGRAM_CHAT_ID")).send("Shura critical error: "+message)
  return 1
 finally:store.close()
if __name__=="__main__":raise SystemExit(main())
