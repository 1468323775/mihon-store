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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Base64

@Source
abstract class DogeManga : KeiSource() {

    // 列表页(HTML)本身一次只给 24 条，翻页要跟着站点自己的「加载更多」接口 /_search 走：
    //   热门排行 / 最新连载：/_search?p=<令牌>，令牌在上一页返回，必须按顺序一页页翻
    //   搜索：              /_search?o=<偏移量>&q=<关键词>，纯偏移，可以直接跳页
    // 首页/搜索页把「下一页」藏在 <script> 里的 atob(base64(JSON)) 里，得先解码才拿得到。
    private val nextPageUrls = mutableMapOf<String, HttpUrl>()

    override suspend fun getPopularManga(page: Int): MangasPage = pagedList(page, KEY_POPULAR) { baseUrl.toHttpUrl() }

    override suspend fun getLatestUpdates(page: Int): MangasPage = pagedList(page, KEY_LATEST) {
        baseUrl.toHttpUrl().newBuilder().addQueryParameter("s", "1").build()
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isBlank()) return getPopularManga(page)

        val url = baseUrl.toHttpUrl().newBuilder().apply {
            if (page <= 1) {
                addQueryParameter("q", query)
            } else {
                addPathSegment("_search")
                addQueryParameter("o", ((page - 1) * PAGE_SIZE).toString())
                addQueryParameter("q", query)
            }
        }.build()

        val result = fetchList(url)
        return MangasPage(result.mangas, result.nextUrl != null)
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

    private suspend fun pagedList(page: Int, key: String, firstPageUrl: () -> HttpUrl): MangasPage {
        val url = if (page <= 1) {
            firstPageUrl()
        } else {
            // 令牌只在按顺序翻页时才拿得到；跳页（没有令牌）就当没有下一页
            nextPageUrls[key] ?: return MangasPage(emptyList(), false)
        }

        val result = fetchList(url)
        val next = result.nextUrl
        if (next == null) {
            nextPageUrls.remove(key)
        } else {
            nextPageUrls[key] = next.toHttpUrl()
        }

        return MangasPage(result.mangas, next != null)
    }

    private suspend fun fetchList(url: HttpUrl): ListResult =
        if (url.encodedPath.startsWith("/_search")) fetchSearchJson(url) else fetchHtmlList(url)

    private suspend fun fetchHtmlList(url: HttpUrl): ListResult {
        val document = client.get(url).asJsoup()
        return ListResult(parseMangaList(document), extractNextUrl(document))
    }

    private suspend fun fetchSearchJson(url: HttpUrl): ListResult {
        val root = JSON.parseToJsonElement(client.get(url).body.string()).jsonObject

        val mangas = root["manga_cards"]?.jsonArray.orEmpty()
            .mapNotNull { it.jsonPrimitive.contentOrNull }
            .filter { it.isNotBlank() }
            .mapNotNull { html -> Jsoup.parse(html).selectFirst("div.site-card")?.let(::parseCard) }

        val next = root["next"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        return ListResult(mangas, next)
    }

    private fun parseMangaList(document: Document): List<SManga> =
        document.select("div.site-card").mapNotNull(::parseCard)

    private fun parseCard(card: Element): SManga? {
        val link = card.selectFirst("a.site-card__manga-title") ?: return null
        return SManga.create().apply {
            setUrlWithoutDomain(link.absUrl("href"))
            title = link.text()
            thumbnail_url = card.selectFirst("img.card-img-top")?.absUrl("src")
            author = card.selectFirst("h6.card-subtitle a")?.text()
            description = card.selectFirst("p.site-card__brief")?.text()
            status = parseStatus(card.selectFirst("p.card-text small")?.text())
        }
    }

    private fun parseMangaDetails(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst("span.site-card__manga-title")?.text()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
        thumbnail_url = document.selectFirst("img.site-manga__cover-image")?.absUrl("src")
        author = document.selectFirst("h4.text-muted a")?.text()
        description = document.selectFirst("p.site-card__brief")?.text()
        status = parseStatus(document.selectFirst("small.text-muted")?.text())
    }

    private fun parseStatus(text: String?): Int = when {
        text == null -> SManga.UNKNOWN
        text.contains("連載中") || text.contains("连载中") -> SManga.ONGOING
        text.contains("完結") || text.contains("完结") -> SManga.COMPLETED
        else -> SManga.UNKNOWN
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

    /** 列表页的「下一页」藏在 script 的 atob(base64(JSON)) 里，形如 {"next":"https://.../_search?p=x"} */
    private fun extractNextUrl(document: Document): String? {
        val script = document.select("script")
            .firstNotNullOfOrNull { it.data().takeIf { data -> data.contains("atob(") } }
            ?: return null

        val encoded = ATOB.find(script)?.groupValues?.get(1) ?: return null
        val decoded = runCatching { String(Base64.getDecoder().decode(encoded)) }.getOrNull() ?: return null
        return NEXT_URL.find(decoded)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
    }

    private data class ListResult(val mangas: List<SManga>, val nextUrl: String?)

    companion object {
        private const val PAGE_SIZE = 24
        private const val KEY_POPULAR = "popular"
        private const val KEY_LATEST = "latest"

        private val JSON = Json { ignoreUnknownKeys = true }
        private val ATOB = Regex("""atob\('([A-Za-z0-9+/=]+)'\)""")
        private val NEXT_URL = Regex("\"next\"\\s*:\\s*\"([^\"]+)\"")
        private val CHAPTER_NUMBER = Regex("""(\d+(?:\.\d+)?)""")
    }
}
