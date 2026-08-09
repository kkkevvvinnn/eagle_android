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
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
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
    /** 相似配色检索的目标颜色（RGB int），非空时按色板距离排序，忽略其他条件。 */
    val similarColor: Int? = null,
) {
    /** 是否存在真实筛选条件（排序方式不算筛选）。 */
    val isActive: Boolean
        get() = tags.isNotEmpty() || minStar > 0 || nameQuery.isNotBlank() ||
            untaggedOnly || similarColor != null
}

/** 基于 Room 索引的查询仓库：动态组装筛选 SQL（标签与/或、评分、排序、相似配色）。 */
class ItemRepository(private val db: AppDatabase) {

    fun pagedItems(filter: Filter): Flow<PagingData<ItemWithTags>> = flow {
        val query = if (filter.similarColor != null) {
            buildSimilarQuery(rankBySimilarColor(filter.similarColor))
        } else {
            buildQuery(filter)
        }
        emitAll(
            Pager(
                config = PagingConfig(
                    pageSize = 60,
                    initialLoadSize = 120,
                    // 占位符模式：大图查看页需要按全局索引直接跳转到任意位置
                    enablePlaceholders = true,
                ),
            ) {
                db.itemDao().pagedItems(query)
            }.flow,
        )
    }

    fun tagCounts(): Flow<List<TagCount>> = db.itemDao().tagCounts()

    fun itemCount(): Flow<Int> = db.itemDao().itemCount()

    /** 按色板与目标颜色的距离对全库排序，取前 [SIMILAR_LIMIT] 个 id。 */
    private suspend fun rankBySimilarColor(color: Int): List<String> =
        db.itemDao().allPalettes()
            .map { it.id to paletteDistance(it.palettesJson, color) }
            .filter { it.second < Double.MAX_VALUE }
            .sortedBy { it.second }
            .take(SIMILAR_LIMIT)
            .map { it.first }

    companion object {
        private const val SIMILAR_LIMIT = 150

        /**
         * 条目色板与目标颜色的距离：各色板颜色与目标的 RGB 欧氏距离的最小值。
         * 无色板时返回 [Double.MAX_VALUE]（不参与相似配色结果）。
         */
        fun paletteDistance(palettesJson: String, color: Int): Double {
            if (palettesJson.isEmpty()) return Double.MAX_VALUE
            val palettes = runCatching {
                EagleItemMeta.json.decodeFromString<List<EagleItemMeta.Palette>>(palettesJson)
            }.getOrDefault(emptyList())
            if (palettes.isEmpty()) return Double.MAX_VALUE
            val tr = (color shr 16) and 0xFF
            val tg = (color shr 8) and 0xFF
            val tb = color and 0xFF
            return palettes
                .filter { it.color.size >= 3 }
                .minOf { p ->
                    val dr = p.color[0] - tr
                    val dg = p.color[1] - tg
                    val db = p.color[2] - tb
                    kotlin.math.sqrt((dr * dr + dg * dg + db * db).toDouble())
                }
        }

        /** 相似配色查询：按距离排序后的 id 列表用 CASE 保序。 */
        fun buildSimilarQuery(rankedIds: List<String>): SupportSQLiteQuery {
            if (rankedIds.isEmpty()) {
                return SimpleSQLiteQuery("SELECT * FROM items WHERE 0")
            }
            val placeholders = rankedIds.joinToString(",") { "?" }
            val caseOrder = rankedIds.indices.joinToString(" ") { "WHEN ? THEN $it" }
            return SimpleSQLiteQuery(
                "SELECT * FROM items WHERE id IN ($placeholders) " +
                    "ORDER BY CASE id $caseOrder END",
                (rankedIds + rankedIds).toTypedArray(),
            )
        }

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
