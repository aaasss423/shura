package app.shura.translator.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A minimal HTTP/1.1 server for tests, built on `ServerSocket`.
 *
 * `com.sun.net.httpserver` is not on the Android unit-test classpath, and pulling in
 * an HTTP library purely so a test can assert on the requests it sends would add a
 * dependency for no product benefit. This is ~100 lines and gives the tests a real
 * socket to talk to, so request bodies, headers and status handling are genuinely
 * exercised rather than mocked.
 */
final class TestHttpServer implements AutoCloseable {

    interface Responder {
        /** Returns the status and body for a request. */
        Reply respond(String method, String path, String body) throws Exception;
    }

    static final class Reply {
        final int status;
        final String body;

        Reply(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    private final ServerSocket socket;
    private final Thread acceptor;
    private volatile Responder responder;
    private volatile boolean running = true;

    final List<String> paths = new ArrayList<>();
    final List<String> bodies = new ArrayList<>();

    TestHttpServer(Responder responder) throws IOException {
        this.responder = responder;
        this.socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"));
        this.acceptor = new Thread(this::acceptLoop, "test-http-server");
        this.acceptor.setDaemon(true);
        this.acceptor.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + socket.getLocalPort();
    }

    void setResponder(Responder responder) {
        this.responder = responder;
    }

    void setStatus(int status) {
        Responder previous = this.responder;
        this.responder = (method, path, body) -> {
            Reply reply = previous.respond(method, path, body);
            return new Reply(status, reply.body);
        };
    }

    private void acceptLoop() {
        while (running) {
            try (Socket client = socket.accept()) {
                serve(client);
            } catch (IOException error) {
                if (running) {
                    // A closed socket during shutdown is expected, not a failure.
                    continue;
                }
                return;
            }
        }
    }

    private void serve(Socket client) throws IOException {
        client.setSoTimeout(5000);
        InputStream in = client.getInputStream();
        OutputStream out = client.getOutputStream();

        String requestLine = readLine(in);
        if (requestLine == null || requestLine.isEmpty()) {
            return;
        }
        String[] parts = requestLine.split(" ");
        String method = parts.length > 0 ? parts[0] : "GET";
        String target = parts.length > 1 ? parts[1] : "/";

        int contentLength = 0;
        boolean chunked = false;
        String header;
        while (!(header = readLine(in)).isEmpty()) {
            String lower = header.toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith("content-length:")) {
                contentLength = Integer.parseInt(header.substring(header.indexOf(':') + 1).trim());
            } else if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) {
                chunked = true;
            }
        }

        byte[] payload = readBody(in, contentLength, chunked);
        String body = new String(payload, StandardCharsets.UTF_8);

        String path = target;
        int query = target.indexOf('?');
        if (query >= 0) {
            path = target.substring(0, query);
        }
        paths.add(target);
        bodies.add(body);

        Reply reply;
        try {
            reply = responder.respond(method, path, body);
        } catch (Exception error) {
            reply = new Reply(500, "{\"error\":{\"message\":\"test server failure\"}}");
        }

        byte[] bytes = reply.body.getBytes(StandardCharsets.UTF_8);
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(reply.status).append(' ').append(reason(reply.status)).append("\r\n");
        head.append("Content-Type: application/json; charset=utf-8\r\n");
        head.append("Content-Length: ").append(bytes.length).append("\r\n");
        head.append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(bytes);
        out.flush();
    }

    private static byte[] readBody(InputStream in, int contentLength, boolean chunked) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        if (chunked) {
            while (true) {
                String sizeLine = readLine(in);
                if (sizeLine == null || sizeLine.isEmpty()) {
                    break;
                }
                int size;
                try {
                    size = Integer.parseInt(sizeLine.trim(), 16);
                } catch (NumberFormatException error) {
                    break;
                }
                if (size == 0) {
                    readLine(in);
                    break;
                }
                copy(in, buffer, size);
                readLine(in);
            }
            return buffer.toByteArray();
        }
        if (contentLength > 0) {
            copy(in, buffer, contentLength);
        }
        return buffer.toByteArray();
    }

    private static void copy(InputStream in, ByteArrayOutputStream buffer, int count) throws IOException {
        byte[] chunk = new byte[Math.min(count, 8192)];
        int remaining = count;
        while (remaining > 0) {
            int read = in.read(chunk, 0, Math.min(chunk.length, remaining));
            if (read <= 0) {
                break;
            }
            buffer.write(chunk, 0, read);
            remaining -= read;
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                buffer.write(c);
            }
        }
        if (c == -1 && buffer.size() == 0) {
            return null;
        }
        return buffer.toString("UTF-8");
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 429: return "Too Many Requests";
            case 500: return "Internal Server Error";
            case 503: return "Service Unavailable";
            default: return "Status";
        }
    }

    @Override
    public void close() {
        running = false;
        try {
            socket.close();
        } catch (IOException ignored) {
            // Shutting down anyway.
        }
    }
}
