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

def _sdk_roots():
    """Android SDK locations to look in, most explicit first."""
    seen=[]
    for raw in (os.environ.get("SHURA_BUILD_TOOLS"),os.environ.get("ANDROID_HOME"),os.environ.get("ANDROID_SDK_ROOT")):
        if raw:
            p=Path(raw)
            # SHURA_BUILD_TOOLS may point straight at a build-tools directory.
            seen.append(p)
            if p.name and p.name[0].isdigit():seen.append(p.parent)
    seen+=[Path("/opt/android-sdk"),Path.home()/"Android"/"Sdk",Path("/usr/lib/android-sdk")]
    out=[]
    for p in seen:
        if p not in out:out.append(p)
    return out

def _discover_build_tools():
    """The newest build-tools directory that actually ships apksigner.

    Without this the signer is never found and every APK is reported
    unverified, which quarantines the whole pipeline rather than failing it."""
    for root in _sdk_roots():
        if root.name and root.name[0].isdigit() and (root/"apksigner").exists():return root
        bt=root/"build-tools"
        if not bt.is_dir():continue
        # Highest version last, so inspect in reverse numeric order.
        versions=sorted((d for d in bt.iterdir() if d.is_dir()),key=lambda d:_version_key(d.name))
        for d in reversed(versions):
            if (d/"apksigner").exists():return d
    return None

def _version_key(name):
    out=[]
    for part in re.split(r"[._-]",name):
        out.append((0,int(part),"") if part.isdigit() else (1,0,part))
    return out

def _jdk_works(java_home):
    """apksigner shells out to java. A JDK that cannot start is indistinguishable
    from a bad signature, so a candidate JDK is only accepted once it has run."""
    java=Path(java_home)/"bin"/("java.exe" if os.name=="nt" else "java")
    if not java.exists():return False
    try:
        return subprocess.run([str(java),"-version"],capture_output=True,timeout=15,check=False).returncode==0
    except (OSError,subprocess.TimeoutExpired):return False

def _discover_java_home():
    """A JDK that actually runs, most explicit first."""
    for raw in (os.environ.get("SHURA_JAVA_HOME"),os.environ.get("JAVA_HOME")):
        if raw and _jdk_works(raw):return Path(raw)
    roots=[Path("/usr/lib/jvm"),Path("/usr/java"),Path("/opt/java"),Path.home()/"jdk"]
    candidates=[]
    for root in roots:
        if not root.is_dir():continue
        for d in sorted(root.iterdir()):
            if d.is_dir() and d.name!="default-jvm":candidates.append(d)
        if (root/"default-jvm").is_dir():candidates.append(root/"default-jvm")
    # 17 first: it is the version this project's Gradle build targets, and it is
    # the one that runs on hardened images where a newer JVM refuses to start.
    candidates.sort(key=lambda d:("17" not in d.name,d.name))
    for d in candidates:
        if _jdk_works(d):return d
    return None

_TOOL_CACHE={}
def _resolved(build_tools,java_home):
    """Resolve each unset argument by discovery, once per process.

    Each side is decided on its own. An early return when ``build_tools`` was
    given also skipped JDK discovery, and the CLI passes a build-tools literal by
    default - so the normal path silently lost the JDK fixup and every APK
    failed as a bad signature when the real cause was a JVM that cannot start."""
    if build_tools is not None and java_home is not None:
        return build_tools,java_home
    if "tools" not in _TOOL_CACHE:
        _TOOL_CACHE["tools"]=(_discover_build_tools(),_discover_java_home())
    found_tools,found_java=_TOOL_CACHE["tools"]
    return build_tools or found_tools, java_home if java_home is not None else found_java

# Only the signer is ever discovered. The manifest is read straight out of the
# archive by axml, with aapt merely a fallback, so discovering aapt2 would
# override a caller that put its own aapt on PATH and gain nothing.
DISCOVERED_TOOLS=("apksigner",)

class ArtifactScanner:
    """APK structure, manifest package, and signing checks. This is not malware detection."""
    def __init__(self,max_size=50_000_000,expected_certificate=None,timeout=15,max_uncompressed=150_000_000,max_entries=10000,build_tools=None,java_home=None):
        # Where aapt2/apksigner live when they are not on PATH. Without this the
        # signer is never found, the fingerprint comes back empty, and a
        # correctly signed APK is reported unverified - which is how a trust
        # gate gets quietly switched off. An explicit argument always wins; only
        # the unset case is discovered, so a caller can still force "no tools".
        # Whether the directory was the caller's choice decides lookup order below.
        explicit=build_tools is not None
        build_tools,java_home=_resolved(build_tools,java_home)
        self.build_tools=build_tools;self.java_home=java_home;self._tools_explicit=explicit
        self.max_size=max_size;self.expected=expected_certificate;self.timeout=timeout;self.max_uncompressed=max_uncompressed;self.max_entries=max_entries

    def _tool(self,name):
        """A build tool by name.

        An explicitly configured directory comes first, then PATH, then - for
        the signer only - whatever discovery found. A caller that put its own
        tool on PATH meant that one to be used, so discovery never outranks it."""
        if self._tools_explicit and self.build_tools:
            candidate=Path(self.build_tools)/name
            if candidate.exists():return str(candidate)
        found=shutil.which(name)
        if found:return found
        if self.build_tools and name in DISCOVERED_TOOLS:
            candidate=Path(self.build_tools)/name
            if candidate.exists():return str(candidate)
        return None

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
            except OSError as exc:return ScanResult(ScanVerdict.SUSPICIOUS,digest,size,f"signature verification could not run: {exc}",package=package)
        else:return ScanResult(ScanVerdict.SUSPICIOUS,digest,size,"apksigner unavailable; signature cannot be verified",package=package)
        if expected and not cert:return ScanResult(ScanVerdict.SUSPICIOUS,digest,size,"signing certificate fingerprint unavailable",package=package)
        if expected and cert.lower().replace(":","")!=expected.lower().replace(":",""):return ScanResult(ScanVerdict.REJECTED,digest,size,"signing certificate mismatch",package=package,certificate=cert)
        return ScanResult(ScanVerdict.CLEAN,digest,size,"APK package and signature verified",package=package,certificate=cert)
