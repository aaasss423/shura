package app.shura.source

/** Stable contract between Shura's UI and independently hosted source adapters. */
interface ShuraSource {
    val id: String
    val name: String
    val language: String
    suspend fun search(query: String, page: Int): List<MangaSummary>
    suspend fun chapters(mangaId: String): List<Chapter>
    suspend fun pages(chapterId: String): List<Page>
}
data class MangaSummary(val id: String, val title: String, val coverUrl: String?)
data class Chapter(val id: String, val name: String, val number: Float?)
data class Page(val index: Int, val imageUrl: String)
