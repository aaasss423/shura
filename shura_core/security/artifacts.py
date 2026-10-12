from dataclasses import dataclass
from enum import StrEnum
from hashlib import sha256
from pathlib import Path
from typing import Protocol
import os, re, shutil, subprocess, zipfile

from .axml import ManifestError, manifest_of

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
    def __init__(self,max_size=50_000_000,expected_certificate=None,timeout=15,max_uncompressed=150_000_000,max_entries=10000,build_tools=None,java_home=None):
        # Where aapt2/apksigner live when they are not on PATH. Without this the
        # signer is never found, the fingerprint comes back empty, and a
        # correctly signed APK is reported unverified - which is how a trust
        # gate gets quietly switched off.
        self.build_tools=build_tools;self.java_home=java_home
        self.max_size=max_size;self.expected=expected_certificate;self.timeout=timeout;self.max_uncompressed=max_uncompressed;self.max_entries=max_entries
    def _tool(self,name):
        """A build tool by name: the configured directory first, then PATH."""
        if self.build_tools:
            candidate=Path(self.build_tools)/name
            if candidate.exists():return str(candidate)
        return shutil.which(name)

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
        # Read from the manifest in the archive first. aapt2 is a fallback, not
        # the source: where it is not installed the package came back unread and
        # a perfectly well-formed APK was reported SUSPICIOUS because it could
        # not be *verified* - a gate that cannot tell "unverified" from "fine"
        # gets disabled, and disabling it is worse than the gap it covered.
        package=None;note=None
        try:
            package=manifest_of(p).package or None
        except ManifestError as exc:
            note=f"manifest could not be read: {exc}"
        if not package:
            aapt=self._tool("aapt2") or self._tool("aapt")
            if aapt:
                try:
                    out=subprocess.run([aapt,"dump","badging",str(p)],capture_output=True,text=True,timeout=self.timeout,check=False)
                    if out.returncode==0:
                        match=re.search(r"^package: name='([^']+)'",out.stdout,re.M)
                        package=match.group(1) if match else None
                    else:note="aapt could not parse the manifest"
                except subprocess.TimeoutExpired:note="manifest package parsing timed out"
            elif not note:note="no manifest reader available"
        if expected_package and not package:return ScanResult(ScanVerdict.SUSPICIOUS,digest,size,f"APK manifest package could not be read; package cannot be verified ({note or 'unknown reason'})")
        if expected_package and package!=expected_package:return ScanResult(ScanVerdict.REJECTED,digest,size,"APK package mismatch",package=package)
        cert=None;tool=self._tool("apksigner")
        if tool:
            try:
                env=dict(os.environ)
                if self.java_home:
                    # apksigner resolves `java` from PATH, and the default JDK on
                    # this image cannot mark memory pages executable - it then
                    # fails as though the signature were the problem.
                    env["JAVA_HOME"]=str(self.java_home)
                    env["PATH"]=str(Path(self.java_home)/"bin")+os.pathsep+env.get("PATH","")
                out=subprocess.run([tool,"verify","--print-certs",str(p)],capture_output=True,text=True,timeout=self.timeout,check=False,env=env)
                if out.returncode:return ScanResult(ScanVerdict.REJECTED,digest,size,"APK signature verification failed",package=package)
                for line in out.stdout.splitlines():
                    if "certificate SHA-256 digest:" in line:cert=line.split(":",1)[1].strip().replace(":","").lower()
            except subprocess.TimeoutExpired:return ScanResult(ScanVerdict.SUSPICIOUS,digest,size,"signature verification timed out",package=package)
        else:return ScanResult(ScanVerdict.SUSPICIOUS,digest,size,"apksigner unavailable; signature cannot be verified",package=package)
        if expected and not cert:return ScanResult(ScanVerdict.SUSPICIOUS,digest,size,"signing certificate fingerprint unavailable",package=package)
        if expected and cert.lower().replace(":","")!=expected.lower().replace(":",""):return ScanResult(ScanVerdict.REJECTED,digest,size,"signing certificate mismatch",package=package,certificate=cert)
        return ScanResult(ScanVerdict.CLEAN,digest,size,"APK package and signature verified",package=package,certificate=cert)
