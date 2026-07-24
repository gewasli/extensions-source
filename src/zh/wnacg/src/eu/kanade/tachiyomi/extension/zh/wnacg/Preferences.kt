package eu.kanade.tachiyomi.extension.zh.wnacg

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.ListPreference
import eu.kanade.tachiyomi.network.GET
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import kotlin.random.Random

private const val DEFAULT_LIST = "https://www.wn07.cfd,https://www.wn07.shop,https://www.wn06.cfd,https://www.wn06.shop"
// 远程域名更新冷却 10分钟，防止短时间大量请求
private const val UPDATE_COOLDOWN_MS = 10 * 60 * 1000L

fun getPreferencesInternal(
    context: Context,
    preferences: SharedPreferences,
    isUrlUpdated: Boolean,
) = arrayOf(
    ListPreference(context).apply {
        key = URL_INDEX_PREF
        title = "网址"
        summary = if (isUrlUpdated) "%s\n网址已自动更新，请重启应用。" else "%s\n正常情况下会自动更新。重启生效。"

        val options = preferences.urlList.filter { it.isNotBlank() }
        entries = options.toTypedArray()
        entryValues = Array(options.size, Int::toString)
    },
)

val SharedPreferences.baseUrl: String
    get() {
        val list = urlList.filter { it.isNotBlank() }
        if (list.isEmpty()) return DEFAULT_LIST.split(",")[0]
        return list.getOrNull(urlIndex) ?: list.first()
    }

val SharedPreferences.urlIndex get() = getString(URL_INDEX_PREF, "-1")!!.toInt()
val SharedPreferences.urlList get() = getString(URL_LIST_PREF, DEFAULT_LIST)!!.split(",")

fun getCiBaseUrl() = DEFAULT_LIST.replace(",", "#, ")

fun SharedPreferences.preferenceMigration() {
    val storedList = getString(URL_LIST_PREF, "")
    if (storedList.isNullOrEmpty()) {
        edit()
            .remove("overrideBaseUrl")
            .putString(URL_LIST_PREF, DEFAULT_LIST)
            .setUrlList(DEFAULT_LIST, urlIndex)
            .apply()
    }
}

fun SharedPreferences.Editor.setUrlList(urlListRaw: String, oldIndex: Int): SharedPreferences.Editor {
    putString(URL_LIST_PREF, urlListRaw)
    val list = urlListRaw.split(",").filter { it.isNotBlank() }
    if (list.isEmpty()) return putString(URL_INDEX_PREF, "0")

    val maxIndex = list.lastIndex
    if (oldIndex in 0..maxIndex) return this

    val newIndex = Random.nextInt(0, maxIndex + 1)
    return putString(URL_INDEX_PREF, newIndex.toString())
}

class UpdateUrlInterceptor(private val preferences: SharedPreferences) : Interceptor {
    private var lastUpdateTime = 0L

    override fun intercept(chain: Interceptor.Chain): Response {
        // ✅ 每次请求实时读取最新域名，不再缓存
        val currentBase = preferences.baseUrl
        val request = chain.request()

        if (!request.url.toString().startsWith(currentBase)) {
            return chain.proceed(request)
        }

        var response: Response? = null
        try {
            response = chain.proceed(request)
            // 正常访问直接返回
            if (response.isSuccessful && response.header("Server") != "Parking/1.0") {
                return response
            }
            // 停放页、异常响应，进入更新逻辑
        } catch (e: Throwable) {
            if (chain.call().isCanceled()) throw e
        }

        // 关闭无效响应，释放连接
        response?.close()

        val now = System.currentTimeMillis()
        // 冷却判断，避免频繁拉取远程域名
        if (now - lastUpdateTime > UPDATE_COOLDOWN_MS) {
            if (tryUpdateUrl(chain)) {
                throw IOException("网址列表已自动更新，请重启扩展/应用生效")
            }
        }

        // 更新失败，抛出原始异常
        throw IOException("当前域名无法访问")
    }

    @Synchronized
    private fun tryUpdateUrl(chain: Interceptor.Chain): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastUpdateTime < UPDATE_COOLDOWN_MS) return false

        val resp = try {
            chain.proceed(GET("https://stevenyomi.github.io/source-domains/wnacg.txt"))
        } catch (_: Throwable) {
            return false
        }

        if (!resp.isSuccessful) {
            resp.close()
            lastUpdateTime = now
            return false
        }

        val newRawList = resp.body.string().trim()
        resp.close()
        lastUpdateTime = now

        if (newRawList.isBlank()) return false

        val localSaved = preferences.getString(URL_LIST_PREF, "")!!
        if (newRawList != localSaved) {
            preferences.edit()
                .setUrlList(newRawList, preferences.urlIndex)
                .apply()
            return true
        }
        return false
    }
}

private const val DEFAULT_LIST_PREF = "defaultBaseUrl"
private const val URL_LIST_PREF = "baseUrlList"
private const val URL_INDEX_PREF = "baseUrlIndex"
