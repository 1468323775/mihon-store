package eu.kanade.tachiyomi.extension.zh.dogemanga

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document

@Source
abstract class DogeManga : KeiSource() {

    // 站点只有首页热门排行，没有独立的"最近更新"列表页
    override val supportsLatest get() = false

    override suspend fun getPopularManga(page: Int): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)
        return MangasPage(parseMangaList(client.get(baseUrl.toHttpUrl()).asJsoup()), false)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = getPopularManga(page)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)
        if (query.isBlank()) return getPopularManga(page)

        val url = baseUrl.toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .build()
        return MangasPage(parseMangaList(client.get(url).asJsoup()), false)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga).toHttpUrl()).asJsoup()

        val details = if (fetchDetails) {
            parseMangaDetails(document).apply { url = manga.url }
        } else {
            manga
        }
        val chapterList = if (fetchChapters) parseChapterList(document) else emptyList()

        return SMangaUpdate(details, chapterList)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter).toHttpUrl()).asJsoup()

        return document.select("img.site-reader__image")
            .map { it.absUrl("data-page-image-url") }
            .filter { it.isNotBlank() }
            .mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl) }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        if (url.pathSegments.firstOrNull() != "m") return null

        val document = client.get(url).asJsoup()
        return parseMangaDetails(document).apply {
            setUrlWithoutDomain(url.toString())
            initialized = true
        }
    }

    private fun parseMangaList(document: Document): List<SManga> = document.select("div.site-card").mapNotNull { card ->
            val link = card.selectFirst("a.site-card__manga-title") ?: return@mapNotNull null
            SManga.create().apply {
                setUrlWithoutDomain(link.absUrl("href"))
                title = link.text()
                thumbnail_url = card.selectFirst("img.card-img-top")?.absUrl("src")
                author = card.selectFirst("h6.card-subtitle a")?.text()
                description = card.selectFirst("p.site-card__brief")?.text()
            }
        }

    private fun parseMangaDetails(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst("span.site-card__manga-title")?.text()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
        thumbnail_url = document.selectFirst("img.site-manga__cover-image")?.absUrl("src")
        author = document.selectFirst("h4.text-muted a")?.text()
        description = document.selectFirst("p.site-card__brief")?.text()
    }

    private fun parseChapterList(document: Document): List<SChapter> =
        document.select("a.site-manga-thumbnail__link")
            .filter { it.absUrl("href").isNotBlank() }
            .map { link ->
                val name = link.selectFirst("img.site-manga-thumbnail__image")
                    ?.attr("alt")
                    ?.takeIf { it.isNotBlank() }
                    ?: link.text().trim()

                SChapter.create().apply {
                    setUrlWithoutDomain(link.absUrl("href"))
                    this.name = name
                    chapter_number = CHAPTER_NUMBER.find(name)
                        ?.groupValues
                        ?.get(1)
                        ?.toFloatOrNull()
                        ?: -1f
                }
            }
            .distinctBy { it.url }

    companion object {
        private val CHAPTER_NUMBER = Regex("""(\d+(?:\.\d+)?)""")
    }
}
