package eu.kanade.tachiyomi.extension.zh.itsacg

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import uy.kohesive.injekt.injectLazy
import java.net.CookieManager
import java.net.CookiePolicy
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.regex.Pattern

/**
 * ITsACG（福利漫畫）图源。
 *
 * 站点底层为 Discuz X3.5 + jameson_manhua 插件。
 * 关键 URL：
 *   列表  : /plugin.php?id=jameson_manhua&c=index&a=ku&odfie=edittime&order=desc&page=N
 *   搜索  : /plugin.php?id=jameson_manhua&c=index&a=search&keyword=xxx&page=N
 *   阅读  : /plugin.php?id=jameson_manhua&c=index&a=bofang&kuid=xxx
 *
 * 结构要点：
 *   1) 每本漫画只有一个章节（单篇本子），getChapterList 恒定返回一条；
 *   2) 阅读页图片不在静态 HTML，藏在 JS 变量 var pics=[...] 中，需正则提取；
 *   3) 图片与页面均需 Referer 防盗链，cookie 需持久化以应对登录/会话校验。
 */
class ItsAcg : HttpSource() {

    override val name = "福利漫畫"

    override val baseUrl = "https://www.itsacgaa.online"

    override val lang = "zh"

    override val supportsLatest = true

    private val network: NetworkHelper by injectLazy()

    private val preferences: Preferences by lazy {
        Preferences(network.prefs)
    }

    /** 类加载后立即恢复持久化 Cookie（失败静默，不影响正常访问） */
    init {
        try {
            restorePersistentCookie()
        } catch (_: Exception) {
            // Injekt 在极早期可能未就绪，忽略即可
        }
    }

    // ------------------------------------------------------------------
    // Cookie 持久化
    // ------------------------------------------------------------------

    /** 请求头，统一加 Referer 防盗链 */
    override val headers = headersBuilder()
        .add("Referer", baseUrl)
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
        .build()

    /** 启动时把持久化的 Cookie 写回 OkHttp 的 CookieJar */
    private fun restorePersistentCookie() {
        val cookieStr = preferences.savedCookies ?: return
        if (cookieStr.isBlank()) return
        val uri = baseUrl.toHttpUrl().toUri()
        val cm = CookieManager(null, CookiePolicy.ACCEPT_ALL)
        cookieStr.split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { pair ->
                try {
                    val cookie = okhttp3.Cookie.parse(baseUrl.toHttpUrl(), pair)
                    if (cookie != null) cm.cookieStore.add(uri, cookie)
                } catch (_: Exception) {
                    // 跳过单条解析失败的 cookie
                }
            }
    }

    /** 把响应返回的 Set-Cookie 合并持久化 */
    private fun saveCookiesFromResponse(response: Response) {
        val setCookies = response.headers("Set-Cookie")
        if (setCookies.isNotEmpty()) {
            val existing = preferences.savedCookies
            val joined = if (existing.isNullOrBlank()) {
                setCookies.joinToString("; ") { it.substringBefore(";") }
            } else {
                existing + "; " + setCookies.joinToString("; ") { it.substringBefore(";") }
            }
            preferences.savedCookies = joined
        }
    }

    // ------------------------------------------------------------------
    // 热门
    // ------------------------------------------------------------------

    override fun popularMangaRequest(page: Int): Request {
        return GET(
            "$baseUrl/plugin.php?id=jameson_manhua&c=index&a=ku" +
                "&odfie=edittime&order=desc&page=$page",
            headers,
        )
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val doc = response.use { parseHtml(it) }
        val mangas = doc.select(".rootcate > div.uk-card.mbm.uk-text-center").mapNotNull { el ->
            val aTag = el.selectFirst("a[href]") ?: return@mapNotNull null
            SManga.create().apply {
                setUrlWithoutDomain(aTag.attr("href"))
                title = aTag.text().trim()
                thumbnail_url = el.selectFirst("img")?.attr("src")
            }
        }
        val hasNext = doc.select(".pg a.nxt").isNotEmpty
        return MangasPage(mangas, hasNext)
    }

    // ------------------------------------------------------------------
    // 搜索
    // ------------------------------------------------------------------

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = buildString {
            append("$baseUrl/plugin.php?id=jameson_manhua&c=index&a=search")
            if (query.isNotBlank()) {
                append("&keyword=").append(query)
            }
            // 分类来自筛选器
            val categoryId = filters.getCategoryId()
            if (categoryId != null) append("&category_id=$categoryId")
            append("&page=$page")
        }
        return GET(url, headers)
    }

    /** 从 FilterList 提取选中的 category_id */
    private fun FilterList.getCategoryId(): String? {
        for (f in this) {
            if (f is ItsAcgFilters.CategoryFilter) {
                return f.selectedCategoryId()
            }
        }
        return null
    }

    override fun searchMangaParse(response: Response): MangasPage {
        return popularMangaParse(response)
    }

    // ------------------------------------------------------------------
    // 详情
    // ------------------------------------------------------------------

    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.use { parseHtml(it) }
        return SManga.create().apply {
            title = doc.selectFirst(".xs2.mt5 a")?.text()
                ?: doc.title()
                ?: "未知标题"
            // 站点为单篇本子，恒定视为完结
            status = SManga.COMPLETED
            description = doc.selectFirst("meta[name=description]")?.attr("content").orEmpty()
            thumbnail_url = doc.selectFirst("img.manhua-fengmian, img[src*='hacg_cover']")?.attr("src")
        }
    }

    override fun getMangaUrl(manga: SManga): String = manga.url

    // ------------------------------------------------------------------
    // 章节（全部单章节）
    // ------------------------------------------------------------------

    override fun chapterListRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        // 该站每个作品是单篇本子，只返回一个「全本」章节
        val url = response.request.url.toString().removePrefix(baseUrl)
        return listOf(
            SChapter.create().apply {
                name = "全本"
                this.url = url
                date_upload = 0
            },
        )
    }

    // ------------------------------------------------------------------
    // 图片页
    // ------------------------------------------------------------------

    override fun pageListRequest(chapter: SChapter): Request {
        return GET(baseUrl + chapter.url, headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val html = response.use { it.body?.string().orEmpty() }
        val urls = extractImageUrls(html)
        return urls.mapIndexed { index, url -> Page(index, imageUrl = url) }
    }

    /** 从阅读页 JS 中正则提取图片数组（var pics = ["...", "..."]） */
    private fun extractImageUrls(html: String): List<String> {
        val results = LinkedHashSet<String>()

        // 常见写法 1: var pics=["https://...", "https://..."];
        val picsPattern = Pattern.compile(
            "(?:var\\s+)?(?:pics|images|imgs|picArr)\\s*=\\s*\\[(.*?)\\];",
            Pattern.DOTALL or Pattern.CASE_INSENSITIVE,
        )
        val picsMatcher = picsPattern.matcher(html)
        if (picsMatcher.find()) {
            extractQuotedUrls(picsMatcher.group(1)).forEach { results.add(it) }
        }

        // 常见写法 2: 直接内联 img src（兜底）
        if (results.isEmpty()) {
            val imgPattern = Pattern.compile("<img[^>]+src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
            val m = imgPattern.matcher(html)
            while (m.find()) {
                val src = m.group(1)
                if (src.startsWith("http")) results.add(src)
            }
        }

        return results.toList()
    }

    private fun extractQuotedUrls(raw: String): List<String> {
        val pattern = Pattern.compile("\"(https?[^\"]+)\"")
        val m = pattern.matcher(raw)
        val list = mutableListOf<String>()
        while (m.find()) {
            list.add(m.group(1))
        }
        return list
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun parseHtml(response: Response): Document {
        val body = response.body?.string().orEmpty()
        saveCookiesFromResponse(response)
        return org.jsoup.Jsoup.parse(body, baseUrl)
    }

    /** 中文日期解析（本子标题中常见 "2026-8-28 12:01"） */
    private fun parseDateOrEpoch(text: String): Long {
        return try {
            val fmt = SimpleDateFormat("yyyy-M-d HH:mm", Locale.getDefault())
            fmt.parse(text)?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }
}
