package app.shura.translator.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import app.shura.translator.model.Models;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Exercises the API client against a real HTTP server.
 *
 * Nothing is mocked: a JDK `HttpServer` is bound to a loopback port and the client
 * speaks actual HTTP to it. A mocked transport would pass while the request body,
 * headers or status handling were wrong — those are exactly the parts that break a
 * client against a real server.
 *
 * The response bodies below are copied from the platform's own serializers
 * (`serializeTranslation`, `serializeChapter`, `/engines`, `/health`).
 */
public class TranslationApiTest {

    private TestHttpServer server;
    private String base;
    /** Bodies the server received, for asserting what was actually sent. */
    private final List<String> receivedBodies = new ArrayList<>();
    private final List<String> receivedPaths = new ArrayList<>();

    @Before
    public void startServer() throws Exception {
        server = new TestHttpServer((method, path, body) -> {
            receivedPaths.add(path);
            receivedBodies.add(body);
            return new TestHttpServer.Reply(200, responseFor(path));
        });
        base = server.baseUrl();
    }

    @After
    public void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    /** Bodies copied from the server's serializers. */
    private String responseFor(String path) {
        if (path.equals("/health")) {
            return "{\"status\":\"ok\",\"engine\":\"mymemory\",\"uptimeSeconds\":42}";
        }
        if (path.equals("/engines")) {
            return "{\"engines\":[{\"id\":\"mymemory\",\"name\":\"MyMemory\",\"available\":true},"
                    + "{\"id\":\"deepl\",\"name\":\"DeepL\",\"available\":false,\"reason\":\"DEEPL_API_KEY is not set\"}],"
                    + "\"routing\":{}}";
        }
        if (path.equals("/engine/health")) {
            return "{\"engine\":\"mymemory\",\"healthy\":true,\"detail\":\"ok\",\"latencyMs\":12}";
        }
        if (path.equals("/translate")) {
            return "{\"text\":\"صباح الخير\",\"rawText\":\"صباح الخير\",\"sourceLanguage\":\"ja\","
                    + "\"targetLanguage\":\"ar\",\"engine\":\"mymemory\",\"fromCache\":false,"
                    + "\"segments\":[{\"index\":0,\"source\":\"おはよう\",\"target\":\"صباح الخير\"}],"
                    + "\"elapsedMs\":812,\"direction\":\"rtl\",\"quality\":null}";
        }
        if (path.equals("/translate/chapter")) {
            return "{\"text\":\"أ\\nب\",\"rawText\":\"أ\\nب\",\"targetLanguage\":\"ar\",\"engine\":\"mymemory\","
                    + "\"segments\":[{\"index\":0,\"source\":\"あ\",\"target\":\"أ\"},"
                    + "{\"index\":1,\"source\":\"い\",\"target\":\"ب\"}],"
                    + "\"progress\":{},\"elapsedMs\":2400,\"degraded\":false,\"direction\":\"rtl\"}";
        }
        if (path.equals("/detect-language")) {
            return "{\"language\":\"ja\",\"confidence\":0.99,\"direction\":\"ltr\"}";
        }
        return "{}";
    }

    private TranslationApi api() {
        return new TranslationApi(new ApiClient(base, 4000, 4000));
    }

    @Test
    public void normalizesBaseUrlSoPathsNeverDoubleSlash() {
        assertEquals("http://host:8080", ApiClient.normalizeBase("http://host:8080/"));
        assertEquals("http://host:8080", ApiClient.normalizeBase("  http://host:8080///  "));
        assertEquals("", ApiClient.normalizeBase(null));
    }

    @Test
    public void readsHealthFromTheRealShape() throws Exception {
        Models.Health health = api().health(new Cancellation());
        assertTrue(health.healthy);
        assertTrue(health.detail.contains("mymemory"));
    }

    @Test
    public void readsEnginesIncludingAnUnavailableOne() throws Exception {
        List<Models.Engine> engines = api().engines(new Cancellation());
        assertEquals(2, engines.size());
        assertEquals("mymemory", engines.get(0).id);
        assertTrue(engines.get(0).available);
        assertFalse(engines.get(1).available);
        assertTrue(engines.get(1).reason.contains("DEEPL_API_KEY"));
    }

    @Test
    public void translatesAndParsesEveryFieldTheServerSends() throws Exception {
        TranslationApi.TranslateRequest request = new TranslationApi.TranslateRequest();
        request.text = "おはよう";
        request.sourceLanguage = "ja";
        request.targetLanguage = "ar";
        request.engine = "mymemory";
        request.contextBefore = "前の行";

        Models.Translation result = api().translate(request, new Cancellation());

        assertEquals("صباح الخير", result.text);
        assertEquals("ja", result.sourceLanguage);
        assertEquals("mymemory", result.engine);
        assertFalse(result.fromCache);
        assertEquals(812, result.elapsedMs);
        assertTrue("server sent direction=rtl, so the client must not guess", result.isRtl());
        assertEquals(1, result.segments.size());
        assertEquals("おはよう", result.segments.get(0).source);
        assertEquals("صباح الخير", result.segments.get(0).target);

        String sent = receivedBodies.get(0);
        assertTrue(sent.contains("\"text\":\"おはよう\""));
        assertTrue(sent.contains("\"sourceLanguage\":\"ja\""));
        assertTrue(sent.contains("\"contextBefore\":\"前の行\""));
    }

    @Test
    public void omitsEmptyOptionalFieldsSoTheServerSeesItsDefaults() throws Exception {
        TranslationApi.TranslateRequest request = new TranslationApi.TranslateRequest();
        request.text = "hello";
        api().translate(request, new Cancellation());
        String sent = receivedBodies.get(0);
        assertFalse("an unset engine must not be sent at all", sent.contains("\"engine\""));
        assertFalse(sent.contains("contextBefore"));
    }

    @Test
    public void translatesAChapterAndReportsDegradation() throws Exception {
        TranslationApi.ChapterRequest request = new TranslationApi.ChapterRequest();
        request.segments = Arrays.asList("あ", "い");
        request.sourceLanguage = "ja";
        Models.Chapter chapter = api().translateChapter(request, new Cancellation());

        assertEquals(2, chapter.segments.size());
        assertEquals("أ", chapter.segments.get(0).target);
        assertFalse(chapter.degraded);
        assertTrue(receivedBodies.get(0).contains("\"segments\":[\"あ\",\"い\"]"));
        assertTrue(receivedBodies.get(0).contains("\"failurePolicy\":\"partial\""));
    }

    @Test
    public void parsesA503FromEngineHealthAsAnAnswerRatherThanAFailure() throws Exception {
        server.setResponder((method, path, body) -> new TestHttpServer.Reply(503,
                "{\"engine\":\"deepl\",\"healthy\":false,\"detail\":\"quota exhausted\"}"));
        Models.Health health = api().engineHealth("deepl", new Cancellation());
        assertFalse(health.healthy);
    }

    @Test
    public void mapsAServerErrorToANonRetryableFailureWhenTheRequestIsWrong() {
        ApiException error = ApiException.fromStatus(400,
                "{\"error\":{\"code\":\"VALIDATION_ERROR\",\"message\":\"text must be a non-empty string\"}}");
        assertEquals(400, error.status());
        assertEquals("VALIDATION_ERROR", error.code());
        assertEquals("text must be a non-empty string", error.getMessage());
        assertFalse("a 400 cannot succeed on retry", error.retryable());
    }

    @Test
    public void mapsServerAndThrottleFailuresToRetryable() {
        assertTrue(ApiException.fromStatus(503, "{}").retryable());
        assertTrue(ApiException.fromStatus(429, "{}").retryable());
        assertTrue(ApiException.fromStatus(408, "{}").retryable());
        assertFalse(ApiException.fromStatus(401, "{}").retryable());
    }

    @Test
    public void reportsANonJsonResponseAsAProtocolErrorNamingTheLikelyCause() {
        // A user who pasted the wrong port, or pointed at a proxy, gets HTML here.
        // The message has to say what to check, not just "parse error".
        server.setResponder((method, path, body) -> new TestHttpServer.Reply(200, "<html>hello</html>"));
        try {
            api().health(new Cancellation());
            fail("expected a protocol error");
        } catch (ApiException error) {
            assertEquals(ApiException.Kind.PROTOCOL, error.kind());
            assertTrue(error.getMessage().contains("did not return JSON"));
            assertTrue(error.retryable());
        }
    }

    @Test
    public void readsAServerErrorEnvelopeOnA400() throws Exception {
        server.setResponder((method, path, body) -> new TestHttpServer.Reply(400,
                "{\"error\":{\"code\":\"VALIDATION_ERROR\",\"message\":\"text must be a non-empty string\"}}"));
        try {
            api().translate(new TranslationApi.TranslateRequest(), new Cancellation());
            fail("expected a server error");
        } catch (ApiException error) {
            assertEquals(400, error.status());
            assertEquals("VALIDATION_ERROR", error.code());
            assertFalse(error.retryable());
        }
    }

    @Test
    public void reportsAnUnreachableServerWithTheAddressInTheMessage() {
        // Port 1 is not listening; the message must name the URL so a user can fix it.
        try {
            new TranslationApi(new ApiClient("http://127.0.0.1:1", 600, 600))
                    .health(new Cancellation());
            fail("expected an ApiException");
        } catch (ApiException error) {
            assertEquals(ApiException.Kind.NETWORK, error.kind());
            assertTrue(error.getMessage().contains("127.0.0.1:1"));
        }
    }

    @Test
    public void cancelledCallThrowsCancelledRatherThanHanging() {
        Cancellation cancellation = new Cancellation();
        cancellation.cancel();
        try {
            new TranslationApi(new ApiClient("http://127.0.0.1:1", 600, 600))
                    .translate(new TranslationApi.TranslateRequest(), cancellation);
            fail("expected cancellation to be reported");
        } catch (ApiException error) {
            assertTrue(error.isCancelled());
        }
    }

    @Test
    public void cancellationBeforeTheRequestPreventsAnyNetworkCall() {
        Cancellation cancellation = new Cancellation();
        cancellation.cancel();
        try {
            api().translate(new TranslationApi.TranslateRequest(), cancellation);
            fail("expected cancellation");
        } catch (ApiException error) {
            assertTrue(error.isCancelled());
        }
        assertTrue("a pre-cancelled call must not hit the network", receivedPaths.isEmpty());
    }

    @Test
    public void cancellationIsIdempotent() {
        Cancellation cancellation = new Cancellation();
        int[] aborts = {0};
        cancellation.onCancel(() -> aborts[0]++);
        cancellation.cancel();
        cancellation.cancel();
        cancellation.cancel();
        assertEquals("double taps must not run the abort handler repeatedly", 1, aborts[0]);
    }

    @Test
    public void detectsLanguageFromTheRealShape() throws Exception {
        assertEquals("ja", api().detectLanguage("おはよう", new Cancellation()));
    }
}
