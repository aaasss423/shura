from datetime import datetime,timezone,timedelta
from shura_core.models import SourceState,ChapterStatus
import time
class Scheduler:
    def __init__(self,store):self.store=store
    def due(self,at=None,limit=250):
        at=at or datetime.now(timezone.utc)
        return [s for s in self.store.sources() if self.store.eligible(s,at)][:limit]
    def retry(self,sid,resume_dead=False):
        s=self.store.get_source(sid)
        if not s:raise KeyError(sid)
        if s.state==SourceState.QUARANTINED:raise ValueError("quarantine requires explicit review and cannot be bypassed")
        if s.state==SourceState.DEAD:
            if not resume_dead:raise ValueError("DEAD source requires --resume-dead")
            self.store.transition(sid,SourceState.ACTIVE,"operator resumed dead source");self.store.event(sid,"retry_reset",{"reason":"operator resumed dead source"})
        elif s.state in (SourceState.PAUSED,SourceState.RETRY_LATER):self.store.transition(sid,SourceState.ACTIVE,"manual retry")

    def serve(self,coordinator,interval_seconds=900,stop_event=None,rechecker=None,recheck_limit=250,processor=None,max_downloads=250,notifier=None):
        """Run due-source scans until stopped. Persistent timestamps prevent unnecessary polling.

        With a CandidateProcessor supplied, every candidate the crawl discovers is driven
        through validate -> artifact download -> security/malware -> quality/content checks in
        the same pass, so a long-running daemon no longer strands work in "discovered".
        Acceptance and publication remain explicit operator steps and are never taken here.
        If a ContentRechecker is supplied, due retryable chapters are re-resolved on every pass."""
        while stop_event is None or not stop_event.is_set():
            due=self.due(limit=coordinator.max_sources)
            candidates=[]
            if due:candidates,_=coordinator.run({s.source_id for s in due})
            if processor is not None and candidates:
                self.process_candidates(processor,candidates,limit=max_downloads,notifier=notifier)
            if rechecker is not None:
                self.recheck_due_chapters(rechecker,limit=recheck_limit)
            if stop_event is not None:
                stop_event.wait(max(1,interval_seconds))
            else:time.sleep(max(1,interval_seconds))

    def process_candidates(self,processor,candidates,limit=250,notifier=None):
        """Drive freshly discovered candidates through the pipeline.

        Each candidate is processed inside its own try/except: one artifact that blows up must
        not abort the pass, and the rest of the batch must still be processed and accounted
        for. ``limit`` caps how many are attempted per pass so a large discovery cannot run
        unbounded, and the remainder is reported rather than silently dropped.
        """
        budget=max(0,min(int(limit),250))
        tally={"ACCEPTED":0,"REJECTED":0,"PENDING":0,"QUARANTINED":0,"error":0,"budgetSkipped":0}
        attempted=0
        for c in candidates:
            if attempted>=budget:
                tally["budgetSkipped"]+=1;continue
            attempted+=1
            try:
                result=processor.process(c)
            except Exception as e:  # noqa: BLE001 - one bad artifact must not sink the pass
                tally["error"]+=1
                self.store.event(c.source_id,"candidate_pipeline_error",{"identity":c.identity,"reason":repr(e)})
                continue
            verdict=str(result.get("verdict","PENDING"))
            tally[verdict]=tally.get(verdict,0)+1
            self.store.event(c.source_id,"candidate_"+verdict.lower(),{"identity":c.identity,"reason":result.get("reason","")})
            if notifier and verdict in ("QUARANTINED","ACCEPTED"):
                prefix="Shura security quarantine" if verdict=="QUARANTINED" else "Shura candidate ready for review"
                notifier.send(f"{prefix}: {c.identity}: {result.get('reason','')}")
        return tally

    def recheck_due_chapters(self,rechecker,limit=250,at=None):
        """Driver for chapter-level retry: every due retryable chapter is re-resolved through the
        generic protocol. A chapter that heals is recorded HEALTHY so the work rolls up to ACCEPTED.

        Healing a chapter is deliberately *not* reported through ``record_attempt(True)``. That
        call logs a ``crawl_success`` the source never produced, and the failure ladder in
        ``StateStore.record_attempt`` counts failures since the last success -- so a chronic
        crawler whose chapters kept healing would reset its own failure window on every pass,
        never reach DEAD, skip its backoff entirely and be polled continuously. The heal is
        recorded as its own event instead, and a source in backoff stays in backoff.
        """
        if not hasattr(rechecker,"recheck") and not callable(rechecker):
            raise TypeError("rechecker must be a ContentRechecker (object with recheck(chapter_record) -> ChapterRecord)")
        due=self.store.due_chapters(at=at,limit=limit)
        changed=0;awake=set()
        for ch in due:
            rid=f"{ch['source_id']}|{ch['work_id']}|{ch['chapter_id']}"
            source=self.store.get_source(ch["source_id"])
            policy=(source.configuration or {}).get("content_policy",{}) if source else {}
            review=rechecker.recheck(ch,policy)
            if review is None:continue
            attempts=ch["attempts"]+1
            self.store.update_chapter(ch["source_id"],ch["work_id"],ch["chapter_id"],review.status,review.reason,review.pages,attempts)
            self.store.event(ch["source_id"],"chapter_recheck",{"identity":rid,"status":review.status.value,"reason":review.reason,"attempt":attempts})
            if review.status==ChapterStatus.HEALTHY and source:
                changed+=1;awake.add(ch["source_id"])
                self.store.event(ch["source_id"],"chapter_recheck_healed",{"identity":rid,"attempts":attempts})
        return {"chapter_rechecks":len(due),"changed":changed,"healed_sources":sorted(awake)}
