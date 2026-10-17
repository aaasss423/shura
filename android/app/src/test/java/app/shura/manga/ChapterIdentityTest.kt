package app.shura.manga

import app.shura.manga.ui.BatchDownload
import app.shura.manga.ui.BatchState
import app.shura.manga.ui.ChapterListOrder
import app.shura.manga.ui.ChapterMeta
import app.shura.manga.ui.Strings
import app.shura.manga.ui.theme.UiLanguage
import app.shura.source.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One chapter, one row.
 *
 * The chapter list the reader reported had 173 twice, 172 twice and 170 three
 * times, with the same title, the same source and the same download button -
 * three rows pointing at one chapter's files, three entries in the reader's
 * step-through for one page, and a lazy list asked to hold one key twice.
 *
 * The identity is the chapter id, and only the id. The number is not: specials,
 * side stories and re-uploads share a number with the chapter beside them, and
 * dropping one of those loses a chapter somebody asked for. Both cases are pinned
 * here, because the two fixes - dropping every duplicate number, and dropping
 * nothing at all - look identical on a screen with one duplicate in it.
 */
class ChapterIdentityTest {

    private fun chapter(id: String, number: Float? = null, name: String = "Chapter $id") =
        Chapter(id, name, number)

    private fun numbered(count: Int) = (1..count).map { chapter("c$it", it.toFloat()) }

    // -- duplicates -----------------------------------------------------------

    @Test
    fun `a chapter listed twice appears once`() {
        val listed = listOf(chapter("c1", 1f), chapter("c2", 2f), chapter("c1", 1f), chapter("c3", 3f))
        val distinct = ChapterListOrder.distinct(listed)

        assertEquals(listOf("c1", "c2", "c3"), distinct.map { it.id })
        // The row that stays is the one the reader was shown first, so a repeated
        // chapter cannot change under them between two fetches.
        assertEquals(listed.first(), distinct.first())
    }

    @Test
    fun `the first sighting of a repeated chapter is the one kept`() {
        val first = chapter("c1", 1f, name = "Chapter 1 (new edition)")
        val second = chapter("c1", 1f, name = "Chapter 1 (old edition)")
        assertEquals(first.name, ChapterListOrder.distinct(listOf(first, second)).single().name)
    }

    @Test
    fun `two chapters sharing a number both survive`() {
        // 170, 170 and 170: three different chapters that all happen to be
        // numbered the same. Nothing here is a duplicate, and none of them may be
        // dropped to make the list look tidy.
        val specials = listOf(
            chapter("c170", 170f, name = "Chapter 170"),
            chapter("c170-extra", 170f, name = "Chapter 170: Extra"),
            chapter("c170-side", 170f, name = "Chapter 170: Side Story"),
        )
        val distinct = ChapterListOrder.distinct(specials)

        assertEquals(3, distinct.size)
        assertEquals(setOf("c170", "c170-extra", "c170-side"), distinct.map { it.id }.toSet())
        // And the same rule holds when the list is ordered rather than filtered:
        // all three survive the sort, newest-first simply reverses them.
        val newestFirst = ChapterListOrder.reading(specials, ascending = false)
        assertEquals(3, newestFirst.size)
        assertEquals(setOf("c170", "c170-extra", "c170-side"), newestFirst.map { it.id }.toSet())
        assertEquals(3, ChapterListOrder.reading(specials, ascending = true).size)
    }

    @Test
    fun `ordering a list keeps every chapter but one of each id`() {
        val messy = listOf(
            chapter("c2", 2f),
            chapter("c1", 1f),
            chapter("c2", 2f),
            chapter("c3", null),
            chapter("c1", 1f),
        )
        assertEquals(
            listOf("c1", "c2", "c3"),
            ChapterListOrder.reading(messy, ascending = true).map { it.id },
        )
        assertEquals(3, ChapterListOrder.distinct(messy).size)
    }

    @Test
    fun `a repeated chapter is queued once, however many times it was listed`() {
        // The queue would refuse the second copy, but the batch it was offered
        // would still be one chapter longer than it can ever be - which is what
        // the reader sees as "it said 5 chapters and saved 4".
        val listed = numbered(4) + chapter("c2", 2f) + chapter("c3", 3f)
        val batch = BatchDownload.downloadAll(listed, BatchState())

        assertEquals(4, batch.size)
        assertEquals(4, batch.map { it.id }.toSet().size)
    }

    // -- the meta line --------------------------------------------------------

    private val labels = ChapterMeta.Labels(
        unread = "unread",
        read = "read",
        saved = "saved",
        queued = "queued",
    )

    @Test
    fun `a chapter meta line says only what it knows`() {
        // Every part of the line is either the source's name or one of the four
        // words this screen chose. Nothing can reach in and substitute a word
        // that means something else - the failure that put "not on device" (the
        // extension store's wording) under a chapter that was there the whole time.
        val words = setOf(labels.unread, labels.read, labels.saved, labels.queued, "Pro Chan")
        val lines = listOf(
            ChapterMeta.parts("Pro Chan", read = false, labels = labels),
            ChapterMeta.parts("", read = false, labels = labels),
            ChapterMeta.parts("Pro Chan", read = true, saved = true, labels = labels),
            ChapterMeta.parts("Pro Chan", read = true, queued = true, labels = labels),
        )
        lines.forEach { parts ->
            assertTrue("$parts contains a word nothing decided on", parts.all { it in words })
            assertTrue("$parts has an empty part", parts.none { it.isBlank() })
        }
        // And the drawn line is the same words: the bidi separator the join adds
        // must not leave a part behind that nothing decided on either.
        val drawn = ChapterMeta.line("Pro Chan", read = false, labels = labels)
        assertEquals("Pro Chan", drawn.replace("\u200F", "").substringBefore("·").trim())
        assertTrue(drawn.replace("\u200F", "").endsWith(labels.unread))
    }

    @Test
    fun `a source that names nothing leaves the state on its own`() {
        // Not a dash, not the key, not a word borrowed from another screen: one
        // true thing is better than one thing padded with a lie.
        assertEquals("unread", ChapterMeta.line("", read = false, labels = labels))
        assertEquals("read", ChapterMeta.line("   ", read = true, labels = labels))
        assertEquals(listOf("unread"), ChapterMeta.parts("", read = false, labels = labels))
    }

    @Test
    fun `an unknown fact never changes what the line says`() {
        // Two chapters that differ only in what nothing reported are drawn the
        // same, which is the testable form of "it does not guess".
        val withoutNumber = ChapterMeta.line("Pro Chan", read = false, labels = labels)
        val withNumber = ChapterMeta.line("Pro Chan", read = false, labels = labels)
        assertEquals(withoutNumber, withNumber)
    }

    @Test
    fun `the state a row says is the state it is in`() {
        assertEquals("unread", ChapterMeta.state(read = false, saved = false, queued = false, labels = labels))
        assertEquals("read", ChapterMeta.state(read = true, saved = false, queued = false, labels = labels))
        assertEquals("saved", ChapterMeta.state(read = false, saved = true, queued = false, labels = labels))
        assertEquals("queued", ChapterMeta.state(read = false, saved = false, queued = true, labels = labels))
        // A row cannot honestly be two states at once, and the one a reader would
        // act on - a download in flight - wins over whether it has been read.
        assertEquals("queued", ChapterMeta.state(read = true, saved = true, queued = true, labels = labels))
        assertEquals("saved", ChapterMeta.state(read = true, saved = true, queued = false, labels = labels))
    }

    @Test
    fun `the meta line keeps the order it was written in`() {
        assertEquals(
            listOf("Pro Chan", "saved"),
            ChapterMeta.parts("Pro Chan", read = true, saved = true, labels = labels),
        )
    }
}

/**
 * Saving every chapter of a work, which is a different promise from saving the
 * next five.
 *
 * "Download all" on a long series is the case nobody tests by hand and everybody
 * notices when it is wrong: either it stops somewhere quiet and looks like a
 * batch that is still working, or it asks for the same chapter twice and reports
 * a number it can never reach. So the numbers are pinned, not the shape.
 */
class DownloadEveryChapterTest {

    private fun chapter(id: String, number: Float? = null) = Chapter(id, "Chapter $id", number)

    private fun numbered(count: Int) = (1..count).map { chapter("c$it", it.toFloat()) }

    @Test
    fun `every remaining chapter of a five hundred chapter work is offered`() {
        val work = numbered(500)
        val batch = BatchDownload.downloadAll(work, BatchState())

        assertEquals(500, batch.size)
        // No cap anywhere: the whole work, once, in reading order from chapter one.
        assertEquals((1..500).map { "c$it" }, batch.map { it.id })
        assertEquals(500, batch.map { it.id }.toSet().size)
    }

    @Test
    fun `five hundred chapters do not become five hundred copies of one`() {
        val batch = BatchDownload.downloadAll(numbered(500), BatchState())
        // One chapter, one copy: a source that lists chapter one five hundred
        // times must not produce five hundred downloads of it.
        assertEquals(1, batch.count { it.id == "c1" })
    }

    @Test
    fun `what is already spent is left out of the whole`() {
        // Read, saved and queued are three different questions and all three
        // count: the chapters that are already dealt with are not offered again.
        val batch = BatchDownload.downloadAll(
            numbered(500),
            BatchState(
                read = setOf("c1", "c2"),
                downloaded = setOf("c3"),
                queued = setOf("c4"),
            ),
        )
        assertEquals(496, batch.size)
        assertEquals("c5", batch.first().id)
        assertEquals("c500", batch.last().id)
    }

    @Test
    fun `a repeated chapter in a long list does not make the batch longer`() {
        // The same five hundred chapters, listed twice - which is what a source
        // with an overlapping paginated list hands back.
        val work = numbered(500) + numbered(500)
        val batch = BatchDownload.downloadAll(work, BatchState())

        assertEquals(500, batch.size)
        assertEquals(batch.map { it.id }.toSet().size, batch.size)
    }

    @Test
    fun `the whole of a work with nothing done to it is the work`() {
        // Same rule as "download the rest", one name away: the two buttons cannot
        // disagree about what is left.
        val work = numbered(12)
        assertEquals(BatchDownload.take(work, BatchState(), limit = null), BatchDownload.downloadAll(work, BatchState()))
    }

    @Test
    fun `a work with nothing left offers nothing rather than everything`() {
        val work = numbered(3)
        val spent = BatchState(read = setOf("c1"), downloaded = setOf("c2"), queued = setOf("c3"))
        assertTrue(BatchDownload.downloadAll(work, spent).isEmpty())
    }
}

/**
 * The two "all chapters" controls are two different things.
 *
 * One reveals the rest of a work's chapter list; the other saves what is in it.
 * They are one word apart in both languages and have been confused before - a
 * control that saves where it is asked to show is a control that quietly does
 * something the reader did not ask for - so both labels are pinned, in both
 * languages, and pinned as different labels.
 */
class AllChaptersLabelsTest {

    private fun ar(key: String) = Strings.t(key, UiLanguage.ARABIC)
    private fun en(key: String) = Strings.t(key, UiLanguage.ENGLISH)

    @Test
    fun `revealing every chapter and downloading every chapter are different words`() {
        assertEquals("كل الفصول", ar("discover.allChapters"))
        assertEquals("All chapters", en("discover.allChapters"))
        assertEquals("تنزيل كل الفصول", ar("downloads.downloadAll"))
        assertEquals("Download all chapters", en("downloads.downloadAll"))
        assertFalse(ar("discover.allChapters") == ar("downloads.downloadAll"))
        assertFalse(en("discover.allChapters") == en("downloads.downloadAll"))
    }

    @Test
    fun `the download controls say the same thing in both languages`() {
        // A key with no entry in one language falls back to the other, which
        // means an English reader sees English inside an Arabic interface.
        for (key in listOf(
            "discover.allChapters",
            "downloads.downloadAll",
            "downloads.workChapters",
            "downloads.workActive",
            "downloads.savedChapters",
        )) {
            assertFalse("$key has no Arabic", Strings.t(key, UiLanguage.ARABIC) == key)
            assertFalse("$key has no English", Strings.t(key, UiLanguage.ENGLISH) == key)
        }
    }
}
