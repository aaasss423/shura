from __future__ import annotations
from contextlib import contextmanager
from urllib.request import Request, build_opener, HTTPRedirectHandler
from urllib.parse import urlparse
from ipaddress import ip_address, ip_network

class NetworkPolicyError(ValueError): pass

class ResponseTooLarge(NetworkPolicyError):
    """The response body is bigger than the ceiling.

    Deliberately a *subclass* of :class:`NetworkPolicyError`: every existing
    ``except NetworkPolicyError`` keeps working, while callers that must tell
    "this request was refused" from "this was too big" can. The two are not the
    same event - one is a security refusal, the other is a size budget.
    """

_PRIVATE_NETWORKS=tuple(ip_network(n) for n in (
    "0.0.0.0/8","10.0.0.0/8","100.64.0.0/10","127.0.0.0/8","169.254.0.0/16",
    "172.16.0.0/12","192.0.0.0/24","192.0.2.0/24","192.168.0.0/16","198.18.0.0/15",
    "198.51.100.0/24","203.0.113.0/24","224.0.0.0/4","240.0.0.0/4","255.255.255.255/32",
    "::1/128","fc00::/7","fe80::/10","ff00::/8",
))

#: Granularity of a streamed read. Peak memory during a fetch is one of these,
#: not the whole response.
_READ_CHUNK = 64 * 1024

def is_private_host(host):
    """True for loopback/link-local/private/reserved IP literals and localhost names."""
    h=(host or "").strip("[]").lower()
    if not h:return True
    if h=="localhost" or h.endswith(".localhost"):return True
    try:ip=ip_address(h)
    except ValueError:return False
    return any(ip in network for network in _PRIVATE_NETWORKS)

class SafeRedirect(HTTPRedirectHandler):
    def __init__(self,allowed,max_redirects,https_only=True,allow_nonstandard_ports=False,allow_private_hosts=False,seed_url=None): self.allowed=set(allowed);self.max_redirects=max_redirects;self.https_only=https_only;self.allow_nonstandard_ports=allow_nonstandard_ports;self.allow_private_hosts=allow_private_hosts;self.count=0;self.seen={seed_url} if seed_url else set()
    def redirect_request(self,req,fp,code,msg,headers,newurl):
        self.count+=1
        if self.count>self.max_redirects: raise NetworkPolicyError("redirect limit exceeded")
        if newurl in self.seen: raise NetworkPolicyError("redirect loop detected")
        self.seen.add(newurl);validate_url(newurl,self.allowed,self.https_only,self.allow_nonstandard_ports,self.allow_private_hosts)
        return super().redirect_request(req,fp,code,msg,headers,newurl)
def host_allowed(host,allowed_hosts):
    """Exact match, unless an entry opts into subdomains with a leading dot.

    Most sources are one fixed hostname, and for those an entry is still matched
    exactly - a plain ``example.org`` never admits ``evil.example.org``. Some
    sources however serve assets from a per-chapter CDN host that rotates
    (MangaDex hands out a fresh ``cmxdXXXX.mangadex.network`` for each chapter).
    An operator who trusts such a domain writes ``.mangadex.network``: the
    leading dot is the explicit opt-in, and it covers that domain and its
    subdomains only. It does not weaken anything else - scheme, credentials,
    port and private-address checks are applied unchanged afterwards.
    """
    host=(host or "").lower()
    for entry in allowed_hosts:
        entry=(entry or "").strip().lower()
        if not entry:continue
        if entry.startswith("."):
            base=entry[1:]
            if host==base or host.endswith("."+base):return True
        elif host==entry:return True
    return False
def validate_url(url,allowed_hosts,https_only=True,allow_nonstandard_ports=False,allow_private_hosts=False):
    p=urlparse(url);host=(p.hostname or "").lower()
    if p.scheme not in (("https",) if https_only else ("https","http")):raise NetworkPolicyError("only HTTPS URLs are allowed")
    if not host or not host_allowed(host,allowed_hosts):raise NetworkPolicyError(f"host is not allowed: {host}")
    if p.username or p.password:raise NetworkPolicyError("URL credentials are forbidden")
    if not allow_nonstandard_ports and p.port not in (None,443) and not (not https_only and p.port==80):raise NetworkPolicyError("non-standard HTTP port is forbidden")
    if not allow_private_hosts and is_private_host(host):raise NetworkPolicyError("private/reserved host is forbidden")
    return url
class SafeHTTP:
    def __init__(self,allowed_hosts,timeout=15,max_bytes=8_000_000,max_redirects=5,https_only=True,allow_nonstandard_ports=False,allow_private_hosts=False):
        self.allowed=set(allowed_hosts);self.timeout=max(0.1,min(float(timeout),60.0));self.max_bytes=max(1,min(int(max_bytes),20_000_000));self.max_redirects=max(0,min(int(max_redirects),10));self.https_only=https_only;self.allow_nonstandard_ports=allow_nonstandard_ports;self.allow_private_hosts=allow_private_hosts
    def get(self,url,headers=None,max_bytes=None):
        validate_url(url,self.allowed,self.https_only,self.allow_nonstandard_ports,self.allow_private_hosts);redirect=SafeRedirect(self.allowed,self.max_redirects,self.https_only,self.allow_nonstandard_ports,self.allow_private_hosts,url);opener=build_opener(redirect)
        req=Request(url,headers={"User-Agent":"Shura/0.1 (+source-validation)",**(headers or {})})
        with opener.open(req,timeout=self.timeout) as response:
            final=response.geturl();validate_url(final,self.allowed,self.https_only,self.allow_nonstandard_ports,self.allow_private_hosts)
            size=int(response.headers.get("Content-Length","0") or 0);limit=min(max_bytes or self.max_bytes,self.max_bytes)
            if size>limit:raise ResponseTooLarge("response exceeds size limit")
            data=response.read(limit+1)
            if len(data)>limit:raise ResponseTooLarge("response exceeds size limit")
            return data,response.headers,final

    @contextmanager
    def stream(self,url,headers=None,max_bytes=None):
        """Yield ``(headers, final_url, chunks, limit)`` with the response still open.

        Every guard :meth:`get` applies is applied here, in the same order and
        before the first byte is handed out. The chunks are pulled from the socket
        as they are consumed, so peak memory is one chunk rather than the whole
        body - that is the whole point, and it is why ``get`` is not simply
        reused here.

        The response stays open for as long as the caller's ``with`` block runs:
        this generator's own ``with opener.open(...)`` is what closes it, on the
        normal exit and on any exception thrown back in.
        """
        validate_url(url,self.allowed,self.https_only,self.allow_nonstandard_ports,self.allow_private_hosts);redirect=SafeRedirect(self.allowed,self.max_redirects,self.https_only,self.allow_nonstandard_ports,self.allow_private_hosts,url);opener=build_opener(redirect)
        req=Request(url,headers={"User-Agent":"Shura/0.1 (+source-validation)",**(headers or {})})
        with opener.open(req,timeout=self.timeout) as response:
            final=response.geturl();validate_url(final,self.allowed,self.https_only,self.allow_nonstandard_ports,self.allow_private_hosts)
            limit=min(max_bytes or self.max_bytes,self.max_bytes)
            size=int(response.headers.get("Content-Length","0") or 0)
            if size>limit:raise ResponseTooLarge("response exceeds size limit")
            def _chunks():
                # Read one byte past the limit so an undeclared oversize body is
                # detected, exactly as read(limit+1) does, but never all at once.
                remaining=limit+1
                while remaining>0:
                    block=response.read(min(_READ_CHUNK,remaining))
                    if not block:break
                    remaining-=len(block);yield block
                # limit+1 bytes went by. Confirm there is genuinely more before
                # refusing, so a body that ends exactly at the limit still passes.
                if remaining==0 and response.read(1):
                    raise ResponseTooLarge("response exceeds size limit")
            yield response.headers,final,_chunks(),limit
