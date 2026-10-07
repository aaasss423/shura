package app.shura.host

import app.shura.source.Chapter
import app.shura.source.MangaSummary
import app.shura.source.Page
import app.shura.source.ShuraSource
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

data class HttpResult(val status: Int, val body: String)

/** Injectable transport so the adapter is unit-testable without the network. */
fun interface HttpGetter {
    fun get(url: String, headers: Map<String, String>): HttpResult
}

/** Bounded HTTPS fetcher restricted to Shura's MangaDex metadata hosts. */
fun defaultHttpGetter(
    connectTimeoutMs: Int = 10_000,
    readTimeoutMs: Int = 20_000,
    maxBodyBytes: Int = 8_000_000,
): HttpGetter = HttpGetter { url, headers ->
    val uri = URI(url)
    require(uri.scheme.equals("https", ignoreCase = true)) { "HTTPS required" }
    require(uri.userInfo == null) { "URL credentials forbidden" }
    val host = uri.host?.lowercase() ?: error("missing URL host")
    require(host == "api.mangadex.org" || host == "uploads.mangadex.org") { "host is not allowed: $host" }
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.setRequestProperty("User-Agent", "Shura-Android/0.1")
        if (headers.isNotEmpty()) connection.setRequestProperty("Accept", headers["Accept"] ?: "application/json")
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
            val out = StringBuilder()
            val buffer = CharArray(32 * 1024)
            var total = 0
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                total += count
                check(total <= maxBodyBytes) { "response exceeds size limit" }
                out.append(buffer, 0, count)
            }
            out.toString()
        } ?: ""
        HttpResult(status, body)
    } finally {
        connection.disconnect()
    }
}

/** Real browseable source backed by the public MangaDex API (search -> manga -> chapters -> pages). */
class MangaDexSource(private val getter: HttpGetter = defaultHttpGetter()) : ShuraSource {
    override val id = "com.shura.source.mangadex"
    override val name = "MangaDex"
    override val language = "en"

    override suspend fun search(query: String, page: Int): List<MangaSummary> = searchRequest(query, page)

    override suspend fun chapters(mangaId: String): List<Chapter> = chaptersRequest(mangaId)

    override suspend fun pages(chapterId: String): List<Page> = pagesRequest(chapterId)

    private fun searchRequest(query: String, page: Int): List<MangaSummary> {
        val offset = page.coerceAtLeast(1) - 1
        val queryParam = if (query.isBlank()) "" else "&title=${URLEncoder.encode(query.trim(), "UTF-8")}"
        val url = "https://api.mangadex.org/manga?limit=20&offset=$offset&includes[]=cover_art&order[latestUploadedChapter]=desc$queryParam"
        val response = getter.get(url, emptyMap())
        check(response.status in 200..299) { "MangaDex search failed (HTTP ${response.status})" }
        val data = JSONObject(response.body).optJSONArray("data") ?: JSONArray()
        return buildList {
            for (index in 0 until data.length()) {
                val item = data.optJSONObject(index) ?: continue
                val id = item.optString("id")
                val attrs = item.optJSONObject("attributes") ?: continue
                val title = attrs.optJSONObject("title")?.optString("en").orEmpty().ifBlank { firstAlternativeTitle(attrs) }
                if (id.isBlank() || title.isBlank()) continue
                add(MangaSummary(id, title, coverUrl(item, id)))
            }
        }
    }

    private fun chaptersRequest(mangaId: String): List<Chapter> {
        val url = "https://api.mangadex.org/manga/$mangaId/feed" +
            "?translatedLanguage[]=en&limit=500&order[chapter]=asc" +
            "&contentRating[]=safe&contentRating[]=suggestive&contentRating[]=erotica"
        val response = getter.get(url, emptyMap())
        check(response.status in 200..299) { "MangaDex feed failed (HTTP ${response.status})" }
        val data = JSONObject(response.body).optJSONArray("data") ?: JSONArray()
        return buildList {
            for (index in 0 until data.length()) {
                val item = data.optJSONObject(index) ?: continue
                val id = item.optString("id")
                val attrs = item.optJSONObject("attributes") ?: continue
                val number = (attrs.opt("chapter") as? String)?.toFloatOrNull()
                val title = attrs.optString("title").orEmpty()
                val name = when {
                    title.isNotBlank() -> title
                    number != null -> "Chapter ${formatNumber(number)}"
                    else -> "Chapter ${index + 1}"
                }
                if (id.isBlank()) continue
                add(Chapter(id, name, number))
            }
        }
    }

    private fun pagesRequest(chapterId: String): List<Page> {
        val response = getter.get("https://api.mangadex.org/at-home/server/$chapterId", emptyMap())
        check(response.status in 200..299) { "MangaDex at-home failed (HTTP ${response.status})" }
        val json = JSONObject(response.body)
        check(json.optString("result").orEmpty() == "ok") { "MangaDex at-home error: ${json.optString("errors").orEmpty()}" }
        val base = json.optString("baseUrl").orEmpty().trimEnd('/')
        val chapter = json.optJSONObject("chapter") ?: error("missing chapter hash")
        val hash = chapter.optString("hash").orEmpty()
        val data = chapter.optJSONArray("data") ?: JSONArray()
        return buildList {
            for (index in 0 until data.length()) {
                val file = data.optString(index)
                if (file.isNotBlank()) add(Page(index, "$base/data/$hash/$file"))
            }
        }
    }

    private fun coverUrl(item: JSONObject, mangaId: String): String? {
        val relationships = item.optJSONArray("relationships") ?: return null
        for (index in 0 until relationships.length()) {
            val relation = relationships.optJSONObject(index) ?: continue
            if (relation.optString("type").orEmpty() != "cover_art") continue
            val fileName = relation.optJSONObject("attributes")?.optString("fileName").orEmpty()
            if (fileName.isNotBlank()) return "https://uploads.mangadex.org/covers/$mangaId/$fileName.256.jpg"
        }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun firstAlternativeTitle(attrs: JSONObject): String {
        val alternatives = attrs.optJSONArray("altTitles") ?: return ""
        for (index in 0 until alternatives.length()) {
            val entry = alternatives.optJSONObject(index) ?: continue
            if (entry.has("en")) return entry.optString("en")
            val keys = entry.keys() as Iterator<String>
            if (keys.hasNext()) return entry.optString(keys.next())
        }
        return ""
    }

    private fun formatNumber(number: Float): String =
        if (number % 1f == 0f) number.toInt().toString() else number.toString()
}