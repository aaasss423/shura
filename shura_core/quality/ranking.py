def source_quality(*,availability=0.5,language="und",successes=0,attempts=0,valid=0,duplicates=0,quarantined=0,fresh_days=30):
    reliability=successes/max(attempts,1); validity=valid/max(successes,1)
    freshness=max(0.0,1-fresh_days/30); language_score=1.0 if language.startswith("ar") else .7 if language!="und" else .3
    security=0.0 if quarantined else 1.0
    return round(max(0.0,min(100.0,100*(.25*availability+.2*reliability+.2*validity+.15*language_score+.1*freshness+.1*security-.1*min(duplicates/max(valid,1),1)))),2)
