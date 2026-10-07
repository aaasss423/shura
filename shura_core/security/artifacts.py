from dataclasses import dataclass
from enum import StrEnum
from hashlib import sha256
from pathlib import Path
from typing import Protocol
import re, shutil, subprocess, zipfile

class ScanVerdict(StrEnum):
    CLEAN="CLEAN"
    SUSPICIOUS="SUSPICIOUS"
    REJECTED="REJECTED"
    UNAVAILABLE="UNAVAILABLE"

@dataclass
class ScanResult:
    verdict: ScanVerdict
    sha256: str
    size: int
    reason: str
    package: str|None=None
    certificate: str|None=None

class Scanner(Protocol):
    def scan(self,path,expected_package=None,expected_certificate=None)->ScanResult:...

class ArtifactScanner:
    """APK structure, manifest package, and signing checks. This is not malware detection."""
    def __init__(self,max_size=50_000_000,expected_certificate=None,timeout=15,max_uncompressed=150_000_000,max_entries=10000):
        self.max_size=max_size;self.expected=expected_certificate;self.timeout=timeout;self.max_uncompressed=max_uncompressed;self.max_entries=max_entries
    def scan(self,path,expected_package=None,expected_certificate=None):
        expected=self.expected if expected_certificate is None else expected_certificate
        p=Path(path);data_hash=sha256();size=0
        with p.open('rb') as f:
            for block in iter(lambda:f.read(1024*1024),b''):size+=len(block);data_hash.update(block)
        digest=data_hash.hexdigest()
        if size>self.max_size:return ScanResult(ScanVerdict.REJECTED,digest,size,"artifact too large")
        if not zipfile.is_zipfile(p):return ScanResult(ScanVerdict.REJECTED,digest,size,"malformed APK/ZIP")
        try:
            with zipfile.ZipFile(p) as z:
                infos=z.infolist()
                if len(infos)>self.max_entries:return ScanResult(ScanVerdict.REJECTED,digest,size,"too many APK entries")
                expanded=sum(i.file_size for i in infos)
                if expanded>self.max_uncompressed:return ScanResult(ScanVerdict.REJECTED,digest,size,"expanded APK size exceeds limit")
                if "AndroidManifest.xml" not in z.namelist():return ScanResult(ScanVerdict.REJECTED,digest,size,"AndroidManifest.xml missing")
                bad=z.testzip()
                if bad:return ScanResult(ScanVerdict.REJECTED,digest,size,f"corrupt archive member: {bad}")
        except (zipfile.BadZipFile,RuntimeError,EOFError) as exc:
            return ScanResult(ScanVerdict.REJECTED,digest,size,f"malformed APK archive: {exc}")
        package=None;aapt=shutil.which("aapt2") or shutil.which("aapt")
        if aapt:
            try:
                cmd=["dump","badging",str(p)]
                if Path(aapt).name=="aapt2":cmd=["aapt2","dump","badging",str(p)]
                else:cmd=["aapt","dump","badging",str(p)]
                out=subprocess.run(cmd,capture_output=True,text=True,timeout=self.timeout,check=False)
                if out.returncode:return ScanResult(ScanVerdict.REJECTED,digest,size,"aapt could not parse APK manifest")
                match=re.search(r"^package: name='([^']+)'",out.stdout,re.M)
                package=match.group(1) if match else None
            except subprocess.TimeoutExpired:return ScanResult(ScanVerdict.SUSPICIOUS,digest,size,"manifest package parsing timed out")
        if expected_package and not package:return ScanResult(ScanVerdict.SUSPICIOUS,digest,size,"aapt unavailable; APK package cannot be verified")
        if expected_package and package!=expected_package:return ScanResult(ScanVerdict.REJECTED,digest,size,"APK package mismatch",package=package)
        cert=None;tool=shutil.which("apksigner")
        if tool:
            try:
                out=subprocess.run([tool,"verify","--print-certs",str(p)],capture_output=True,text=True,timeout=self.timeout,check=False)
                if out.returncode:return ScanResult(ScanVerdict.REJECTED,digest,size,"APK signature verification failed",package=package)
                for line in out.stdout.splitlines():
                    if "certificate SHA-256 digest:" in line:cert=line.split(":",1)[1].strip().replace(":","").lower()
            except subprocess.TimeoutExpired:return ScanResult(ScanVerdict.SUSPICIOUS,digest,size,"signature verification timed out",package=package)
        else:return ScanResult(ScanVerdict.SUSPICIOUS,digest,size,"apksigner unavailable; signature cannot be verified",package=package)
        if expected and not cert:return ScanResult(ScanVerdict.SUSPICIOUS,digest,size,"signing certificate fingerprint unavailable",package=package)
        if expected and cert.lower().replace(":","")!=expected.lower().replace(":",""):return ScanResult(ScanVerdict.REJECTED,digest,size,"signing certificate mismatch",package=package,certificate=cert)
        return ScanResult(ScanVerdict.CLEAN,digest,size,"APK package and signature verified",package=package,certificate=cert)
