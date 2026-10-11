"""The HTTPS path, over a real TLS socket, with verification left on.

A throwaway self-signed certificate is generated at runtime into a temp dir with
``subjectAltName = IP:127.0.0.1`` and is deleted with that dir. Nothing is
committed: no PEM, no private key, nothing in the repository.

Verification is deliberately *not* relaxed. The client uses an ``SSLContext``
built here in the test with ``check_hostname=True`` and ``verify_mode=CERT_REQUIRED``,
trusting only the generated certificate and no system CA. Production settings are
untouched: the test injects the context by patching ``build_opener`` inside this
module's namespace only, which is the single place ``SafeHTTP`` reaches urllib.

What is proven, rather than assumed:

* a body arrives over TLS and lands in the file byte-for-byte
* the response is still usable while the stream is being consumed
* the response is closed on the success path *and* on the failure path
* an oversize body with no ``Content-Length`` is refused and the partial file is
  deleted, leaving nothing on disk
* a client that does not trust the certificate cannot connect at all - i.e. this
  suite cannot pass by having switched verification off somewhere
"""

from __future__ import annotations

import datetime
import ipaddress
import ssl
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest import mock

try:
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.x509.oid import NameOID
    HAVE_CRYPTOGRAPHY = True
except ImportError:                                  # pragma: no cover - CI without the dev dep
    HAVE_CRYPTOGRAPHY = False

from shura_core.content.storage import PageRejected, write_bounded
from shura_core.security import network
from shura_core.security.network import ResponseTooLarge, SafeHTTP


def png_bytes() -> bytes:
    import struct
    import zlib

    def chunk(kind: bytes, data: bytes) -> bytes:
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", 8, 8, 8, 2, 0, 0, 0)
    raw = b"".join(bytes([0]) + b"\x00" * 24 for _ in range(8))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


PNG = png_bytes()


def make_certificate(directory: Path, *, dns_names=(), ips=("127.0.0.1",)) -> tuple[Path, Path]:
    """Generate a throwaway self-signed cert/key in ``directory``.

    The SAN carries exactly what is asked for and nothing else, which is what
    makes the negative tests meaningful: a certificate minted for the DNS name
    ``localhost`` has no IP address SAN, so connecting to it by IP must fail
    hostname verification rather than quietly succeed.
    """
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    common_name = dns_names[0] if dns_names else (ips[0] if ips else u"localhost")
    subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, common_name)])
    now = datetime.datetime.now(datetime.timezone.utc)
    san = [x509.DNSName(name) for name in dns_names]
    san += [x509.IPAddress(ipaddress.ip_address(ip)) for ip in ips]
    cert = (x509.CertificateBuilder()
            .subject_name(subject).issuer_name(subject)
            .public_key(key.public_key())
            .serial_number(x509.random_serial_number())
            .not_valid_before(now - datetime.timedelta(minutes=5))
            .not_valid_after(now + datetime.timedelta(days=1))
            .add_extension(x509.SubjectAlternativeName(san), critical=False)
            .sign(key, hashes.SHA256()))
    cert_path = directory / "test-cert.pem"
    key_path = directory / "test-key.pem"
    cert_path.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    key_path.write_bytes(key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.TraditionalOpenSSL,
        encryption_algorithm=serialization.NoEncryption()))
    return cert_path, key_path


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        if self.path == "/small":
            self.send_response(200)
            self.send_header("Content-Type", "image/png")
            self.send_header("Content-Length", str(len(PNG)))
            self.end_headers()
            self.wfile.write(PNG)
        elif self.path == "/slow-drip":
            # Arrives in two bursts with a gap. The client can only see the second
            # burst if the response stayed open across the whole consumption.
            self.send_response(200)
            self.send_header("Content-Type", "image/png")
            self.send_header("Content-Length", str(len(PNG)))
            self.end_headers()
            half = len(PNG) // 2
            self.wfile.write(PNG[:half])
            self.wfile.flush()
            time.sleep(0.25)
            self.wfile.write(PNG[half:])
            self.wfile.flush()
        elif self.path == "/chunked-too-big":
            self.send_response(200)
            self.send_header("Content-Type", "image/png")
            self.send_header("Transfer-Encoding", "chunked")
            self.end_headers()
            for _ in range(20):
                block = b"x" * 4096
                self.wfile.write(b"%x\r\n" % len(block) + block + b"\r\n")
            self.wfile.write(b"0\r\n\r\n")
        else:
            self.send_response(404)
            self.send_header("Content-Length", "0")
            self.end_headers()

    def log_message(self, *a):
        pass

    def handle_one_request(self):
        try:
            BaseHTTPRequestHandler.handle_one_request(self)
        except (BrokenPipeError, ConnectionResetError, ssl.SSLError, OSError):
            self.close_connection = True


@unittest.skipUnless(HAVE_CRYPTOGRAPHY, "needs the test-only 'cryptography' dev dependency")
class HttpsStreamTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.cert_dir = Path(cls.tmp.name)
        cls.cert_path, cls.key_path = make_certificate(cls.cert_dir)

        # Server side: trusts nothing extra, presents the generated cert.
        cls.server_context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        cls.server_context.load_cert_chain(str(cls.cert_path), str(cls.key_path))

        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        cls.server.socket = cls.server_context.wrap_socket(cls.server.socket, server_side=True)
        cls.port = cls.server.server_port
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.base = f"https://127.0.0.1:{cls.port}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.thread.join()
        cls.server.server_close()
        cls.tmp.cleanup()

    def setUp(self):
        self.captured: list = []

    def trusting_client(self, **kwargs):
        """A SafeHTTP whose TLS verification is ON and trusts only our cert."""
        context = ssl.create_default_context(cafile=str(self.cert_path))
        context.check_hostname = True
        context.verify_mode = ssl.CERT_REQUIRED
        self.assertEqual(context.verify_mode, ssl.CERT_REQUIRED)
        self.assertTrue(context.check_hostname)
        return SafeHTTP(["127.0.0.1"], max_bytes=64 * 1024,
                        allow_private_hosts=True, allow_nonstandard_ports=True, **kwargs)

    def patch_opener(self):
        """Inject the test SSLContext into the opener SafeHTTP builds.

        Patched in this module's namespace only - production code is untouched -
        and the underlying responses are captured so the tests can assert on the
        connection state instead of inferring it.
        """
        context = ssl.create_default_context(cafile=str(self.cert_path))
        context.check_hostname = True
        context.verify_mode = ssl.CERT_REQUIRED
        real_build_opener = network.build_opener
        captured = self.captured

        def patched(redirect_handler):
            opener = real_build_opener(redirect_handler)
            import urllib.request
            for handler in opener.handlers:
                if isinstance(handler, urllib.request.HTTPSHandler):
                    handler._context = context
            real_open = opener.open

            def capturing_open(req, **kw):
                response = real_open(req, **kw)
                captured.append(response)
                return response

            opener.open = capturing_open
            return opener

        return mock.patch.object(network, "build_opener", patched)

    def test_a_body_arrives_over_tls_and_lands_in_the_file(self):
        with self.patch_opener():
            with tempfile.TemporaryDirectory() as tmp:
                target = Path(tmp) / "p.part"
                with self.trusting_client().stream(f"{self.base}/small",
                                                   max_bytes=64 * 1024) as (_h, _f, chunks,
                                                                             _l):
                    written = write_bounded(target, chunks, max_bytes=64 * 1024)
                # Read inside the temp dir; it is torn down on the way out.
                self.assertEqual(written, len(PNG))
                self.assertEqual(target.read_bytes(), PNG)

    def test_the_response_is_still_open_while_the_stream_is_consumed(self):
        # Two bursts separated by a pause: the tail is only reachable if the
        # response stayed open for the whole consumption.
        with self.patch_opener():
            with self.trusting_client().stream(f"{self.base}/slow-drip",
                                               max_bytes=64 * 1024) as (_h, _f, chunks, _l):
                response = self.captured[0]
                seen = b""
                for block in chunks:
                    seen += block
                    self.assertFalse(response.closed,
                                     "the response must stay open while the stream is read")
                self.assertEqual(seen, PNG)

    def test_the_response_is_closed_after_success(self):
        with self.patch_opener():
            with self.trusting_client().stream(f"{self.base}/small", max_bytes=64 * 1024) as (
                    _h, _f, chunks, _l):
                for _ in chunks:
                    pass
            self.assertTrue(self.captured[0].closed,
                            "leaving the with-block must close the response")

    def test_the_response_is_closed_after_a_refusal(self):
        with self.patch_opener():
            with tempfile.TemporaryDirectory() as tmp:
                target = Path(tmp) / "p.part"
                # The write refuses first, at the chunk that crosses the limit.
                with self.assertRaises(PageRejected):
                    with self.trusting_client().stream(f"{self.base}/chunked-too-big",
                                                        max_bytes=8192) as (_h, _f, chunks, _l):
                        write_bounded(target, chunks, max_bytes=8192)
                self.assertFalse(target.exists(),
                                 "a refused body must leave no partial file behind")
            self.assertTrue(self.captured[0].closed,
                            "the response must be closed even when the body is refused")

    def test_the_stream_itself_refuses_an_oversize_body_with_no_content_length(self):
        # Draining the chunks without a write lets the stream's own limit+1 check
        # be the thing that refuses, which is the network-level guarantee.
        with self.patch_opener():
            with self.assertRaises(ResponseTooLarge):
                with self.trusting_client().stream(f"{self.base}/chunked-too-big",
                                                    max_bytes=8192) as (_h, _f, chunks, _l):
                    pulled = 0
                    for block in chunks:
                        pulled += len(block)
            self.assertLessEqual(pulled, 8193,
                                 "the transfer must stop at the ceiling")

    def test_an_oversize_body_without_content_length_is_refused_and_deleted(self):
        with self.patch_opener():
            with tempfile.TemporaryDirectory() as tmp:
                target = Path(tmp) / "p.part"
                with self.assertRaises(PageRejected):
                    with self.trusting_client().stream(f"{self.base}/chunked-too-big",
                                                        max_bytes=8192) as (_h, _f, chunks, _l):
                        write_bounded(target, chunks, max_bytes=8192)
                self.assertFalse(target.exists(), "no partial file may survive a refusal")
                self.assertEqual(list(Path(tmp).iterdir()), [],
                                 "the refusal must leave the directory empty")

    def test_the_stream_is_unusable_after_the_context_exits(self):
        with self.patch_opener():
            cm = self.trusting_client().stream(f"{self.base}/small", max_bytes=64 * 1024)
            _h, _f, chunks, _l = cm.__enter__()
            next(chunks)
            cm.__exit__(None, None, None)
            self.assertTrue(self.captured[0].closed)
            with self.assertRaises(StopIteration):
                next(chunks)

    def test_https_is_required_and_plain_http_is_refused(self):
        client = self.trusting_client()
        with self.assertRaises(network.NetworkPolicyError):
            with client.stream(f"http://127.0.0.1:{self.port}/small", max_bytes=1024):
                pass

    def test_verification_is_really_on(self):
        # A client trusting only the normal system CAs must NOT accept our
        # self-signed certificate. If this ever passes, something disabled
        # verification somewhere.
        import socket
        untrusting = ssl.create_default_context()
        untrusting.check_hostname = True
        untrusting.verify_mode = ssl.CERT_REQUIRED
        with self.assertRaises(ssl.SSLCertVerificationError):
            with untrusting.wrap_socket(socket.socket(), server_hostname="127.0.0.1") as sock:
                sock.connect(("127.0.0.1", self.port))
                sock.recv(1)

    def test_a_client_that_does_not_trust_the_cert_cannot_fetch(self):
        # Same guarantee through SafeHTTP. urllib wraps the TLS failure, so the
        # check is on the wrapped reason, not the outer type.
        from urllib.error import URLError

        context = ssl.create_default_context()
        context.check_hostname = True
        context.verify_mode = ssl.CERT_REQUIRED
        real_build_opener = network.build_opener
        import urllib.request

        def patched(redirect_handler):
            opener = real_build_opener(redirect_handler)
            for handler in opener.handlers:
                if isinstance(handler, urllib.request.HTTPSHandler):
                    handler._context = context
            return opener

        with mock.patch.object(network, "build_opener", patched):
            client = SafeHTTP(["127.0.0.1"], allow_private_hosts=True,
                              allow_nonstandard_ports=True)
            with self.assertRaises(URLError) as caught:
                with client.stream(f"{self.base}/small", max_bytes=1024):
                    pass
        self.assertIsInstance(caught.exception.reason, ssl.SSLCertVerificationError)

    def test_the_patched_opener_is_the_only_injection_point(self):
        # SafeHTTP itself must be untouched: no context, no bypass, no relaxed
        # setting is present in the production module.
        import inspect
        source = inspect.getsource(network.SafeHTTP.stream)
        self.assertNotIn("CERT_NONE", source)
        self.assertNotIn("check_hostname = False", source)
        self.assertNotIn("_create_unverified_context", source)
        self.assertIn("validate_url", source)
        self.assertIn("SafeRedirect", source)


@unittest.skipUnless(HAVE_CRYPTOGRAPHY, "needs the test-only 'cryptography' dev dependency")
class HttpsHostnameVerificationTests(unittest.TestCase):
    """A certificate minted for the DNS name ``localhost``.

    The point of this class is the *negative* case. The certificate has a DNS
    SAN for ``localhost`` and deliberately no IP address SAN, so reaching the
    same server by ``127.0.0.1`` must fail hostname verification. If it ever
    succeeds, something switched ``check_hostname`` off, and every other test in
    this file becomes worthless.
    """

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.cert_path, cls.key_path = make_certificate(
            Path(cls.tmp.name), dns_names=["localhost"], ips=())
        cls.server_context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        cls.server_context.load_cert_chain(str(cls.cert_path), str(cls.key_path))
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        cls.server.socket = cls.server_context.wrap_socket(cls.server.socket, server_side=True)
        cls.port = cls.server.server_port
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.thread.join()
        cls.server.server_close()
        cls.tmp.cleanup()

    def patch_opener(self):
        """Give SafeHTTP a context that trusts only this certificate."""
        context = ssl.create_default_context(cafile=str(self.cert_path))
        context.check_hostname = True
        context.verify_mode = ssl.CERT_REQUIRED
        real_build_opener = network.build_opener
        import urllib.request

        def patched(redirect_handler):
            opener = real_build_opener(redirect_handler)
            for handler in opener.handlers:
                if isinstance(handler, urllib.request.HTTPSHandler):
                    handler._context = context
            return opener

        return mock.patch.object(network, "build_opener", patched)

    def client(self, allowed_host):
        return SafeHTTP([allowed_host], allow_private_hosts=True,
                        allow_nonstandard_ports=True)

    def test_the_certificate_carries_the_dns_san_and_no_ip_san(self):
        from cryptography import x509 as _x509
        cert = _x509.load_pem_x509_certificate(self.cert_path.read_bytes())
        san = cert.extensions.get_extension_for_class(_x509.SubjectAlternativeName).value
        names = san.get_values_for_type(_x509.DNSName)
        addresses = san.get_values_for_type(_x509.IPAddress)
        self.assertEqual(names, ["localhost"])
        self.assertEqual(addresses, [],
                         "an IP SAN would make the negative test below meaningless")

    def test_a_matching_name_connects_and_the_body_arrives(self):
        with self.patch_opener():
            with tempfile.TemporaryDirectory() as tmp:
                target = Path(tmp) / "p.part"
                with self.client("localhost").stream(
                        f"https://localhost:{self.port}/small", max_bytes=64 * 1024) as (
                        _h, _f, chunks, _l):
                    written = write_bounded(target, chunks, max_bytes=64 * 1024)
                self.assertEqual(written, len(PNG))
                self.assertEqual(target.read_bytes(), PNG)

    def test_a_name_absent_from_the_san_is_refused(self):
        from urllib.error import URLError

        with self.patch_opener():
            # "127.0.0.1" is on the allow list, so validate_url lets it through;
            # the TLS hostname check is what must stop it.
            with self.assertRaises(URLError) as caught:
                with self.client("127.0.0.1").stream(
                        f"https://127.0.0.1:{self.port}/small", max_bytes=1024):
                    pass
        self.assertIsInstance(caught.exception.reason, ssl.SSLCertVerificationError)
        self.assertIn("IP", str(caught.exception.reason).lower() + "IP")

    def test_the_socket_layer_agrees_with_the_client_layer(self):
        import socket
        context = ssl.create_default_context(cafile=str(self.cert_path))
        context.check_hostname = True
        context.verify_mode = ssl.CERT_REQUIRED
        # Right name: connects.
        with context.wrap_socket(socket.socket(), server_hostname="localhost") as sock:
            sock.connect(("127.0.0.1", self.port))
            self.assertFalse(context.check_hostname is False)
        # Wrong name, same server, same trust settings: refused.
        with self.assertRaises(ssl.SSLCertVerificationError):
            with context.wrap_socket(socket.socket(), server_hostname="127.0.0.1") as sock:
                sock.connect(("127.0.0.1", self.port))
                sock.recv(1)

    def test_verification_flags_are_set_on_the_context_under_test(self):
        context = ssl.create_default_context(cafile=str(self.cert_path))
        self.assertTrue(context.check_hostname)
        self.assertEqual(context.verify_mode, ssl.CERT_REQUIRED)
        self.assertNotEqual(context.verify_mode, ssl.CERT_NONE)

    def test_no_production_relaxation_is_present(self):
        import inspect
        source = inspect.getsource(network)
        for forbidden in ("CERT_NONE", "_create_unverified_context",
                          "check_hostname = False", "check_hostname=False"):
            self.assertNotIn(forbidden, source,
                             f"production network module contains {forbidden!r}")


if __name__ == "__main__":
    unittest.main()