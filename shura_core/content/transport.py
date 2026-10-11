"""The production transport: :class:`SafeHTTPTransport`.

``PageFetcher`` takes a transport, not an HTTP client, so this is the one place
that binds the fetch stage to the repository's existing guards. It delegates
wholesale to :class:`SafeHTTP` - host allow-listing, HTTPS-only, private-address
refusal, redirect limits and response ceilings are not reimplemented here, so
they cannot drift apart. There is no switch that turns a production guard off.
"""

from __future__ import annotations

from ..security.network import NetworkPolicyError, ResponseTooLarge, SafeHTTP

__all__ = ["SafeHTTPTransport", "NetworkPolicyError", "ResponseTooLarge"]


class SafeHTTPTransport:
    """Adapts :class:`SafeHTTP` to the transport interface ``PageFetcher`` expects.

    :meth:`stream` is the primary path: the response stays open only for as long
    as the caller's ``with`` block, and the body is never fully materialised in
    memory. :meth:`get` is kept for callers that genuinely want the whole body at
    once; the fetcher does not use it.
    """

    def __init__(self, http: SafeHTTP):
        self.http = http

    def stream(self, url: str, *, max_bytes: int, headers: dict | None = None):
        """Return the ``SafeHTTP.stream`` context manager, unentered."""
        return self.http.stream(url, headers=headers, max_bytes=max_bytes)

    def get(self, url: str, headers: dict | None = None):
        """Whole-body fetch. Returns ``(data, mime)``; uses the full ceiling."""
        data, response_headers, _final = self.http.get(url, headers=headers)
        return data, response_headers.get_content_type()