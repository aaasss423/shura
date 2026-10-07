package app.shura.host

import app.shura.source.ShuraSource
import app.shura.source.MangaSummary
import app.shura.source.Chapter
import app.shura.source.Page
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceHostTest {
    private class FixtureSource(override val id: String, override val name: String) : ShuraSource {
        override val language = "ar"
        override suspend fun search(query: String, page: Int) = emptyList<MangaSummary>()
        override suspend fun chapters(mangaId: String) = emptyList<Chapter>()
        override suspend fun pages(chapterId: String) = emptyList<Page>()
    }
    @Test fun registerFindAndRemoveSource() {
        val host = SourceHost(); val source = FixtureSource("fixture", "Fixture")
        host.register(source)
        assertEquals(source, host.find("fixture"))
        assertEquals(listOf(source), host.all())
        host.remove("fixture")
        assertNull(host.find("fixture"))
    }
}
