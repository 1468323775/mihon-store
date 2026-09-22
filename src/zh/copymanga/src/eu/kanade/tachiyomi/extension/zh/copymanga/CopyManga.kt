package eu.kanade.tachiyomi.extension.zh.copymanga

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

@Source
abstract class CopyManga : KeiSource() {

    override val supportsLatest get() = true

    // 站点只有 APP 接口（网页是前端渲染，扒不出东西），接口域名有主站和镜像两个，坏了自动切
    private val apiHosts = listOf("api.mangacopy.com", "api.2026copy.com")
    private var apiHostIndex = 0

    private val apiHeaders = Headers.Builder()
        .add("User-Agent", COPY_UA)
        .add("platform", "1")
        .add("region", "1")
        .add("webp", "1")
        .add("version", COPY_VERSION)
        .build()

    override suspend fun getPopularManga(page: Int): MangasPage {
        val offset = (page - 1) * PAGE_SIZE
        val dto = apiGetBody(
            "ranks",
            mapOf("type" to "1", "limit" to "$PAGE_SIZE", "offset" to "$offset", "_update" to "true"),
        ).parseAs<RanksDto>()

        val result = dto.results
        val mangas = result?.list.orEmpty().mapNotNull { it.comic?.toSManga() }
        return MangasPage(mangas, hasNextPage(result?.total, offset, mangas.size))
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val offset = (page - 1) * PAGE_SIZE
        // 注意：/comics 返回的是扁平漫画对象，/ranks 是包了一层 comic，两者结构不同
        val dto = apiGetBody(
            "comics",
            mapOf(
                "limit" to "$PAGE_SIZE",
                "offset" to "$offset",
                "ordering" to "-datetime_updated",
                "_update" to "true",
            ),
        ).parseAs<SearchDto>()

        val result = dto.results
        val mangas = result?.list.orEmpty().mapNotNull { it.toSMangaOrNull() }
        return MangasPage(mangas, hasNextPage(result?.total, offset, mangas.size))
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isBlank()) return getPopularManga(page)

        val offset = (page - 1) * PAGE_SIZE
        val dto = apiGetBody(
            "search/comic",
            mapOf("q" to query, "limit" to "$PAGE_SIZE", "offset" to "$offset", "q_type" to ""),
        ).parseAs<SearchDto>()

        val result = dto.results
        val mangas = result?.list.orEmpty().mapNotNull { it.toSMangaOrNull() }
        return MangasPage(mangas, hasNextPage(result?.total, offset, mangas.size))
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val pathWord = pathWordOf(manga.url)

        val details = if (fetchDetails && pathWord != null) {
            val dto = apiGetBody(
                "comic2/$pathWord",
                mapOf("in_mainland" to "false", "platform" to "1"),
            ).parseAs<DetailDto>()
            dto.results?.comic?.toSManga()?.apply { url = manga.url } ?: manga
        } else {
            manga
        }

        val chapterList = if (fetchChapters && pathWord != null) fetchChapterList(pathWord) else emptyList()
        return SMangaUpdate(details, chapterList)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val segments = chapter.url.trim('/').split('/')
        val pathWord = segments.getOrNull(1) ?: return emptyList()
        val uuid = segments.getOrNull(3) ?: return emptyList()

        val dto = apiGetBody(
            "comic/$pathWord/chapter/$uuid",
            mapOf("platform" to "1"),
        ).parseAs<PagesDto>()

        return dto.results?.chapter?.contents.orEmpty()
            .map { it.url }
            .filter { it.isNotBlank() }
            .mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl) }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        if (url.pathSegments.firstOrNull() != "comic") return null
        val pathWord = url.pathSegments.getOrNull(1) ?: return null

        val dto = apiGetBody(
            "comic2/$pathWord",
            mapOf("in_mainland" to "false", "platform" to "1"),
        ).parseAs<DetailDto>()

        return dto.results?.comic?.toSManga()?.apply { initialized = true } ?: return null
    }

    private suspend fun fetchChapterList(pathWord: String): List<SChapter> {
        val chapters = mutableListOf<SChapter>()
        var offset = 0
        while (true) {
            val dto = apiGetBody(
                "comic/$pathWord/group/default/chapters",
                mapOf("limit" to "$CHAPTER_PAGE", "offset" to "$offset"),
            ).parseAs<ChaptersDto>()

            val result = dto.results ?: break
            chapters += result.list.map { it.toSChapter(pathWord) }
            offset += CHAPTER_PAGE
            if (result.list.isEmpty() || chapters.size >= result.total) break
        }
        return chapters.distinctBy { it.url }
    }

    /** 接口域名轮换：主站不通就换镜像 */
    private suspend fun apiGetBody(path: String, params: Map<String, String>): String {
        var lastError: Exception? = null
        repeat(apiHosts.size) {
            try {
                val url = "https://${apiHosts[apiHostIndex]}/api/v3/$path".toHttpUrl().newBuilder()
                    .apply { params.forEach { (key, value) -> addQueryParameter(key, value) } }
                    .build()
                return client.get(url, apiHeaders).body.string()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                apiHostIndex = (apiHostIndex + 1) % apiHosts.size
            }
        }
        throw lastError ?: IllegalStateException("拷贝漫画接口失败：$path")
    }

    private fun hasNextPage(total: Long?, offset: Int, count: Int): Boolean =
        total != null && count > 0 && offset + count < total

    private fun pathWordOf(mangaUrl: String): String? =
        mangaUrl.trim('/').split('/').getOrNull(1)?.takeIf { it.isNotBlank() }

    private fun ComicDto.toSMangaOrNull(): SManga? = path_word.takeIf { it.isNotBlank() }?.let { toSManga() }

    private fun ComicDto.toSManga(): SManga = SManga.create().apply {
        setUrlWithoutDomain("/comic/$path_word")
        title = name
        thumbnail_url = cover
        author = this@toSManga.author.joinToString(", ") { it.name }
        description = brief
        genre = theme.joinToString(", ") { it.name }.takeIf { it.isNotBlank() }
        status = when (this@toSManga.status?.value) {
            0 -> SManga.ONGOING
            1 -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }

    private fun ChapterDto.toSChapter(pathWord: String): SChapter = SChapter.create().apply {
        setUrlWithoutDomain("/comic/$pathWord/chapter/$uuid")
        name = this@toSChapter.name
        chapter_number = CHAPTER_NUMBER.find(name)?.groupValues?.get(1)?.toFloatOrNull() ?: (index + 1).toFloat()
        date_upload = CHAPTER_DATE.tryParse(datetime_created)
    }

    @Serializable
    private data class RanksDto(val results: PagedComics? = null)

    @Serializable
    private data class PagedComics(val total: Long = 0, val list: List<ComicWrapper> = emptyList())

    @Serializable
    private data class ComicWrapper(val comic: ComicDto? = null)

    @Serializable
    private data class SearchDto(val results: SearchResults? = null)

    @Serializable
    private data class SearchResults(val total: Long = 0, val list: List<ComicDto> = emptyList())

    @Serializable
    private data class DetailDto(val results: DetailResults? = null)

    @Serializable
    private data class DetailResults(val comic: ComicDto? = null)

    @Serializable
    private data class ChaptersDto(val results: ChaptersResults? = null)

    @Serializable
    private data class ChaptersResults(val total: Long = 0, val list: List<ChapterDto> = emptyList())

    @Serializable
    private data class PagesDto(val results: PagesResults? = null)

    @Serializable
    private data class PagesResults(val chapter: ChapterPagesDto? = null)

    @Serializable
    private data class ChapterPagesDto(val contents: List<ContentDto> = emptyList())

    @Serializable
    private data class ContentDto(val url: String)

    @Serializable
    private data class ComicDto(
        val name: String = "",
        val path_word: String = "",
        val cover: String? = null,
        val author: List<AuthorDto> = emptyList(),
        val theme: List<AuthorDto> = emptyList(),
        val brief: String? = null,
        val status: ValueDisplayDto? = null,
    )

    @Serializable
    private data class AuthorDto(val name: String = "")

    @Serializable
    private data class ValueDisplayDto(val value: Int = -1, val display: String? = null)

    @Serializable
    private data class ChapterDto(
        val uuid: String = "",
        val name: String = "",
        val index: Int = 0,
        val datetime_created: String? = null,
    )

    companion object {
        private const val PAGE_SIZE = 20
        private const val CHAPTER_PAGE = 500
        private const val COPY_UA = "COPY/3.2.4"
        private const val COPY_VERSION = "2023.10.20"

        private val CHAPTER_NUMBER = Regex("""(\d+(?:\.\d+)?)""")
        private val CHAPTER_DATE = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }
}
