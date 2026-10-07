from datetime import datetime,timezone,timedelta
from shura_core.models import SourceState
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

    def serve(self,coordinator,interval_seconds=900,stop_event=None):
        """Run due-source scans until stopped. Persistent timestamps prevent unnecessary polling."""
        while stop_event is None or not stop_event.is_set():
            due=self.due(limit=coordinator.max_sources)
            if due:coordinator.run({s.source_id for s in due})
            if stop_event is not None:
                stop_event.wait(max(1,interval_seconds))
            else:time.sleep(max(1,interval_seconds))
