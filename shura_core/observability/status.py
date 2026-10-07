from shura_core.models import SourceState

def source_report(store,source_id):
    record=store.status(source_id)
    if record is None:return None
    s=record["source"];counts=record["counts"]
    return {
        "source_id":s.source_id,"name":s.name,"url":s.url,"kind":s.kind,"language":s.language,
        "state":s.state.value,"last_attempt":s.last_attempt,"last_success":s.last_success,
        "last_failure":s.last_failure,"failure_reason":s.failure_reason,"next_retry":s.next_retry,
        "attempt_count":s.attempt_count,"configuration_fingerprint":s.configuration_fingerprint,
        "stop_reason":s.failure_reason if s.state==SourceState.PAUSED else None,
        "discovered_count":counts.get("discovered",0),"accepted_count":counts.get("candidate_accepted",0),
        "rejected_count":counts.get("candidate_rejected",0),"quarantine_count":counts.get("quarantine",0),
        "duplicate_count":counts.get("duplicate",0)+counts.get("duplicatesSkipped",0),"events":counts,
        "recent":record["recent"],
    }
