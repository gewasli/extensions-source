package eu.kanade.tachiyomi.extension.zh.itsacg

import android.content.SharedPreferences

/**
 * 源级本地持久化配置（SharedPreferences）。
 *
 * 负责把登录/会话 Cookie 持久化到本地，重启 APP / 插件后依然保留，
 * 避免每次都要重新登录或重新过反爬校验。
 *
 * 使用方式：在 ItsAcg.kt 里通过 lazy 单例持有。
 */
class Preferences(private val prefs: SharedPreferences) {

    /** 持久化的 Cookie 字符串（"k1=v1; k2=v2"），可为 null */
    var savedCookies: String?
        get() = prefs.getString(KEY_COOKIE, null)
        set(value) = prefs.edit().putString(KEY_COOKIE, value).apply()

    /** 清空持久化 Cookie */
    fun clearCookies() {
        prefs.edit().remove(KEY_COOKIE).apply()
    }

    companion object {
        private const val KEY_COOKIE = "itsacg_persistent_cookie"
    }
}
