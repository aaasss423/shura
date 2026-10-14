package app.shura.manga.reader.translation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Talks to a running Shura translator over HTTP.
 *
 * ## The contract it binds to
 *
 * Shura's translator already serves this API, and these are its real routes -
 * not a Shura-invented shape:
 *
 *   GET  /health                       -> { status, engine, uptimeSeconds }
 *   GET  /languages                    -> supported language codes
 *   GET  /engine/health?engine=        -> { healthy, ... } or 503
 *   POST /translate                    -> one string in, one string out
 *   POST /translate/chapter            -> { segments[] } in, per-segment results out
 *
 * The chapter route is the one the reader uses: a page of manga is several
 * lines, and translating them as one chapter is what keeps the context between
 * them. Each segment carries the page it came from, so a result can be put back
 * on the page it belongs to.
 *
 * ## Where the credentials are
 *
 * Nowhere near here. Engine keys (DeepL and the rest) live in the translator's
 * vault; this client is handed a URL and nothing else. There is no field in
 * which a provider key could be put, which is the point.
 *
 * ## Where the address comes from
 *
 * Configuration, and deliberately not a hardcoded localhost. `127.0.0.1` on a
 * phone is the phone. A debug build points at the emulator's host loopback;
 * a release build must be given a real HTTPS address, and this class refuses to
 * talk to a plain-HTTP address outside debug rather than shipping credentials
 * or page text in the clear.
 */
class HttpTranslationEngine(
    private val baseUrl: String,
    /** Debug builds may use a cleartext loopback; releases may not. */
    private val allowCleartext: Boolean = false,
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 20_000,
    private val engine: String? = null,
) : TranslationEngine {

    @Volatile
    private var cancelled = false

    override fun cancel() {
        cancelled = true
    }

    private fun resume() {
        cancelled = false
    }

    private fun requireSecureEndpoint() {
        val url = baseUrl.trim().lowercase()
        if (url.startsWith("https://")) return
        if (allowCleartext && (url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost"))) return
        throw IOException(
            "the translation endpoint must be HTTPS (got $baseUrl); a cleartext address " +
                "is only allowed for a debug build on the loopback interface"
        )
    }

    override suspend fun availability(): TranslationEngineAvailability =
        withContext(Dispatchers.IO) {
            try {
                requireSecureEndpoint()
                val health = JSONObject(get("/engine/health${engine?.let { "?engine=$it" } ?: ""}"))
                val healthy = health.optBoolean("healthy", false)
                val languages = runCatching {
                    val array = JSONArray(get("/languages"))
                    (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
                }.getOrDefault(emptyList())
                TranslationEngineAvailability(
                    reachable = healthy,
                    engine = health.optString("engine", ""),
                    detail = health.optString("detail")
                        ?: health.optString("reason")
                        ?: health.optString("error")
                        ?: "",
                    languages = languages,
                    // Shura's engine is a text engine. It does not read pages.
                    imageSupport = false,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TranslationEngineAvailability(reachable = false, detail = e.message ?: "unreachable")
            }
        }

    override suspend fun translateChapter(
        request: TranslationChapterRequest,
    ): TranslationChapterResult = withContext(Dispatchers.IO) {
        resume()
        try {
            requireSecureEndpoint()
            if (request.segments.isEmpty()) {
                return@withContext TranslationChapterResult(segments = emptyList())
            }
            val payload = JSONObject()
                .put("segments", JSONArray().apply {
                    request.segments.forEach { segment ->
                        put(JSONObject().put("id", "p${segment.pageIndex}").put("text", segment.text))
                    }
                })
                .put("sourceLanguage", request.sourceLanguage)
                .put("targetLanguage", request.targetLanguage)
                .put("failurePolicy", request.failurePolicy.name.lowercase())
                .put("deadlineMs", request.deadlineMs)
            engine?.let { payload.put("engine", it) }

            val body = post("/translate/chapter", payload.toString())
            coroutineContext.ensureActive()
            parseChapter(request, JSONObject(body))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TranslationChapterResult(
                segments = request.segments.map {
                    TranslationSegmentResult(it.pageIndex, null, e.message ?: "translation failed")
                },
                error = e.message ?: "translation failed",
            )
        }
    }

    /**
     * Maps the engine's own response onto the reader's model.
     *
     * The segment ids are the page indexes this client sent, so they are
     * authoritative for putting a result back on its page; a segment the engine
     * omitted becomes a failure carrying the engine's reason rather than
     * silently disappearing.
     */
    private fun parseChapter(
        request: TranslationChapterRequest,
        body: JSONObject,
    ): TranslationChapterResult {
        val array = body.optJSONArray("segments") ?: JSONArray()
        val byPage = mutableMapOf<Int, MutableList<String>>()
        val errors = mutableMapOf<Int, String>()
        for (i in 0 until array.length()) {
            val segment = array.optJSONObject(i) ?: continue
            val id = segment.optString("id")
            val page = id.removePrefix("p").toIntOrNull() ?: continue
            val translated = segment.optString("translated").takeIf { it.isNotBlank() }
            if (translated != null) {
                byPage.getOrPut(page) { mutableListOf() }.add(translated)
            } else {
                errors[page] = segment.optString("error")
                    .takeIf { it.isNotBlank() }
                    ?: segment.optString("reason").takeIf { it.isNotBlank() }
                    ?: "this segment was not translated"
            }
        }
        val results = request.segments.map { segment ->
            val text = byPage[segment.pageIndex]?.joinToString("\n")
            TranslationSegmentResult(
                pageIndex = segment.pageIndex,
                text = text,
                error = text?.let { null } ?: (errors[segment.pageIndex] ?: "not translated"),
            )
        }
        return TranslationChapterResult(
            segments = results,
            engine = body.optString("engine", ""),
            elapsedMs = body.optLong("elapsedMs", 0),
            degraded = body.optBoolean("degraded", false),
        )
    }

    private fun get(path: String): String = request("GET", path, null)

    private fun post(path: String, body: String): String = request("POST", path, body)

    private fun request(method: String, path: String, body: String?): String {
        val connection = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            body?.let { connection.outputStream.use { out -> out.write(it.toByteArray(Charsets.UTF_8)) } }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (cancelled) throw IOException("cancelled")
            if (code !in 200..299) {
                // The engine's own error is more useful than the status alone.
                val detail = runCatching { JSONObject(text).optString("error") }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: text.take(160)
                throw IOException("$method $path failed (HTTP $code): $detail")
            }
            return text
        } finally {
            connection.disconnect()
        }
    }
}
