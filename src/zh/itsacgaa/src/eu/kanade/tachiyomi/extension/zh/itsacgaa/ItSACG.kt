package eu.kanade.tachiyomi.extension.zh.itsacgaa

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.applicationContext
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.nodes.Element
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ITsACG 漫画中心（Discuz! + jameson_manhua 插件）。
 *
 * 特性：
 * - 可在扩展设置中修改网站地址（默认 https://www.itsacgaa.online，改后需重启生效）
 * - 可在扩展设置中填写用户名/密码，浏览时自动登录（Discuz! 标准登录，保存会话 Cookie）
 * - 支持最新、分类浏览、搜索、详情、阅读（图片列表内嵌于阅读页 JS 数组）
 */
@Source
abstract class ItSACG : KeiSource(), ConfigurableSource {

    override val supportsLatest = true

    private val preferences: SharedPreferences = getPreferences()

    override val baseUrl
        get() = preferences.baseUrl.ifBlank { DEFAULT_BASE_URL }.removeSuffix("/")

    private val username
        get() = preferences.getString(USERNAME_PREF, "").orEmpty()

    private val password
        get() = preferences.getString(PASSWORD_PREF, "").orEmpty()

    private val loginFailed = AtomicBoolean(false)
    private val loggingIn = AtomicBoolean(false)

    private val loginScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())

    override fun OkHttpClient.Builder.configureClient() = apply {
        // 懒登录：已填写账号密码但无会话 Cookie 时，自动尝试登录
        addInterceptor { chain ->
            val request = chain.request()
            val host = request.url.host
            val sourceHost = baseUrl.toHttpUrl().host
            val isLoginUrl = request.url.toString().contains("logging")
            if (
                !isLoginUrl &&
                host == sourceHost &&
                needsLogin() &&
                loggingIn.compareAndSet(false, true)
            ) {
                try {
                    login()
                } catch (_: Exception) {
                    loginFailed.set(true)
                } finally {
                    loggingIn.set(false)
                }
            }
            chain.proceed(request)
        }
        // 为本站域名注入已保存的会话 Cookie（阅读页图片在 CDN 域，无需注入）
        addNetworkInterceptor { chain ->
            val request = chain.request()
            val host = baseUrl.toHttpUrl().host
            if (request.url.host == host || request.url.host.endsWith(".$host")) {
                val cookies = preferences.savedCookies
                if (cookies.isNotBlank()) {
                    return@addNetworkInterceptor chain.proceed(
                        request.newBuilder()
                            .header("Cookie", cookies)
                            .build(),
                    )
                }
            }
            chain.proceed(request)
        }
    }

    // ── 列表 ─────────────────────────────────────────────

    override suspend fun getPopularManga(page: Int): MangasPage =
        parseMangaPage(client.get(latestUrl(page)))

    override suspend fun getLatestUpdates(page: Int): MangasPage =
        parseMangaPage(client.get(latestUrl(page)))

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isBlank()) {
            val categoryId = filters.firstInstanceOrNull<CategoryFilter>()?.categoryId
            return if (categoryId != null) {
                parseMangaPage(client.get(categoryUrl(categoryId, page)))
            } else {
                parseMangaPage(client.get(latestUrl(page)))
            }
        }
        return parseMangaPage(client.get(searchUrl(query, page)))
    }

    private fun parseMangaPage(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(".uk-card").mapNotNull { mangaFromCard(it) }
        val hasNextPage = document.selectFirst(".pg a.nxt") != null
        return MangasPage(mangas, hasNextPage)
    }

    private fun mangaFromCard(card: Element): SManga? {
        val link = card.selectFirst("p a[href*='bofang']") ?: return null
        val url = link.attr("href").removePrefix("./")
        if (url.isBlank()) return null
        return SManga.create().apply {
            this.url = url
            title = link.text()
            thumbnail_url = card.selectFirst("img")?.absUrl("src").orEmpty()
        }
    }

    // ── 详情 & 章节 ──────────────────────────────────────

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/${chapter.url}"

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        if (!url.encodedPath.contains("bofang")) return null
        val kuid = url.queryParameter("kuid") ?: return null
        return getMangaDetails(
            SManga.create().apply {
                this.url = "plugin.php?id=jameson_manhua&c=index&a=bofang&kuid=$kuid"
            },
        )
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val mangaDeferred = async { if (fetchDetails) getMangaDetails(manga) else manga }
        val chaptersDeferred = async { if (fetchChapters) getChapterList(manga) else chapters }
        SMangaUpdate(manga = mangaDeferred.await(), chapters = chaptersDeferred.await())
    }

    private suspend fun getMangaDetails(manga: SManga): SManga {
        val document = client.get("$baseUrl/${manga.url}").asJsoup()
        val info = document.selectFirst("h3")?.parent() ?: document
        return SManga.create().apply {
            url = manga.url
            title = document.selectFirst("h3")?.text() ?: manga.title
            author = info.selectFirst("a[href*='a=mzz']")?.text()
                ?.removePrefix("作者:")?.trim()?.takeIf { it.isNotBlank() }
            genre = buildList {
                info.select("a[href*='category_id=']").eachText().forEach(::add)
                info.select("a[href*='a=search_tags'] span").eachText().forEach(::add)
            }.distinct().joinToString(", ").ifBlank { null }
            thumbnail_url = document.selectFirst("img[src*='imgcover']")?.absUrl("src")
            status = SManga.COMPLETED
        }
    }

    private suspend fun getChapterList(manga: SManga): List<SChapter> {
        val document = client.get("$baseUrl/${manga.url}").asJsoup()
        val readUrls = document.select("a[href*='a=read'][href*='zjid=']")
            .mapNotNull { it.attr("href").removePrefix("./").takeIf(String::isNotBlank) }
            .distinct()
        return readUrls.mapIndexed { index, url ->
            SChapter.create().apply {
                this.url = url
                name = if (readUrls.size == 1) "阅读" else "第 ${index + 1} 话"
            }
        }
    }

    // ── 阅读页 ───────────────────────────────────────────

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val html = client.get("$baseUrl/${chapter.url}").use { it.body.string() }
        val urlsMatch = pageUrlsRegex.find(html) ?: return emptyList()
        return urlInArrayRegex.findAll(urlsMatch.groupValues[1])
            .map { it.value }
            .mapIndexed { index, url -> Page(index, imageUrl = url) }
            .toList()
    }

    // ── 过滤器 ───────────────────────────────────────────

    override fun getFilterList(data: JsonElement?) = FilterList(CategoryFilter())

    // ── 设置：网站地址 + 账号密码登录 ─────────────────────

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addEditTextPreference(
            title = "网站地址",
            summary = baseUrl,
            key = BASE_URL_PREF,
            default = DEFAULT_BASE_URL,
            dialogMessage = "默认 $DEFAULT_BASE_URL。修改后需要重启应用才能生效。",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            validate = { it.isBlank() || it.toHttpUrlOrNull() != null },
        ) {
            loginFailed.set(false)
            preferences.edit().putString(COOKIE_PREF, "").apply()
        }

        screen.addEditTextPreference(
            title = "用户名",
            summary = username.ifBlank { "登录本站所需的用户名" },
            key = USERNAME_PREF,
        ) {
            resetLoginState()
        }

        screen.addEditTextPreference(
            title = "密码",
            summary = if (password.isBlank()) "登录本站所需的密码" else "*".repeat(password.length),
            key = PASSWORD_PREF,
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            summaryOf = { "*".repeat(it.length) },
        ) {
            resetLoginState()
        }

        Preference(screen.context).apply {
            key = "login"
            title = "登录"
            val self = this
            summary = if (preferences.hasCookies) "已登录" else "填写用户名和密码后点击登录"
            setOnPreferenceClickListener {
                if (username.isBlank() || password.isBlank()) {
                    Toast.makeText(applicationContext, "请先填写用户名和密码", Toast.LENGTH_SHORT).show()
                } else {
                    self.summary = "登录中…"
                    loginScope.launch {
                        val success = try {
                            login()
                            true
                        } catch (_: Exception) {
                            false
                        }
                        handler.post {
                            self.summary = if (success) "已登录" else "登录失败，请检查账号密码"
                        }
                    }
                }
                true
            }
        }.let(screen::addPreference)

        Preference(screen.context).apply {
            key = "logout"
            title = "退出登录"
            summary = "清除已保存的登录 Cookie"
            setOnPreferenceClickListener {
                resetLoginState()
                Toast.makeText(applicationContext, "已退出登录", Toast.LENGTH_SHORT).show()
                true
            }
        }.let(screen::addPreference)
    }

    // ── 登录 ─────────────────────────────────────────────

    private fun needsLogin(): Boolean =
        username.isNotBlank() &&
            password.isNotBlank() &&
            !preferences.hasCookies &&
            !loginFailed.get()

    private fun resetLoginState() {
        loginFailed.set(false)
        preferences.edit().putString(COOKIE_PREF, "").apply()
    }

    /**
     * Discuz! 标准登录：
     * 1. GET 登录页提取 formhash 与登录表单 action
     * 2. POST 用户名/密码（inajax=1 返回可解析的 XML 状态）
     * 3. 保存会话 Cookie
     */
    @Synchronized
    private fun login() {
        // 1. 获取登录页
        val loginPage = network.client.newCall(
            GET("$baseUrl/member.php?mod=logging&action=login", headers),
        ).execute()
        val document = loginPage.use { it.asJsoup() }
        val form = document.selectFirst("form[action*='loginsubmit']")
            ?: throw IOException("未找到登录表单，登录页可能已改版")
        val formhash = form.selectFirst("input[name='formhash']")?.attr("value")
            ?: throw IOException("未找到 formhash")
        val formAction = form.attr("action").removePrefix("./")
        val loginUrl = "$baseUrl/$formAction".toHttpUrl().newBuilder()
            .addQueryParameter("inajax", "1")
            .build()

        // 2. 提交登录
        val body = FormBody.Builder()
            .add("formhash", formhash)
            .add("username", username)
            .add("password", password)
            .add("questionid", "0")
            .add("answer", "")
            .add("cookietime", "2592000")
            .add("referer", baseUrl)
            .build()
        val response = network.client.newCall(POST(loginUrl.toString(), headers, body)).execute()
        val bodyText = response.use { it.body.string() }
        val setCookies = response.headers("Set-Cookie")

        if (!bodyText.contains("succeed", ignoreCase = true)) {
            preferences.edit().putString(COOKIE_PREF, "").apply()
            throw IOException("登录失败，请检查用户名和密码")
        }

        // 3. 保存 Cookie
        val cookies = setCookies.mapNotNull { cookie ->
            val pair = cookie.substringBefore(';')
            val key = pair.substringBefore('=')
            val value = pair.substringAfter('=')
            if (key.isBlank() || value.isBlank()) null else "$key=$value"
        }.distinct().joinToString("; ")
        preferences.edit().putString(COOKIE_PREF, cookies).apply()
    }

    // ── URL 构造 ─────────────────────────────────────────

    private fun latestUrl(page: Int): HttpUrl =
        "$baseUrl/plugin.php".toHttpUrl().newBuilder()
            .addQueryParameter("id", "jameson_manhua")
            .addQueryParameter("c", "index")
            .addQueryParameter("a", "ku")
            .addQueryParameter("odfie", "addtime")
            .addQueryParameter("order", "desc")
            .addQueryParameter("page", page.toString())
            .build()

    private fun categoryUrl(categoryId: String, page: Int): HttpUrl =
        "$baseUrl/plugin.php".toHttpUrl().newBuilder()
            .addQueryParameter("id", "jameson_manhua")
            .addQueryParameter("a", "ku")
            .addQueryParameter("category_id", categoryId)
            .addQueryParameter("page", page.toString())
            .build()

    private fun searchUrl(query: String, page: Int): HttpUrl =
        "$baseUrl/plugin.php".toHttpUrl().newBuilder()
            .addQueryParameter("id", "jameson_manhua")
            .addQueryParameter("c", "index")
            .addQueryParameter("a", "search")
            .addQueryParameter("keyword", query)
            .addQueryParameter("order_by", "addtime")
            .addQueryParameter("page", page.toString())
            .build()

    private companion object {
        // 阅读页中内嵌图片列表的 JS 数组
        val pageUrlsRegex = Regex("""let\s+urls\s*=\s*(\[[^\]]*\])""")
        val urlInArrayRegex = Regex("""https?://[^"]+""")
    }
}
