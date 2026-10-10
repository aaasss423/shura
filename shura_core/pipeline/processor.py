from __future__ import annotations
import re
from shura_core.models import Candidate
from shura_core.security.artifacts import ArtifactScanner,ScanVerdict
from shura_core.security.malware import MalwareScanner
from shura_core.security.network import SafeHTTP
from shura_core.security.network import validate_url
from shura_core.quality.ranking import source_quality
from shura_core.pipeline.content import MANIFEST_KEY, evaluate_work
from shura_core.quality.adult import SourceClass, classify_source, classify_work
from datetime import datetime,timezone
class CandidateProcessor:
    def __init__(self,store,artifact_dir="artifacts",scanner=None,malware_scanner=None,expected_hosts=None,http_factory=None,url_validator=None,content_review_enabled=None):
        from pathlib import Path
        self.store=store;self.dir=Path(artifact_dir);self.dir.mkdir(parents=True,exist_ok=True);self.scanner=scanner or ArtifactScanner();self.malware_scanner=malware_scanner or MalwareScanner();self.expected_hosts=expected_hosts or set();self.http_factory=http_factory or SafeHTTP;self.url_validator=url_validator or validate_url;self._force_content_review=content_review_enabled
    def _content_review(self,c):
        """Generic per-chapter content review. Runs in the production accept path whenever
        the source enables content_policy.review_enabled (or it is forced). The work is only
        rejected when it has no healthy public chapter and nothing left to retry; paid and
        damaged chapters are recorded in the ledger and excluded, never blocking the rest."""
        s=self.store.get_source(c.source_id);cfg=s.configuration if s else {}
        policy=cfg.get("content_policy") or {}
        enabled=self._force_content_review if self._force_content_review is not None else policy.get("review_enabled",False)
        if not enabled:return ("SKIP","")
        manifest=(c.metadata or {}).get(MANIFEST_KEY)
        if not manifest:
            self.store.put_pending(c,"content-review-required")
            self.store.event(c.source_id,"content_review",{"identity":c.identity,"verdict":"REQUIRED","reason":"content review enabled but candidate carries no content manifest"})
            return ("PENDING","content review enabled but candidate carries no content manifest")
        hosts=set(cfg.get("allowed_hosts",[])) if s else set(self.expected_hosts)
        http=self.http_factory(hosts,timeout=policy.get("fetch_timeout",15),max_bytes=policy.get("max_page_bytes",2_000_000))
        def fetch(url):
            data,h,final=http.get(url,max_bytes=policy.get("max_page_bytes",2_000_000))
            return data,h,final
        review=evaluate_work(str(manifest.get("work_id") or c.identity),str(manifest.get("work_name") or c.name),list(manifest.get("chapters") or []),fetch,policy)
        self.store.record_chapters(c.source_id,review)
        self.store.event(c.source_id,"content_review",{"identity":c.identity,"verdict":review.verdict,"reason":review.reason,"totals":review.totals})
        if review.verdict=="PENDING":self.store.put_pending(c,"content-review")
        return (review.verdict,review.reason)
    def _source_content_gate(self,c):
        """Publication-policy gate on the source itself, before any download.

        Returns ``(verdict, reason)`` where a non-``ALLOW`` verdict stops the
        candidate: ``REJECTED`` for a source that is explicitly pornographic,
        ``QUARANTINE`` for one under review, and ``PENDING`` when the signals are
        inconclusive or the source is a mixed/unknown catalogue. Inconclusive
        never becomes an automatic accept - that is the one failure mode the
        policy exists to prevent.

        This runs ahead of the artifact download so an explicit source never
        costs a fetch, and it is recorded in the event ledger either way so the
        decision can be audited and re-derived.
        """
        s=self.store.get_source(c.source_id);cfg=s.configuration if s else {}
        policy=cfg.get("content_policy")
        if not policy:
            # No content policy configured means the source is not being published
            # under the content policy at all; the legacy gates still apply. This
            # keeps an unconfigured source from being held forever by a policy it
            # never opted into.
            return ("ALLOW","content policy not configured for this source")
        assessment=classify_source(
            declared=policy.get("classification"),
            host=(c.apk_url or "").split("/")[2].split(":")[0] if "//" in (c.apk_url or "") else None,
            # The source's own declared metadata, not the candidate's artifact
            # name: "how this catalogue is described" is the question, and the APK
            # filename answers nothing about it.
            name=(s.name if s else c.name),description=cfg.get("description"),
            tags=cfg.get("tags") or [],
        )
        self.store.event(c.source_id,"content_classification",
                         {"identity":c.identity,"scope":"source",**assessment.to_dict()})
        kind=assessment.classification
        if kind is SourceClass.NSFW:
            return ("REJECTED","source classified NSFW: "+"; ".join(assessment.reasons))
        if kind is SourceClass.QUARANTINED:
            return ("QUARANTINE","source is quarantined by content policy: "+"; ".join(assessment.reasons))
        if kind in (SourceClass.MIXED, SourceClass.UNKNOWN):
            if policy.get("review_unclassified", kind is SourceClass.MIXED):
                self.store.put_pending(c,"content-classification")
                return ("PENDING",f"source classified {kind.value}, operator review required: "+"; ".join(assessment.reasons))
            return ("PENDING",f"source classification {kind.value} is unresolved: "+"; ".join(assessment.reasons))
        return ("ALLOW","source classified "+kind.value)

    def _work_content_gate(self,c):
        """Per-title gate, independent of the source verdict.

        A source marked SAFE may still carry one explicit title, and that title
        must not inherit the source's verdict. Only applied when the source
        declares per-title checking; absence of that is not permission to skip
        silently.
        """
        s=self.store.get_source(c.source_id);cfg=s.configuration if s else {}
        policy=cfg.get("content_policy") or {}
        if not policy.get("check_titles",False):return None
        manifest=(c.metadata or {}).get(MANIFEST_KEY) or {}
        assessment=classify_work(
            work_id=str(manifest.get("work_id") or c.identity),
            name=str(manifest.get("work_name") or c.name),
            tags=manifest.get("tags") or [],
            description=manifest.get("description"),
        )
        self.store.event(c.source_id,"content_classification",
                         {"identity":c.identity,"scope":"work",**assessment.to_dict()})
        if assessment.classification is SourceClass.NSFW:
            return ("REJECTED","title classified NSFW: "+"; ".join(assessment.reasons))
        if assessment.classification in (SourceClass.MIXED, SourceClass.UNKNOWN):
            return ("PENDING","title classification unresolved: "+"; ".join(assessment.reasons))
        return None

    def validate(self,c):
        errors=[]
        source=self.store.get_source(c.source_id)
        if not source or not source.configuration.get("signing_key"):errors.append("trusted source signing_key is not configured")
        elif not re.fullmatch(r"[0-9a-fA-F:]{64,95}",source.configuration["signing_key"]) or len(source.configuration["signing_key"].replace(":",""))!=64:errors.append("trusted signing_key must be a SHA-256 certificate fingerprint")
        if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)+",c.package or ""):errors.append("invalid package")
        if not c.version or len(c.version)>128 or not re.fullmatch(r"[A-Za-z0-9._+-]+",c.version):errors.append("invalid version")
        if not c.name:errors.append("missing name")
        if not c.language or len(c.language)>35:errors.append("invalid language")
        if not c.provenance:errors.append("missing provenance")
        try:
            allowed=set(source.configuration.get("allowed_hosts",[])) if source else set()
            self.url_validator(c.apk_url,allowed or self.expected_hosts)
        except Exception as e:errors.append(f"unsafe APK URL: {e}")
        return errors
    def process(self,c,download=True):
        if self.store.is_source_quarantined(c.source_id) or self.store.is_quarantined(c.source_id,c.identity):return {"verdict":"QUARANTINED","reason":"durable security quarantine requires review"}
        gate=self._source_content_gate(c)
        if gate[0]=="REJECTED":
            self.store.put_pending(c,"content-policy-rejected")
            self.store.mark_processed(c.source_id,c.identity,self.store.get_source(c.source_id).configuration_fingerprint,"rejected")
            return {"verdict":"REJECTED","reason":gate[1]}
        if gate[0]=="QUARANTINE":
            self.store.put_pending(c,"content-policy-quarantine")
            return {"verdict":"QUARANTINED","reason":gate[1]}
        if gate[0]=="PENDING":
            return {"verdict":"PENDING","reason":gate[1]}
        title_gate=self._work_content_gate(c)
        if title_gate:
            if title_gate[0]=="REJECTED":self.store.put_pending(c,"content-policy-rejected")
            else:self.store.put_pending(c,"content-classification")
            return {"verdict":title_gate[0],"reason":title_gate[1]}
        errors=self.validate(c)
        if errors:
            self.store.put_pending(c,"validation-rejected");self.store.mark_processed(c.source_id,c.identity,self.store.get_source(c.source_id).configuration_fingerprint, "rejected");return {"verdict":"REJECTED","reason":"; ".join(errors)}
        self.store.put_pending(c,"validated")
        if not download:return {"verdict":"PENDING","reason":"artifact download deferred"}
        try:
            source=self.store.get_source(c.source_id);hosts=set(source.configuration.get("allowed_hosts",[])) if source else set(self.expected_hosts)
            from shura_core.security.network import SafeHTTP
            http=self.http_factory(hosts,timeout=30,max_bytes=self.scanner.max_size)
            data,_,_=http.get(c.apk_url,max_bytes=self.scanner.max_size)
            path=self.dir/(c.source_id.replace("/","_")+"-"+c.identity.replace("/","_")+".apk");path.write_bytes(data)
            source=self.store.get_source(c.source_id)
            result=self.scanner.scan(path,expected_package=c.package,expected_certificate=source.configuration.get("signing_key"))
            if result.verdict!=ScanVerdict.CLEAN:
                self.store.mark_processed(c.source_id,c.identity,self.store.get_source(c.source_id).configuration_fingerprint,"quarantined")
                self.store.quarantine_item(c,result.reason,result.sha256,{"verdict":result.verdict.value,"scanner":type(self.scanner).__name__,"size":result.size});return {"verdict":"QUARANTINED","reason":result.reason,"sha256":result.sha256}
            malware=self.malware_scanner.scan(path)
            if malware.verdict!=ScanVerdict.CLEAN:
                fingerprint=self.store.get_source(c.source_id).configuration_fingerprint
                if malware.verdict==ScanVerdict.UNAVAILABLE:
                    # The scanner could not run. That is an infrastructure fault, not
                    # evidence of malware, so the artifact is neither accepted nor
                    # filed as malware: it is deferred so a later pass can scan it
                    # once the engine is back. It can never reach the publisher.
                    self.store.put_pending(c,"security-blocked")
                    self.store.event(c.source_id,"security_blocked",{"identity":c.identity,"reason":malware.reason,"scanner":type(self.malware_scanner).__name__})
                    return {"verdict":"PENDING","reason":"security scan unavailable: "+malware.reason,"sha256":result.sha256,"malware_verdict":malware.verdict.value}
                self.store.mark_processed(c.source_id,c.identity,fingerprint,"quarantined")
                self.store.quarantine_item(c,malware.reason,result.sha256,{"verdict":malware.verdict.value,"scanner":type(self.malware_scanner).__name__,"size":malware.size,"artifact_sha256":result.sha256})
                return {"verdict":"QUARANTINED","reason":malware.reason,"sha256":result.sha256,"malware_verdict":malware.verdict.value}
            c.metadata["_artifact_path"]=str(path)
            c.metadata["_security"]={"sha256":result.sha256,"package":result.package,"certificate":result.certificate,"verdict":result.verdict.value,"malware":{"engine":type(self.malware_scanner).__name__,"verdict":malware.verdict.value,"detail":malware.reason}}
            status=self.store.status(c.source_id);counts=status["counts"] if status else {};source=self.store.get_source(c.source_id)
            days=30
            if source and source.last_success:
                days=max(0,(datetime.now(timezone.utc)-datetime.fromisoformat(source.last_success)).days)
            c.metadata["_quality_score"]=source_quality(language=c.language,successes=counts.get("crawl_success",0),attempts=max(1,source.attempt_count if source else 1),valid=counts.get("candidate_accepted",0)+1,duplicates=counts.get("duplicatesSkipped",0),quarantined=0,fresh_days=days)
            verdict,reason=self._content_review(c)
            if verdict=="REJECTED":
                self.store.put_pending(c,"content-review")
                return {"verdict":"REJECTED","reason":"content: "+reason}
            if verdict=="PENDING":return {"verdict":"PENDING","reason":"content: "+reason}
            self.store.put_pending(c,"security-passed")
            self.store.mark_processed(c.source_id,c.identity,self.store.get_source(c.source_id).configuration_fingerprint,"security-passed")
            return {"verdict":"ACCEPTED","reason":f"{result.reason}; {malware.reason}","sha256":result.sha256,"malware_verdict":malware.verdict.value,"quality_score":c.metadata["_quality_score"],"artifact":str(path)}
        except Exception as e:
            self.store.put_pending(c,"download-failed");return {"verdict":"PENDING","reason":str(e)}
