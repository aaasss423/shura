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

    def serve(self,coordinator,interval_seconds=900,stop_event=None,rechecker=None,recheck_limit=250):
        """Run due-source scans until stopped. Persistent timestamps prevent unnecessary polling.
        If a ContentRechecker is supplied, due retryable chapters are re-resolved on every pass."""
        while stop_event is None or not stop_event.is_set():
            due=self.due(limit=coordinator.max_sources)
            if due:coordinator.run({s.source_id for s in due})
            if rechecker is not None:
                self.recheck_due_chapters(rechecker,limit=recheck_limit)
            if stop_event is not None:
                stop_event.wait(max(1,interval_seconds))
            else:time.sleep(max(1,interval_seconds))

    def recheck_due_chapters(self,rechecker,limit=250,at=None):
        """Driver for chapter-level retry: every due retryable chapter is re-resolved through the
        generic protocol. A chapter that heals is recorded HEALTHY and its source is woken so the
        normal crawl cycle re-reviews the work (and publishes since it now has a healthy chapter).
        A genuinely unavailable chapter is recorded as such; the work keeps its healthy chapters."""
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
                self.store.record_attempt(ch["source_id"],True,"chapter recheck healed")
        return {"chapter_rechecks":len(due),"changed":changed,"awake_sources":sorted(awake)}
