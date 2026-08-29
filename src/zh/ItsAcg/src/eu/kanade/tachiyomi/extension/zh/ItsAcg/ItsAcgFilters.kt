package eu.kanade.tachiyomi.extension.zh.itsacg

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

/**
 * 筛选条件定义。
 *
 * 当前站点核心筛选为「分类」（category_id），对应列表页 URL 参数：
 *   https://www.itsacgaa.online/plugin.php?id=jameson_manhua&c=index&a=ku&category_id=1
 *
 * 其余（排序/语言子分类）可在此继续扩展为 Triple / SelectFilter。
 */
object ItsAcgFilters {
    /** 顶级分类下拉 */
    class CategoryFilter : Filter.Select<String>(
        "分类",
        arrayOf(
            "全部",
            "同人誌",
            "單行本",
            "雜誌&短篇",
            "韓漫",
            "3D&漫畫",
        ),
    ) {
        /** 对应的 category_id 值；"全部" 用 null 表示不加参数 */
        fun selectedCategoryId(): String? = when (selectedIndex) {
            0 -> null
            1 -> "1"
            2 -> "2"
            3 -> "3"
            4 -> "4"
            5 -> "19"
            else -> null
        }
    }

    /** 排序方式 */
    class SortFilter : Filter.Select<String>(
        "排序",
        arrayOf("更新时间", "上架时间", "阅读量", "收藏量"),
    ) {
        /** 对应的 odfie 值 */
        fun selectedSortField(): String = when (selectedIndex) {
            0 -> "edittime"
            1 -> "addtime"
            2 -> "views"
            3 -> "favores"
            else -> "edittime"
        }
    }

    fun getFilterList(): FilterList = FilterList(
        CategoryFilter(),
        SortFilter(),
    )
}
