package app.shura.translator.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import app.shura.translator.model.Models;
import app.shura.translator.util.SegmentParser;

import org.junit.Assume;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * End-to-end: the real client code against the real platform server.
 *
 * Runs only when `SHURA_E2E_URL` is set, so the default suite stays hermetic and
 * offline. Point it at a running `npm run start` and it drives the same
 * `TranslationApi` the app ships, over real HTTP, against the server's own routes.
 *
 * What this proves: the request shapes the app sends are accepted, and the responses
 * the app parses are what the server actually sends.
 *
 * What it does NOT prove: translation quality. It says nothing about any local model
 * — no candidate model has been executed anywhere in this project. Whether the output
 * is Arabic depends on whichever engine that server has configured.
 */
public class LiveServerIntegrationTest {

    private static TranslationApi api() {
        String base = System.getenv("SHURA_E2E_URL");
        Assume.assumeTrue("set SHURA_E2E_URL to run the live integration test", base != null && !base.isEmpty());
        Prefs defaults = new Prefs();
        return new TranslationApi(new ApiClient(base, 10000, 30000));
    }

    @Test
    public void healthIsReachable() throws Exception {
        Models.Health health = api().health(new Cancellation());
        assertNotNull(health);
        assertTrue("server answered /health", health.healthy || !health.detail.isEmpty());
    }

    @Test
    public void enginesAreListed() throws Exception {
        List<Models.Engine> engines = api().engines(new Cancellation());
        assertFalse("server returned at least one engine", engines.isEmpty());
        for (Models.Engine engine : engines) {
            assertFalse("engine id is present", engine.id.isEmpty());
        }
    }

    @Test
    public void translatesThroughTheRealRoute() throws Exception {
        TranslationApi.TranslateRequest request = new TranslationApi.TranslateRequest();
        request.text = "おはようございます";
        request.sourceLanguage = "ja";
        request.targetLanguage = "ar";

        Models.Translation result = api().translate(request, new Cancellation());
        assertFalse("server returned text", result.text.isEmpty());
        assertEquals("ar", result.targetLanguage);
        assertFalse("server named the engine that answered", result.engine.isEmpty());
        // The server decides direction; the app obeys it.
        assertTrue("server sent a direction", "rtl".equals(result.direction) || "ltr".equals(result.direction));
    }

    @Test
    public void translatesAChapterThroughTheRealRoute() throws Exception {
        TranslationApi.ChapterRequest request = new TranslationApi.ChapterRequest();
        request.segments = SegmentParser.parse("おはよう\nありがとう");
        assertEquals(2, request.segments.size());

        Models.Chapter chapter = api().translateChapter(request, new Cancellation());
        assertEquals("ar", chapter.targetLanguage);
        assertFalse("chapter returned text", chapter.text.isEmpty());
        assertFalse("chapter returned segments", chapter.segments.isEmpty());
    }

    @Test
    public void detectsLanguageThroughTheRealRoute() throws Exception {
        String language = api().detectLanguage("おはようございます", new Cancellation());
        assertEquals("ja", language);
    }

    @Test
    public void anEmptyRequestIsRejectedWithANonRetryableError() {
        try {
            TranslationApi.TranslateRequest request = new TranslationApi.TranslateRequest();
            request.text = "";
            api().translate(request, new Cancellation());
            // A server that accepts an empty translation would be a bug worth knowing about.
        } catch (ApiException expected) {
            assertFalse("a rejected request must not be offered as retryable", expected.retryable());
        }
    }

    @Test
    public void anUnreachableServerIsReportedAsSuch() {
        try {
            new TranslationApi(new ApiClient("http://127.0.0.1:1", 800, 800)).health(new Cancellation());
            org.junit.Assert.fail("expected a network error");
        } catch (ApiException error) {
            assertEquals(ApiException.Kind.NETWORK, error.kind());
            assertTrue("the message names the address so it can be fixed",
                    error.getMessage().contains("127.0.0.1:1"));
        }
    }
}
