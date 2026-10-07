from __future__ import annotations
import re
from shura_core.models import Candidate
from shura_core.security.artifacts import ArtifactScanner,ScanVerdict
from shura_core.security.malware import MalwareScanner
from shura_core.security.network import SafeHTTP
from shura_core.security.network import validate_url
from shura_core.quality.ranking import source_quality
from datetime import datetime,timezone
class CandidateProcessor:
    def __init__(self,store,artifact_dir="artifacts",scanner=None,malware_scanner=None,expected_hosts=None,http_factory=None,url_validator=None):
        from pathlib import Path
        self.store=store;self.dir=Path(artifact_dir);self.dir.mkdir(parents=True,exist_ok=True);self.scanner=scanner or ArtifactScanner();self.malware_scanner=malware_scanner or MalwareScanner();self.expected_hosts=expected_hosts or set();self.http_factory=http_factory or SafeHTTP;self.url_validator=url_validator or validate_url
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
                self.store.mark_processed(c.source_id,c.identity,self.store.get_source(c.source_id).configuration_fingerprint,"quarantined")
                self.store.quarantine_item(c,malware.reason,result.sha256,{"verdict":malware.verdict.value,"scanner":type(self.malware_scanner).__name__,"size":malware.size,"artifact_sha256":result.sha256})
                return {"verdict":"QUARANTINED","reason":malware.reason,"sha256":result.sha256,"malware_verdict":malware.verdict.value}
            c.metadata["_artifact_path"]=str(path)
            c.metadata["_security"]={"sha256":result.sha256,"package":result.package,"certificate":result.certificate,"verdict":result.verdict.value,"malware":{"engine":type(self.malware_scanner).__name__,"verdict":malware.verdict.value,"detail":malware.reason}}
            status=self.store.status(c.source_id);counts=status["counts"] if status else {};source=self.store.get_source(c.source_id)
            days=30
            if source and source.last_success:
                days=max(0,(datetime.now(timezone.utc)-datetime.fromisoformat(source.last_success)).days)
            c.metadata["_quality_score"]=source_quality(language=c.language,successes=counts.get("crawl_success",0),attempts=max(1,source.attempt_count if source else 1),valid=counts.get("candidate_accepted",0)+1,duplicates=counts.get("duplicatesSkipped",0),quarantined=0,fresh_days=days)
            self.store.put_pending(c,"security-passed")
            self.store.mark_processed(c.source_id,c.identity,self.store.get_source(c.source_id).configuration_fingerprint,"security-passed")
            return {"verdict":"ACCEPTED","reason":f"{result.reason}; {malware.reason}","sha256":result.sha256,"malware_verdict":malware.verdict.value,"quality_score":c.metadata["_quality_score"],"artifact":str(path)}
        except Exception as e:
            self.store.put_pending(c,"download-failed");return {"verdict":"PENDING","reason":str(e)}
