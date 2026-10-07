import json, shutil, tempfile, re, time
from pathlib import Path
from shura_core.publishing import index_pb
class PublishRefused(RuntimeError):pass
class RepositoryPublisher:
    def __init__(self,store,root):self.store=store;self.root=Path(root)
    def publish(self,stage=False,release=False):
        pending=self.store.pending()
        if not pending:return {"published":0,"noop":True,"refused":False,**self.store.record_noop_publication()}
        eligible=[x for x in pending if x.get("stage")=="accepted" and not self.store.is_source_quarantined(x["source_id"])]
        refused=[x for x in pending if x.get("stage")!="accepted" or self.store.is_source_quarantined(x["source_id"])]
        if stage:return {"published":0,"staged":len(pending),"refused":bool(refused),"refused_count":len(refused),"publications_today":self.store.publication_count_today(),"published_this_run":0,"publication_charged":False}
        if not release:raise PublishRefused("release flag required")
        existing={x["identity"]:x for x in self.store.published()}
        groups={}
        for item in eligible:groups.setdefault(item["identity"],[]).append(item)
        selected=[];conflicts=[];aliases={};already=[]
        for identity,items in groups.items():
            if identity in existing:
                old_hash=existing[identity].get("metadata",{}).get("_security",{}).get("sha256")
                if old_hash and all(x.get("metadata",{}).get("_security",{}).get("sha256")==old_hash for x in items):already.extend(items)
                else:conflicts.extend(items)
                continue
            hashes={x.get("metadata",{}).get("_security",{}).get("sha256") for x in items}
            if len(hashes)>1:
                conflicts.extend(items);continue
            selected.append(items[0]);aliases[identity]=items[1:]
        refused.extend(conflicts)
        if not selected:
            # Clear only exact duplicates of already published identities; retain conflicts and refused work.
            for item in already:self.store.forget_pending(item["source_id"],item["identity"])
            ledger=self.store.record_noop_publication() if not refused else {"published_this_run":0,"publications_today":self.store.publication_count_today(),"publication_charged":False}
            return {"published":0,"noop":bool(already) and not conflicts,"refused":bool(refused),"refused_count":len(refused),**ledger}
        self.root.mkdir(parents=True,exist_ok=True)
        output={};
        for item in selected:
            if not re.fullmatch(r"[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+",item.get("package","")) or not re.fullmatch(r"[A-Za-z0-9._+-]+",item.get("version","")):
                raise PublishRefused(f"unsafe package/version path for {item.get('identity','unknown')}")
            artifact=Path(item.get("metadata",{}).get("_artifact_path",""))
            if not artifact.is_file():raise PublishRefused(f"verified APK missing for {item['identity']}")
            filename=f"{item['package'].replace('.', '_')}-{item['version']}.apk"
            item.setdefault("metadata",{})["_published_apk"]=filename
            output[self.root/filename]=artifact
        all_entries=self.store.published()+selected
        entries=[self._repo_entry(x) for x in all_entries]
        docs={"index.json":{"repo":"Shura","packages":entries},"index.min.json":{"repo":"Shura","packages":entries},"repo.json":{"name":"Shura","website":"","signingKey":""}}
        for name,obj in docs.items():output[self.root/name]=json.dumps(obj,ensure_ascii=False,separators=(",",":"),sort_keys=True).encode()
        output[self.root/"index.pb"]=index_pb.encode_index(entries,repo="Shura",generated_at=int(time.time()))
        # Stage every artifact and index, retain backups, and restore on any filesystem/DB failure.
        staged=[];backups=[];installed=[];tmpdir=Path(tempfile.mkdtemp(prefix=".shura-publish-",dir=self.root))
        try:
            for i,(dest,value) in enumerate(output.items()):
                tmp=tmpdir/str(i)
                if isinstance(value,Path):shutil.copyfile(value,tmp)
                else:tmp.write_bytes(value)
                staged.append((tmp,dest))
            for i,(_,dest) in enumerate(staged):
                if dest.exists():
                    backup=tmpdir/f"backup-{i}";shutil.copyfile(dest,backup);backups.append((backup,dest))
            for tmp,dest in staged:tmp.replace(dest);installed.append(dest)
            from shura_core.models import Candidate
            ledger=self.store.publish([Candidate(**{k:v for k,v in item.items() if k in Candidate.__dataclass_fields__}) for item in selected])
            for identity,duplicates in aliases.items():
                for duplicate in duplicates:
                    self.store.forget_pending(duplicate["source_id"],identity)
                    self.store.event(duplicate["source_id"],"duplicate_publication",{"identity":identity,"canonical_source":next(x["source_id"] for x in selected if x["identity"]==identity)})
        except Exception:
            for dest in installed:
                backup=next((b for b,d in backups if d==dest),None)
                if backup and backup.exists():shutil.copyfile(backup,dest)
                else:dest.unlink(missing_ok=True)
            raise
        finally:shutil.rmtree(tmpdir,ignore_errors=True)
        return {"published":ledger["published_this_run"],"noop":False,"refused":bool(refused),"refused_count":len(refused),**ledger}
    @staticmethod
    def _repo_entry(x):
        metadata=x.get("metadata",{})
        return {"name":x.get("name",""),"pkg":x["package"],"apk":metadata.get("_published_apk",""),"lang":x.get("language","und"),"version":x["version"],"code":0,"nsfw":0,"sources":[x.get("apk_url","")],"shura":{"source_id":x["source_id"],"identity":x["identity"],"provenance":x.get("provenance",{}),"artifact_sha256":metadata.get("_security",{}).get("sha256"),"signing_certificate_sha256":metadata.get("_security",{}).get("certificate")}}
