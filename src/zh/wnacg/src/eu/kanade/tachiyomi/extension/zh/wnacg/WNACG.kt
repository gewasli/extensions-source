package eu.kanade.tachiyomi.extension.zh.wnacg

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferences
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import rx.Observable
import java.util.concurrent.TimeUnit

@Source
abstract class WNACG :
    HttpSource(),
    ConfigurableSource {

    override val supportsLatest = true

    private val preferences = getPreferences { preferenceMigration() }

    override val baseUrl = when (System.getenv("CI")) {
        "true" -> getCiBaseUrl()
        else -> preferences.baseUrl
    }

    private val updateUrlInterceptor = UpdateUrlInterceptor(preferences)

    override val client = network.client.newBuilder()
        .addInterceptor(updateUrlInterceptor)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    override fun headersBuilder() = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36")
        .set("Referer", baseUrl)
        .set("Sec-Fetch-Dest", "document")
        .set("Sec-Fetch-Mode", "navigate")
        .set("Sec-Fetch-Site", "same-origin")
        .add("Accept-Language", "zh-CN,zh;q=0.9")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")

    private fun imageHeaders(): Headers {
        return Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36")
            .set("Referer", baseUrl)
            .set("Sec-Fetch-Dest", "image")
            .set("Sec-Fetch-Mode", "no-cors")
            .set("Sec-Fetch-Site", "cross-site")
            .add("Accept", "image/avif,image/webp,image/png,image/jpeg,*/*;q=0.8")
            .build()
    }

    // Popular
    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/albums-favorite_ranking-page-$page-type-week.html", headers)
    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(".gallary_item").map { mangaFromElement(it) }
        val hasNextPage = document.selectFirst("span.thispage + a") != null
        return MangasPage(mangas, hasNextPage)
    }

    // Latest
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/albums-index-page-$page.html", headers)
    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // Search
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isBlank()) {
            val tagFilter = filters.firstInstanceOrNull<TagFilter>()
            if (tagFilter != null && tagFilter.state.isNotBlank()) {
                return GET("$baseUrl/albums-index-page-$page-tag-${tagFilter.state}.html", headers)
            }
            val categoryFilter = filters.firstInstanceOrNull<CategoryFilter>()
            if (categoryFilter != null && categoryFilter.toUriPart().isNotEmpty()) {
                return GET("$baseUrl/" + categoryFilter.toUriPart().format(page), headers)
            }
            return popularMangaRequest(page)
        }
        val url = "$baseUrl/search/index.php".toHttpUrl().newBuilder()
            .addQueryParameter("s", "create_time_DESC")
            .addQueryParameter("q", query)
            .addQueryParameter("p", page.toString())
            .build()
        return GET(url, headers)
    }
    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // Manga details
    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val artistText = document.selectFirst("div.uwuinfo p")?.text()?.trim()
        return SManga.create().apply {
            title = document.selectFirst("h2")?.text()?.trim() ?: "未知作品"
            artist = artistText
            author = artistText
            genre = document.select("a.tagshow")
                .eachText()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(", ")
                .ifEmpty { null }
            thumbnail_url = document.selectFirst("div.uwthumb img")
                ?.attr("src")
                ?.let { if (it.startsWith("//")) "http:$it" else it }
            description = document.selectFirst("div.asTBcell p")
                ?.html()
                ?.replace("<br>", "\n")
                ?.replace(Regex("<.+?>"), "")
                ?.trim()
            status = SManga.COMPLETED
        }
    }

    // Chapter list
    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        val chapter = SChapter.create().apply {
            url = manga.url
            name = "Ch. 1"
        }
        return Observable.just(listOf(chapter))
    }
    override fun chapterListParse(response: Response): List<SChapter> {
        throw UnsupportedOperationException("仅使用fetchChapterList")
    }

    // Pages
    override fun pageListRequest(chapter: SChapter): Request {
        val pageUrl = baseUrl + chapter.url.replace("-index-", "-gallery-")
        return GET(pageUrl, imageHeaders())
    }
    override fun pageListParse(response: Response): List<Page> {
        val body = response.body.string()
        return pageImageRegex.findAll(body).mapIndexedTo(ArrayList()) { index, match ->
            var imgUrl = match.value
            if (!imgUrl.startsWith("http")) imgUrl = "https:$imgUrl"
            Page(index, imageUrl = imgUrl)
        }
    }
    override fun imageUrlParse(response: Response): String {
        throw UnsupportedOperationException("直链已预提取")
    }

    // Filters
    override fun getFilterList() = FilterList(
        Filter.Header("注意：分类和标签均不支持搜索"),
        CategoryFilter(),
        Filter.Separator(),
        Filter.Header("注意：仅支持 1 个标签，不支持分类"),
        TagFilter(),
    )

    // Preferences
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        getPreferencesInternal(screen.context, preferences, updateUrlInterceptor.isUpdated)
            .forEach(screen::addPreference)
    }

    // Helpers
    private fun mangaFromElement(element: Element): SManga {
        val item = SManga.create()
        val link = element.selectFirst(".title > a") ?: return item
        item.url = link.attr("href")
        item.title = link.text().trim()

        val imgSrc = element.selectFirst("img")?.absUrl("src")
        item.thumbnail_url = imgSrc?.replaceBefore(':', "http")
        return item
    }

    companion object {
        private val pageImageRegex = Regex("""(https?://)?\S*\.(jpeg|jpg|png|webp|gif)""")
    }
}
