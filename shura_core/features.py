import os

def enabled(name,default=False):
    value=os.getenv("SHURA_FEATURE_"+name.upper().replace("-","_"))
    if value is None:return default
    return value.strip().lower() in {"1","true","yes","on"}

FEATURES={"translation":False,"experimental-crawlers":False,"experimental-scanners":False,"future-ai":False}
