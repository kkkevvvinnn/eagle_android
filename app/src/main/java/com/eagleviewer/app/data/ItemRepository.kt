package com.eagleviewer.app.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.eagleviewer.app.data.db.AppDatabase
import com.eagleviewer.app.data.db.ItemWithTags
import com.eagleviewer.app.data.db.TagCount
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
enum class Sort(val clause: String, val label: String) {
    BTIME_DESC("btime DESC", "添加时间 ↓"),
    BTIME_ASC("btime ASC", "添加时间 ↑"),
    STAR_DESC("star DESC, btime DESC", "评分 ↓"),
    STAR_ASC("star ASC, btime DESC", "评分 ↑"),
    SIZE_DESC("size DESC", "文件大小 ↓"),
    SIZE_ASC("size ASC", "文件大小 ↑"),
}

@Serializable
data class Filter(
    val tags: Set<String> = emptySet(),
    val andMode: Boolean = true,
    val minStar: Int = 0,
    val sort: Sort = Sort.BTIME_DESC,
    val nameQuery: String = "",
    val untaggedOnly: Boolean = false,
)

/** 基于 Room 索引的查询仓库：动态组装筛选 SQL（标签与/或、评分、排序）。 */
class ItemRepository(private val db: AppDatabase) {

    fun pagedItems(filter: Filter): Flow<PagingData<ItemWithTags>> =
        Pager(
            config = PagingConfig(
                pageSize = 60,
                initialLoadSize = 120,
                // 占位符模式：大图查看页需要按全局索引直接跳转到任意位置
                enablePlaceholders = true,
            ),
        ) {
            db.itemDao().pagedItems(buildQuery(filter))
        }.flow

    fun tagCounts(): Flow<List<TagCount>> = db.itemDao().tagCounts()

    fun itemCount(): Flow<Int> = db.itemDao().itemCount()

    companion object {
        fun buildQuery(filter: Filter): SupportSQLiteQuery {
            val conditions = mutableListOf<String>()
            val args = mutableListOf<Any>()

            if (filter.minStar > 0) {
                conditions += "star >= ?"
                args += filter.minStar
            }
            if (filter.nameQuery.isNotBlank()) {
                conditions += "name LIKE '%' || ? || '%'"
                args += filter.nameQuery.trim()
            }
            if (filter.untaggedOnly) {
                // 未标记：不属于任何标签
                conditions += "id NOT IN (SELECT DISTINCT itemId FROM item_tag)"
            } else if (filter.tags.isNotEmpty()) {
                val placeholders = filter.tags.joinToString(",") { "?" }
                conditions += if (filter.andMode) {
                    // 与：同时含有全部选中标签
                    "id IN (SELECT itemId FROM item_tag WHERE tag IN ($placeholders) " +
                        "GROUP BY itemId HAVING COUNT(DISTINCT tag) = ${filter.tags.size})"
                } else {
                    // 或：含有任一选中标签
                    "id IN (SELECT DISTINCT itemId FROM item_tag WHERE tag IN ($placeholders))"
                }
                args.addAll(filter.tags)
            }

            val sql = buildString {
                append("SELECT * FROM items")
                if (conditions.isNotEmpty()) {
                    append(" WHERE ")
                    append(conditions.joinToString(" AND "))
                }
                append(" ORDER BY ")
                append(filter.sort.clause)
            }
            return SimpleSQLiteQuery(sql, args.toTypedArray())
        }
    }
}
