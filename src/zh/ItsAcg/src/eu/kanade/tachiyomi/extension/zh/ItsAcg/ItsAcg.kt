package eu.kanade.tachiyomi.extension.zh.itsacg
import android.content.Context
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.setting.WebViewSetting
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import uy.kohesive.injekt.injectLazy
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
 *   3) 使用WebView登录，Cookie自动同步到OkHttp。
 */
// lib1.4：删除 @Source，去掉 abstract
class ItsAcg :
    HttpSource(),
    ConfigurableSource {

    // lib1.4 必须手动写这4个属性，gradle不会注入
    override val name = "福利漫畫"
    override val baseUrl = "https://填写你的站点域名"
    override val lang = "zh"
    override val supportsLatest = true

    // 补上泛型，解决 Cannot infer type for T；变量名network和父类冲突，加override
    override val network: NetworkHelper by injectLazy<NetworkHelper>()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", baseUrl)
        .add("User‑Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")

    // setupPreferenceScreen 使用lib1.4双参数版本，WebViewSetting可以正常使用
    override fun setupPreferenceScreen(screen: PreferenceScreen, context: Context) {
        WebViewSetting(context).apply {
            key = "webview_login"
            title = "网页登录"
            summary = "打开网页登录账号，Cookie会自动同步"
            url = baseUrl
            screen.addPreference(this)
        }
    }

    // ...后面全部业务函数保留
    // ------------------------------------------------------------------
    // 热门
    // ------------------------------------------------------------------
    override fun popularMangaRequest(page: Int): Request = GET(
        "$baseUrl/plugin.php?id=jameson_manhua&c=index&a=ku" +
            "&odfie=edittime&order=desc&page=$page",
        headers,
    )


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
    // 搜索 & 筛选（接入分类+排序）
    // ------------------------------------------------------------------
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val categoryFilter = filters.filterIsInstance<ItsAcgFilters.CategoryFilter>().firstOrNull()
        val sortFilter = filters.filterIsInstance<ItsAcgFilters.SortFilter>().firstOrNull()

        val categoryId = categoryFilter?.selectedCategoryId()
        val sortField = sortFilter?.selectedSortField() ?: "edittime"

        val url = buildString {
            append("$baseUrl/plugin.php?id=jameson_manhua&c=index&a=search")
            if (query.isNotBlank()) {
                append("&keyword=").append(query)
            }
            if (categoryId != null) {
                append("&category_id=$categoryId")
            }
            append("&odfie=$sortField")
            append("&page=$page")
        }
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    override fun getFilterList(): FilterList = ItsAcgFilters.getFilterList()

    // ------------------------------------------------------------------
    // 详情
    // ------------------------------------------------------------------
    override fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.use { parseHtml(it) }
        return SManga.create().apply {
            title = doc.selectFirst(".xs2.mt5 a")?.text()
                ?: doc.title()
                ?: "未知标题"
            status = SManga.COMPLETED
            description = doc.selectFirst("meta[name=description]")?.attr("content").orEmpty()
            thumbnail_url = doc.selectFirst("img.manhua-fengmian, img[src*='hacg_cover']")?.attr("src")
        }
    }

    override fun getMangaUrl(manga: SManga): String = manga.url

    // ------------------------------------------------------------------
    // 章节（全部单章节）
    // ------------------------------------------------------------------
    override fun chapterListRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun chapterListParse(response: Response): List<SChapter> {
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
    override fun pageListRequest(chapter: SChapter): Request = GET(baseUrl + chapter.url, headers)

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
        return org.jsoup.Jsoup.parse(body, baseUrl)
    }

    // ------------------------------------------------------------------
    // ConfigurableSource：WebView登录入口（参考NoyAcg）
    // ------------------------------------------------------------------
    override fun setupPreferenceScreen(screen: PreferenceScreen, context: Context) {
        WebViewSetting(context).apply {
            key = "webview_login"
            title = "网页登录"
            summary = "打开网页登录账号，Cookie会自动同步"
            this.intentExtra = mapOf("url" to baseUrl)
            screen.addPreference(this)
        }
    }
}
