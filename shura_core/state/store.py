from __future__ import annotations
import json, sqlite3, uuid
from pathlib import Path
from datetime import datetime, timezone, timedelta
from contextlib import contextmanager
from shura_core.models import Source, SourceState, now

class StateStore:
    """SQLite state store with schema versioning and atomic transitions."""
    def __init__(self, path: str | Path):
        self.path=str(path); self.db=sqlite3.connect(self.path,timeout=30,isolation_level=None); self.db.row_factory=sqlite3.Row
        self.db.execute("PRAGMA foreign_keys=ON"); self.db.execute("PRAGMA journal_mode=WAL"); self._migrate()
    @contextmanager
    def tx(self):
        self.db.execute("BEGIN IMMEDIATE")
        try: yield self.db; self.db.execute("COMMIT")
        except Exception: self.db.execute("ROLLBACK"); raise
    def _migrate(self):
        self.db.executescript('''CREATE TABLE IF NOT EXISTS schema_version(version INTEGER NOT NULL); INSERT INTO schema_version SELECT 1 WHERE NOT EXISTS(SELECT 1 FROM schema_version);
        CREATE TABLE IF NOT EXISTS sources(source_id TEXT PRIMARY KEY,data TEXT NOT NULL,state TEXT NOT NULL,updated_at TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS history(id INTEGER PRIMARY KEY,source_id TEXT NOT NULL,event TEXT NOT NULL,detail TEXT NOT NULL,at TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS processed(source_id TEXT NOT NULL,identity TEXT NOT NULL,fingerprint TEXT NOT NULL,verdict TEXT NOT NULL,at TEXT NOT NULL,PRIMARY KEY(source_id,identity,fingerprint));
        CREATE TABLE IF NOT EXISTS pending(source_id TEXT NOT NULL,identity TEXT NOT NULL,candidate TEXT NOT NULL,stage TEXT NOT NULL,at TEXT NOT NULL,PRIMARY KEY(source_id,identity));
        CREATE TABLE IF NOT EXISTS quarantine(source_id TEXT NOT NULL,identity TEXT NOT NULL,reason TEXT NOT NULL,artifact_sha256 TEXT,details TEXT NOT NULL,at TEXT NOT NULL,reviewed INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(source_id,identity));
        CREATE TABLE IF NOT EXISTS publications(identity TEXT PRIMARY KEY,candidate TEXT NOT NULL,published_at TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS counters(day TEXT PRIMARY KEY,published INTEGER NOT NULL DEFAULT 0);
        CREATE TABLE IF NOT EXISTS publication_runs(run_id TEXT PRIMARY KEY,day TEXT NOT NULL,published_this_run INTEGER NOT NULL,publication_charged INTEGER NOT NULL,outcome TEXT NOT NULL,created_at TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS content_works(source_id TEXT NOT NULL,work_id TEXT NOT NULL,data TEXT NOT NULL,PRIMARY KEY(source_id,work_id));
        CREATE TABLE IF NOT EXISTS content_chapters(source_id TEXT NOT NULL,work_id TEXT NOT NULL,chapter_id TEXT NOT NULL,status TEXT NOT NULL,reason TEXT NOT NULL,pages TEXT NOT NULL,attempts INTEGER NOT NULL DEFAULT 0,updated_at TEXT NOT NULL,next_retry TEXT NOT NULL,PRIMARY KEY(source_id,work_id,chapter_id));
        CREATE INDEX IF NOT EXISTS idx_chapter_due ON content_chapters(status,next_retry);''')
        version=self.db.execute("SELECT max(version) FROM schema_version").fetchone()[0]
        if version>1:raise RuntimeError(f"database schema {version} is newer than this Shura build")
    def close(self): self.db.close()
    def event(self,sid,event,detail): self.db.execute("INSERT INTO history(source_id,event,detail,at) VALUES(?,?,?,?)",(sid,event,json.dumps(detail,default=str),now()))
    def add_source(self,s,update_state=False):
        previous=self.get_source(s.source_id)
        has_quarantine=self.is_source_quarantined(s.source_id)
        s.configuration_fingerprint=s.fingerprint()
        if previous:
            s.created_at=previous.created_at
            s.last_attempt=previous.last_attempt;s.last_success=previous.last_success;s.last_failure=previous.last_failure
            s.next_retry=previous.next_retry;s.failure_reason=previous.failure_reason;s.attempt_count=previous.attempt_count
            if not update_state:s.state=previous.state
        if has_quarantine:s.state=SourceState.QUARANTINED
        d=s.__dict__.copy();d['state']=s.state.value
        with self.tx():
            self.db.execute("INSERT INTO sources VALUES(?,?,?,?) ON CONFLICT(source_id) DO UPDATE SET data=excluded.data,state=excluded.state,updated_at=excluded.updated_at",(s.source_id,json.dumps(d),s.state.value,now()))
            self.event(s.source_id,"registered" if not previous else "configuration_updated",{"kind":s.kind,"fingerprint":s.configuration_fingerprint,"state":s.state.value})
    def get_source(self,sid):
        r=self.db.execute("SELECT data FROM sources WHERE source_id=?",(sid,)).fetchone()
        if not r:return None
        d=json.loads(r[0]);d['state']=SourceState(d['state']);return Source(**d)
    def sources(self):return [self.get_source(r[0]) for r in self.db.execute("SELECT source_id FROM sources ORDER BY source_id")]
    def save_source(self,s):
        s.updated_at=now();d=s.__dict__.copy();d['state']=s.state.value
        self.db.execute("UPDATE sources SET data=?,state=?,updated_at=? WHERE source_id=?",(json.dumps(d),s.state.value,s.updated_at,s.source_id))
    def transition(self,sid,state,reason=""):
        s=self.get_source(sid)
        if not s:raise KeyError(sid)
        allowed={SourceState.ACTIVE:{SourceState.RETRY_LATER,SourceState.PAUSED,SourceState.DEAD,SourceState.QUARANTINED,SourceState.ACCEPTED},SourceState.RETRY_LATER:{SourceState.ACTIVE,SourceState.DEAD,SourceState.PAUSED,SourceState.QUARANTINED,SourceState.ACCEPTED},SourceState.PAUSED:{SourceState.ACTIVE,SourceState.ACCEPTED},SourceState.DEAD:{SourceState.ACTIVE},SourceState.ACCEPTED:{SourceState.ACTIVE,SourceState.RETRY_LATER,SourceState.PAUSED,SourceState.QUARANTINED},SourceState.QUARANTINED:set()}
        if state not in allowed[s.state]:raise ValueError(f"invalid transition {s.state} -> {state}")
        s.state=state;s.failure_reason=None if state==SourceState.ACTIVE else (reason or s.failure_reason);self.save_source(s);self.event(sid,"state_transition",{"state":state.value,"reason":reason})
    def accept_source(self,sid,operator="local-operator",reason=""):
        if self.is_source_quarantined(sid):raise ValueError("quarantined source cannot be accepted")
        if not self.get_source(sid):raise KeyError(sid)
        self.transition(sid,SourceState.ACCEPTED,reason or "operator accepted source")
        s=self.get_source(sid);s.failure_reason=None;s.next_retry=None;self.save_source(s)
        self.event(sid,"source_accepted",{"operator":operator,"reason":reason})
    def record_attempt(self,sid,success,reason=""):
        s=self.get_source(sid)
        if not s:raise KeyError(sid)
        t=now();s.last_attempt=t;s.attempt_count+=1
        if success:s.last_success=t;s.failure_reason=None;s.next_retry=None;s.state=SourceState.ACTIVE if s.state==SourceState.RETRY_LATER else s.state
        else:
            s.last_failure=t;s.failure_reason=reason
            if s.state in (SourceState.ACTIVE,SourceState.ACCEPTED,SourceState.RETRY_LATER):
                s.state=SourceState.RETRY_LATER
                last_success=self.db.execute("SELECT max(at) FROM history WHERE source_id=? AND event IN ('crawl_success','retry_reset')",(sid,)).fetchone()[0]
                query="SELECT count(*) FROM history WHERE source_id=? AND event='crawl_failure'"+(" AND at>?" if last_success else "")
                failures=self.db.execute(query,(sid,last_success) if last_success else (sid,)).fetchone()[0]+1
                if failures>=10:
                    s.state=SourceState.DEAD;s.next_retry=None
                else:
                    delay=timedelta(days=1) if failures>=3 else timedelta(hours=6)
                    s.next_retry=(datetime.now(timezone.utc)+delay).isoformat()
        self.save_source(s);self.event(sid,"crawl_success" if success else "crawl_failure",{"reason":reason})
    def eligible(self,s,at=None):
        at=at or datetime.now(timezone.utc)
        if not s.enabled or s.state in (SourceState.PAUSED,SourceState.DEAD,SourceState.QUARANTINED):return False
        if s.state==SourceState.RETRY_LATER:return bool(s.next_retry and datetime.fromisoformat(s.next_retry)<=at) if s.next_retry else bool(s.last_failure and datetime.fromisoformat(s.last_failure)+timedelta(days=1)<=at)
        return True
    def already_processed(self,sid,identity,fingerprint):return bool(self.db.execute("SELECT 1 FROM processed WHERE source_id=? AND identity=? AND fingerprint=?",(sid,identity,fingerprint)).fetchone())
    def mark_processed(self,sid,identity,fingerprint,verdict):self.db.execute("INSERT OR REPLACE INTO processed VALUES(?,?,?,?,?)",(sid,identity,fingerprint,verdict,now()))
    def put_pending(self,c,stage="discovered"):self.db.execute("INSERT OR REPLACE INTO pending VALUES(?,?,?,?,?)",(c.source_id,c.identity,json.dumps(c.as_dict()),stage,now()))
    def pending(self):
        out=[]
        for r in self.db.execute("SELECT source_id,identity,candidate,stage FROM pending ORDER BY at"):
            item=json.loads(r["candidate"]);item["identity"]=r["identity"];item["stage"]=r["stage"];out.append(item)
        return out
    def has_pending_for(self,sid,identity):return bool(self.db.execute("SELECT 1 FROM pending WHERE source_id=? AND identity=?",(sid,identity)).fetchone())
    def forget_pending(self,sid,identity):self.db.execute("DELETE FROM pending WHERE source_id=? AND identity=?",(sid,identity))
    def quarantine_item(self,c,reason,digest=None,details=None):
        self.db.execute("INSERT OR REPLACE INTO quarantine VALUES(?,?,?,?,?,?,0)",(c.source_id,c.identity,reason,digest,json.dumps(details or {}),now()));s=self.get_source(c.source_id)
        if s and s.state!=SourceState.QUARANTINED:self.transition(s.source_id,SourceState.QUARANTINED,reason)
        self.event(c.source_id,"quarantine",{"identity":c.identity,"reason":reason,"sha256":digest})
    def status(self,sid):
        s=self.get_source(sid)
        if not s:return None
        counts={r[0]:r[1] for r in self.db.execute("SELECT event,count(*) FROM history WHERE source_id=? GROUP BY event",(sid,))}
        return {"source":s,"counts":counts,"recent":[dict(r) for r in self.db.execute("SELECT event,detail,at FROM history WHERE source_id=? ORDER BY id DESC LIMIT 20",(sid,))]}
    def record_chapters(self,sid,review,at=None):
        at=at or now()
        self.db.execute("INSERT OR REPLACE INTO content_works VALUES(?,?,?)",(sid,review.work_id,json.dumps({"work_id":review.work_id,"name":review.work_name,"verdict":review.verdict,"reason":review.reason,"totals":review.totals,"updated_at":at},ensure_ascii=False,default=str)))
        for ch in review.chapters:
            self.db.execute("INSERT OR REPLACE INTO content_chapters VALUES(?,?,?,?,?,?,?,?,?)",(sid,ch.work_id,ch.chapter_id,ch.status.value,ch.reason,json.dumps(ch.pages,default=str),ch.attempts,at,at))
        return review
    def content_work(self,sid,work_id):
        row=self.db.execute("SELECT data FROM content_works WHERE source_id=? AND work_id=?",(sid,work_id)).fetchone()
        return json.loads(row[0]) if row else None
    def content_works(self,sid=None):
        rows=self.db.execute("SELECT source_id,work_id FROM content_works ORDER BY source_id,work_id") if sid is None else self.db.execute("SELECT source_id,work_id FROM content_works WHERE source_id=? ORDER BY work_id",(sid,))
        return [{"source_id":r[0],"work_id":r[1]} for r in rows]
    def content_chapters(self,sid=None,work_id=None):
        if sid is not None and work_id is not None:
            rows=self.db.execute("SELECT source_id,work_id,chapter_id,status,reason,pages,attempts,updated_at,next_retry FROM content_chapters WHERE source_id=? AND work_id=? ORDER BY chapter_id",(sid,work_id))
        elif sid is not None:
            rows=self.db.execute("SELECT source_id,work_id,chapter_id,status,reason,pages,attempts,updated_at,next_retry FROM content_chapters WHERE source_id=? ORDER BY work_id,chapter_id",(sid,))
        else:
            rows=self.db.execute("SELECT source_id,work_id,chapter_id,status,reason,pages,attempts,updated_at,next_retry FROM content_chapters ORDER BY source_id,work_id,chapter_id")
        return [{"source_id":r[0],"work_id":r[1],"chapter_id":r[2],"status":r[3],"reason":r[4],"pages":json.loads(r[5]),"attempts":r[6],"updated_at":r[7],"next_retry":r[8]} for r in rows]
    def due_chapters(self,at=None,limit=250):
        at=at or now()
        rows=self.db.execute("SELECT source_id,work_id,chapter_id,status,reason,attempts FROM content_chapters WHERE status IN ('TEMPORARY_FAILURE','STALE_LINK','PARSE_ERROR') AND next_retry<=? ORDER BY next_retry LIMIT ?",(at,limit))
        return [{"source_id":r[0],"work_id":r[1],"chapter_id":r[2],"status":r[3],"reason":r[4],"attempts":r[5]} for r in rows]
    def update_chapter(self,sid,work_id,chapter_id,status,reason,pages,attempts,at=None,next_retry=None):
        at=at or now();st=status if isinstance(status,str) else status.value;cid=str(chapter_id);page_json=json.dumps(pages,default=str);nrt=next_retry or at
        if self.db.execute("SELECT 1 FROM content_chapters WHERE source_id=? AND work_id=? AND chapter_id=?",(sid,work_id,cid)).fetchone():
            self.db.execute("UPDATE content_chapters SET status=?,reason=?,pages=?,attempts=?,updated_at=?,next_retry=? WHERE source_id=? AND work_id=? AND chapter_id=?",(st,reason,page_json,attempts,at,nrt,sid,work_id,cid))
        else:
            self.db.execute("INSERT OR REPLACE INTO content_chapters VALUES(?,?,?,?,?,?,?,?,?)",(sid,work_id,cid,st,reason,page_json,attempts,at,nrt))
        self.recompute_work(sid,work_id)
    def recompute_work(self,sid,work_id):
        """Roll chapter-level verdicts up into the work summary. A work is publishable as long as
        it has >=1 healthy (or preview-verified PARTIAL) public chapter; paid/unavailable/retryable
        chapters are counted and excluded without affecting the rest, and nothing is deleted."""
        rows=self.db.execute("SELECT status FROM content_chapters WHERE source_id=? AND work_id=?",(sid,work_id))
        totals: dict[str,int]={}
        for (st,) in rows: totals[st]=totals.get(st,0)+1
        existing=self.db.execute("SELECT data FROM content_works WHERE source_id=? AND work_id=?",(sid,work_id)).fetchone()
        name=work_id
        if existing:
            try: name=json.loads(existing[0]).get("name") or work_id
            except Exception: pass
        healthy=totals.get("HEALTHY",0);partial=totals.get("PARTIAL",0);retryable=sum(totals.get(s,0) for s in ("TEMPORARY_FAILURE","STALE_LINK","PARSE_ERROR"))
        excluded=totals.get("PAID",0)+totals.get("UNAVAILABLE",0)
        if healthy>0 or partial>0: verdict,reason="ACCEPTED",f"{healthy} healthy + {partial} partial(preview-verified) public chapter(s); {excluded} excluded; {retryable} retryable"
        elif retryable>0: verdict,reason="PENDING",f"no healthy chapter yet; {retryable} retryable + {excluded} excluded; will retry per policy (not final)"
        else: verdict,reason="REJECTED","no publicly available chapters left; recorded for later recheck"
        self.db.execute("INSERT OR REPLACE INTO content_works VALUES(?,?,?)",(sid,work_id,json.dumps({"work_id":work_id,"name":name,"verdict":verdict,"reason":reason,"totals":totals,"updated_at":now()},ensure_ascii=False,default=str)))
    def forget_source(self,sid):
        # Security quarantine is durable and must survive source removal/re-registration.
        with self.tx():
            for table in ("sources","history","processed","pending"):self.db.execute(f"DELETE FROM {table} WHERE source_id=?",(sid,))
    def is_quarantined(self,sid,identity):
        return bool(self.db.execute("SELECT 1 FROM quarantine WHERE source_id=? AND identity=? AND reviewed=0",(sid,identity)).fetchone())
    def is_source_quarantined(self,sid):
        return bool(self.db.execute("SELECT 1 FROM quarantine WHERE source_id=? AND reviewed=0 LIMIT 1",(sid,)).fetchone())
    def accept_pending(self,sid,identity,operator="local-operator"):
        row=self.db.execute("SELECT candidate,stage FROM pending WHERE source_id=? AND identity=?",(sid,identity)).fetchone()
        if not row:raise KeyError(identity)
        if self.is_quarantined(sid,identity) or self.is_source_quarantined(sid):raise ValueError("quarantined source/candidate cannot be accepted")
        if row["stage"]!="security-passed":raise ValueError("candidate must pass security checks before acceptance")
        with self.tx():
            self.db.execute("UPDATE pending SET stage='accepted',at=? WHERE source_id=? AND identity=?",(now(),sid,identity))
            self.event(sid,"candidate_accepted",{"identity":identity,"operator":operator})
    def review_quarantine(self,sid,identity,operator="local-operator",reason="",resolve=False):
        if not self.is_quarantined(sid,identity):raise KeyError(identity)
        if resolve and not reason.strip():raise ValueError("quarantine resolution requires an audit reason")
        with self.tx():
            if resolve:self.db.execute("UPDATE quarantine SET reviewed=1 WHERE source_id=? AND identity=?",(sid,identity))
            self.event(sid,"quarantine_review",{"identity":identity,"operator":operator,"reason":reason,"decision":"resolved" if resolve else "retained"})
            if resolve:
                self.db.execute("DELETE FROM processed WHERE source_id=? AND identity=? AND verdict='quarantined'",(sid,identity))
                s=self.get_source(sid)
                if s and s.state==SourceState.QUARANTINED and not self.is_source_quarantined(sid):
                    s.state=SourceState.ACTIVE;s.failure_reason=None;s.next_retry=None;self.save_source(s)
                    self.event(sid,"quarantine_source_resolved",{"identity":identity,"operator":operator,"reason":reason})
    def publish(self,candidates):
        day=datetime.now(timezone.utc).date().isoformat();count=0;run_id=str(uuid.uuid4());created=now()
        with self.tx() as db:
            for c in candidates:
                db.execute("INSERT OR IGNORE INTO publications VALUES(?,?,?)",(c.identity,json.dumps(c.as_dict()),created))
                if db.execute("SELECT changes()").fetchone()[0]:count+=1
                db.execute("DELETE FROM pending WHERE source_id=? AND identity=?",(c.source_id,c.identity))
            if count:db.execute("INSERT INTO counters VALUES(?,?) ON CONFLICT(day) DO UPDATE SET published=published+excluded.published",(day,count))
            db.execute("INSERT INTO publication_runs VALUES(?,?,?,?,?,?)",(run_id,day,count,int(count>0),"published" if count else "noop",created))
        return {"run_id":run_id,"published_this_run":count,"publications_today":self.publication_count_today(),"publication_charged":count>0}
    def record_noop_publication(self):
        run_id=str(uuid.uuid4());day=datetime.now(timezone.utc).date().isoformat();created=now()
        self.db.execute("INSERT INTO publication_runs VALUES(?,?,?,?,?,?)",(run_id,day,0,0,"noop",created))
        return {"run_id":run_id,"published_this_run":0,"publications_today":self.publication_count_today(),"publication_charged":False}
    def published(self):
        rows=[]
        for r in self.db.execute("SELECT candidate FROM publications ORDER BY published_at"):
            item=json.loads(r[0]);item["identity"]=f"{item['package']}|{item['version']}";rows.append(item)
        return rows
    def publication_count_today(self):
        r=self.db.execute("SELECT published FROM counters WHERE day=?",(datetime.now(timezone.utc).date().isoformat(),)).fetchone();return r[0] if r else 0
