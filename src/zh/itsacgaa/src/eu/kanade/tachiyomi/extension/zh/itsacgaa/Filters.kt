package eu.kanade.tachiyomi.extension.zh.itsacgaa

import eu.kanade.tachiyomi.source.model.Filter

/**
 * 分类浏览过滤器（与站点的 category_id 对应，空表示“全部/最新”）。
 */
class CategoryFilter : Filter.Select<String>(
    "分类",
    arrayOf(
        "全部",
        "同人誌",
        "單行本",
        "雜誌&短篇",
        "韓漫",
        "3D漫畫",
        "漢化",
    ),
    0,
) {
    val categoryId: String?
        get() = when (state) {
            1 -> "1"
            2 -> "2"
            3 -> "3"
            4 -> "4"
            5 -> "19"
            6 -> "14"
            else -> null
        }
}
