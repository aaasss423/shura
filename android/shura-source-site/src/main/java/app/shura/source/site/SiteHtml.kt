package app.shura.source.site

import eu.kanade.tachiyomi.source.model.SManga
import java.text.Normalizer
import java.net.URL

/**
 * The generic HTML reading rules, written once for every generated extension.
 *
 * These are the rules Shura's crawler proved against real sites, expressed for
 * the runtime that actually reads them. The important one is the first: a
 * catalogue index is not a list of works.
 *
 * A live Arabic catalogue links *chapters* whose text names the work - "X -
 * الفصل 24" - so reading link text as a title produces works with chapter
 * numbers in their names, one per chapter. So an entry that carries a chapter
 * marker is a chapter of the work named before the marker, and only an entry
 * without one is a page for the work itself.
 */
internal object SiteHtml {

    /** One entry found on a page. */
    data class Entry(val url: String, val title: String, val number: Float, val isChapter: Boolean)

    // -- text -------------------------------------------------------------

    /**
     * Folds the differences real catalogues actually contain.
     *
     * The same Arabic work ships in one index as "X" and as "X&nbsp;X" - a
     * non-breaking space from a copy-paste. Left alone that is two works with
     * one set of chapters between them.
     */
    fun normaliseTitle(raw: String?): String {
        var text = Normalizer.normalize(unescape(stripTags(raw ?: "")), Normalizer.Form.NFC)
        text = text.replace(' ', ' ').replace('‎', ' ').replace('‏', ' ')
        // Arabic diacritics and tatweel carry no meaning in a work's name and
        // differ between the index and the work's own page.
        text = Regex("[\\u064B-\\u0652\\u0670\\u0640]").replace(text, "")
        return Regex("\\s+").replace(text, " ").trim()
    }

    private val ANCHOR = Regex("<a[^>]+href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</a>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val HEADING = Regex("<h1[^>]*>(.*?)</h1>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val IMAGE_SRC = Regex("(?:src|data-src|data-original)\\s*=\\s*[\"']([^\"']+)[\"']",
        setOf(RegexOption.IGNORE_CASE))

    fun stripTags(html: String): String = Regex("<[^>]+>").replace(html, "")

    fun unescape(text: String): String {
        var out = text
        for ((entity, char) in ENTITIES) out = out.replace(entity, char.toString())
        NUMERIC.replace(out) { m ->
            val code = m.groupValues[1].toIntOrNull()
            if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else m.value
        }
        return out
    }

    private val ENTITIES = listOf("&nbsp;" to ' ', "&amp;" to '&', "&lt;" to '<',
        "&gt;" to '>', "&quot;" to '"', "&#039;" to '\'', "&apos;" to '\'')
    private val NUMERIC = Regex("&#(x?)([0-9a-fA-F]+);")

    private fun heading(html: String): String =
        HEADING.find(html)?.let { normaliseTitle(it.groupValues[1]) }.orEmpty()

    // -- numbers ----------------------------------------------------------

    /** Chapter numbers are not always whole: one site serves "37.5" beside "37". */
    private fun firstNumber(text: String): Float? =
        Regex("\\d+(?:\\.\\d+)?").find(text)?.value?.toFloatOrNull()

    /**
     * Splits "Work - Chapter 12" into the work and the number.
     *
     * Returns null when the text carries no marker, which is the evidence that
     * the entry is a work rather than a chapter of one.
     */
    fun splitChapterTitle(raw: String, marker: Regex): Pair<String, Float?>? {
        val text = normaliseTitle(raw)
        if (text.isEmpty() || marker.pattern.isEmpty()) return null
        val found = marker.find(text) ?: return null
        val base = text.substring(0, found.range.first).trim(' ', '-', '–', '—', '|', ':')
        if (base.isEmpty() || base == text) return null
        return base to firstNumber(text.substring(found.range.first))
    }

    // -- links ------------------------------------------------------------

    /**
     * The listing groups on a page: each is a set of entry links that belong to
     * one index.
     *
     * Grouping is by *parent directory*, not by a digit-masked path. Masking
     * digits looks equivalent and is not: a catalogue whose slugs carry an id
     * suffix - ``/comics/pick-up-a-grimoire-become-a-mage-1bb35e56`` - masks to
     * a different string per work, so every link lands in a group of one and a
     * real index of forty works looks like navigation. The parent directory is
     * what an index actually shares.
     */
    private fun entryGroups(spec: SiteSpec, html: String): List<List<Pair<String, String>>> {
        val byParent = LinkedHashMap<String, LinkedHashMap<String, String>>()
        val byShape = LinkedHashMap<String, LinkedHashMap<String, String>>()
        for (match in ANCHOR.findAll(html)) {
            val href = match.groupValues[1].trim()
            if (href.isEmpty() || href.startsWith("#") || href.startsWith("mailto:") ||
                href.startsWith("javascript:") || href.startsWith("data:")) continue
            if (!spec.entryHref.containsMatchIn(href)) continue
            val title = normaliseTitle(match.groupValues[2])
            byShape.getOrPut(href.replace(Regex("\\d+"), "#")) { LinkedHashMap() }
                .putIfAbsent(href, title)
            val parent = href.substringBeforeLast('/', "")
            byParent.getOrPut(parent) { LinkedHashMap() }.putIfAbsent(href, title)
        }
        // A menu has many shapes of one link each; a listing repeats one shape
        // under one parent. Parents win where they qualify, because they are the
        // weaker assumption.
        val byParents = byParent.values.filter { it.size >= spec.minListingGroup }
        return (if (byParents.isNotEmpty()) byParents
                 else byShape.values.filter { it.size >= spec.minListingGroup })
            .map { it.toList() }
    }

    fun absolute(href: String, base: String): String = try {
        URL(URL(base), href).toString()
    } catch (_: Exception) {
        href
    }

    /**
     * A chapter's number, preferring the one in its own URL.
     *
     * The link text is not reliable. One live catalogue renders a chapter as
     * "Chapter 6" immediately followed by "2 days ago", so the first number in
     * the text is 62 - a number that exists nowhere in the URL and belongs to
     * no chapter. The URL of a chapter ends in its number, so that is read
     * first and the text is only a fallback for sites that number nowhere else.
     */
    private fun chapterNumber(href: String, text: String, spec: SiteSpec): Float {
        val tail = href.substringBefore('?').trimEnd('/').substringAfterLast('/')
        if (tail.isNotEmpty() && Regex("^\\d+(?:\\.\\d+)?$").matches(tail)) {
            return tail.toFloatOrNull() ?: -1f
        }
        if (text.isNotEmpty()) {
            val fromText = splitChapterTitle(text, spec.chapterMarker)?.second
            if (fromText != null) return fromText
        }
        return -1f
    }

    /** Every entry on a page, newest-first for chapters. */
    fun entries(spec: SiteSpec, html: String, pageUrl: String): List<Entry> {
        val seen = LinkedHashSet<String>()
        val out = ArrayList<Entry>()
        for (group in entryGroups(spec, html)) {
            for ((href, text) in group) {
                val url = absolute(href, pageUrl)
                if (!seen.add(url)) continue
                // The URL is asked first; a title marker is the fallback for a
                // site that numbers its chapters nowhere else.
                val byHref = spec.chapterHref.pattern.isNotEmpty() &&
                    spec.chapterHref.containsMatchIn(href)
                val byTitle = text.isNotEmpty() && splitChapterTitle(text, spec.chapterMarker) != null
                val isChapter = byHref || byTitle
                out.add(Entry(url, text, chapterNumber(href, text, spec), isChapter))
            }
        }
        return out
    }

    // -- the three operations ---------------------------------------------

    /**
     * The works on an index.
     *
     * Only entries without a chapter marker become works: those are the only
     * links that are pages *for a work*. A chapter's URL is not promoted into a
     * work, because reading its heading yields a chapter name and the reader
     * would get a manga that does not exist.
     */
    fun works(spec: SiteSpec, html: String, query: String = ""): List<SManga> {
        val pageUrl = spec.catalogueUrl
        val needle = normaliseTitle(query).lowercase()
        val works = LinkedHashMap<String, SManga>()
        for (entry in entries(spec, html, pageUrl)) {
            if (entry.isChapter || entry.title.isEmpty()) continue
            // No search route was proven for this site, so the query is applied
            // to what the site really returned rather than to a route guessed
            // from another site's URL layout.
            if (needle.isNotEmpty() && !entry.title.lowercase().contains(needle)) continue
            val key = identity(entry.title)
            if (works.containsKey(key)) continue
            works[key] = SManga.create().apply {
                url = entry.url
                title = entry.title
            }
        }
        return works.values.toList()
    }

    /** The chapters linked from a work's own page. */
    fun chapters(spec: SiteSpec, html: String, pageUrl: String): List<Entry> {
        val chapters = entries(spec, html, pageUrl).filter { it.isChapter && it.number >= 0f }
        return chapters
            .distinctBy { it.url }
            .sortedByDescending { it.number }
    }

    /**
     * The chapters of one work, taken from a page that lists them.
     *
     * A catalogue does not have to link a work's chapters from the work's own
     * page. The Arabic site this engine was written against links them only
     * from the index, each labelled with the work it belongs to - so a reader
     * that went straight to the work page would find no chapters at all and
     * report the manga as empty.
     *
     * Both are tried, and neither invents anything: an entry counts as a
     * chapter of *this* work only when its own text names it.
     */
    fun chaptersFor(spec: SiteSpec, html: String, pageUrl: String, workTitle: String,
                    indexHtml: String): List<Entry> {
        val direct = chapters(spec, html, pageUrl)
        if (direct.isNotEmpty()) return direct
        val wanted = normaliseTitle(workTitle).lowercase()
        if (wanted.isEmpty() || indexHtml.isEmpty()) return emptyList()
        return entries(spec, indexHtml, spec.catalogueUrl)
            .filter { entry ->
                val split = splitChapterTitle(entry.title, spec.chapterMarker) ?: return@filter false
                identity(split.first).equals(identity(wanted), ignoreCase = true)
            }
            .map { entry ->
                Entry(entry.url, entry.title,
                    splitChapterTitle(entry.title, spec.chapterMarker)?.second ?: -1f, true)
            }
            .distinctBy { it.url }
            .sortedByDescending { it.number }
    }

    /**
     * The reading pages of one chapter, in order.
     *
     * Two signals, both required. The image must match the extension pattern,
     * and it must live under the prefix that identifies this chapter - which is
     * what keeps a cover or a logo from being handed to the reader as page 1.
     */
    fun pages(spec: SiteSpec, html: String, pageUrl: String): List<String> {
        // Without a proven chapter prefix there is no way to tell a reading
        // page from the cover and the logos on the same page, so this returns
        // nothing. Guessing here is what puts a cover in front of the reader as
        // page 1 of a chapter they are trying to read.
        if (spec.pageImagePrefix.pattern.isEmpty()) return emptyList()
        val found = ArrayList<Pair<Float, String>>()
        for (match in IMAGE_SRC.findAll(html)) {
            val src = match.groupValues[1].trim()
            if (!spec.pageImage.containsMatchIn(src)) continue
            if (spec.pageImagePrefix.pattern.isNotEmpty() &&
                !spec.pageImagePrefix.containsMatchIn(src)) continue
            if (spec.imageHost.isNotEmpty()) {
                val host = URL(src).host
                if (host.isNotEmpty() && !host.equals(spec.imageHost, ignoreCase = true)) continue
            }
            val number = firstNumber(src.substringAfterLast('/')) ?: found.size.toFloat()
            found += number to absolute(src, pageUrl)
        }
        return found.sortedBy { it.first }.map { it.second }.distinct()
    }

    /** A work's cover, if the spec says how to recognise one. */
    fun cover(spec: SiteSpec, html: String, pageUrl: String): String? {
        val pattern = spec.coverImage ?: return null
        for (match in IMAGE_SRC.findAll(html)) {
            val src = match.groupValues[1].trim()
            if (pattern.containsMatchIn(src)) return absolute(src, pageUrl)
        }
        return null
    }

    fun heading(spec: SiteSpec, html: String): String =
        if (spec.trustPageHeading) heading(html) else ""

    /**
     * A stable identity for a work, from its title.
     *
     * Not a URL slug: a catalogue whose titles are Arabic produces an empty
     * slug for every one of them, which would collapse the whole index into a
     * single work.
     */
    fun identity(title: String): String {
        val text = normaliseTitle(title).lowercase()
        val readable = Regex("[a-z0-9]+").findAll(text)
            .joinToString("-") { it.value }.take(32)
        val digest = MessageDigestHex.sha1(text)
        return if (readable.isEmpty()) digest.take(12) else "$readable-${digest.take(12)}"
    }

    /**
     * Where a search goes.
     *
     * The catalogue, always: this engine invents no search route, because a
     * route this site was never shown to serve returns an error page that would
     * parse into an empty result and look like "no such manga".
     */
    fun searchUrl(spec: SiteSpec, page: Int, query: String): String {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return spec.catalogueUrl
        return spec.catalogueUrl + "#" +
            java.net.URLEncoder.encode(trimmed, "UTF-8")
    }
}

/** Minimal SHA-1, so identity needs no extra dependency. */
private object MessageDigestHex {
    fun sha1(text: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-1")
            .digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}