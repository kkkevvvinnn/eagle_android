package com.eagleviewer.app.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.eagleviewer.app.data.db.AppDatabase
import com.eagleviewer.app.data.db.ItemWithTags
import com.eagleviewer.app.data.db.TagCount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
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
    val andMode: Boolean = false,
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

    /** Prepared once per filter, shared by paging and counts (including the actual color top 150). */
    suspend fun prepareQuery(filter: Filter): SupportSQLiteQuery =
        if (filter.similarColor != null) buildSimilarQuery(rankBySimilarColor(filter.similarColor))
        else buildQuery(filter)

    fun pagedItems(query: SupportSQLiteQuery): Flow<PagingData<ItemWithTags>> =
        Pager(
            config = PagingConfig(pageSize = 60, initialLoadSize = 120, enablePlaceholders = true),
        ) { db.itemDao().pagedItems(query) }.flow

    fun currentTagCounts(query: SupportSQLiteQuery): Flow<List<TagCount>> =
        db.itemDao().filteredTagCounts(buildTagCountQuery(query))

    fun tagCounts(): Flow<List<TagCount>> = db.itemDao().tagCounts()

    fun itemCount(): Flow<Int> = db.itemDao().itemCount()

    /** 按色板与目标颜色的距离对全库排序，取前 [SIMILAR_LIMIT] 个 id。 */
    private suspend fun rankBySimilarColor(color: Int): List<String> {
        val all = db.itemDao().allPalettes()
        // 全库 JSON 解码 + 排序不能跑在主线程（收集方是 viewModelScope）
        return withContext(Dispatchers.Default) {
            all.map { it.id to paletteDistance(it.palettesJson, color) }
                .filter { it.second < Double.MAX_VALUE }
                .sortedBy { it.second }
                .take(SIMILAR_LIMIT)
                .map { it.first }
        }
    }

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
            // minOfOrNull：色板全部残缺（color 不足 3 元素）时不参与检索
            return palettes
                .filter { it.color.size >= 3 }
                .minOfOrNull { p ->
                    val dr = p.color[0] - tr
                    val dg = p.color[1] - tg
                    val db = p.color[2] - tb
                    kotlin.math.sqrt((dr * dr + dg * dg + db * db).toDouble())
                } ?: Double.MAX_VALUE
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

        /** Counts the complete prepared result, preserving every predicate and binding. */
        fun buildTagCountQuery(result: SupportSQLiteQuery): SupportSQLiteQuery = object : SupportSQLiteQuery {
            override val sql: String = "SELECT tags.tag AS tag, COUNT(DISTINCT tags.itemId) AS cnt " +
                "FROM item_tag AS tags INNER JOIN (${result.sql}) AS current_items " +
                "ON tags.itemId = current_items.id GROUP BY tags.tag"
            override val argCount: Int get() = result.argCount
            override fun bindTo(statement: androidx.sqlite.db.SupportSQLiteProgram) = result.bindTo(statement)
        }

        fun buildQuery(filter: Filter): SupportSQLiteQuery {
            val conditions = mutableListOf<String>()
            val args = mutableListOf<Any>()
            val rankTagMatches = !filter.untaggedOnly && !filter.andMode && filter.tags.size > 1
            val tagJoin = if (!filter.untaggedOnly && !filter.andMode && filter.tags.isNotEmpty()) {
                // 标签只绑定一次：聚合每项命中的不同已选标签数，避免排序再复制 IN 参数。
                val placeholders = filter.tags.joinToString(",") { "?" }
                args.addAll(filter.tags)
                " INNER JOIN (SELECT itemId, COUNT(DISTINCT tag) AS matchCount " +
                    "FROM item_tag WHERE tag IN ($placeholders) GROUP BY itemId) AS tag_matches " +
                    "ON items.id = tag_matches.itemId"
            } else ""

            if (filter.minStar > 0) {
                conditions += "star >= ?"
                args += filter.minStar
            }
            if (filter.nameQuery.isNotBlank()) {
                // 转义 LIKE 通配符：用户输入 %/_ 应按字面匹配
                conditions += "name LIKE '%' || ? || '%' ESCAPE '\\'"
                args += filter.nameQuery.trim()
                    .replace("\\", "\\\\")
                    .replace("%", "\\%")
                    .replace("_", "\\_")
            }
            if (filter.untaggedOnly) {
                // 未标记：不属于任何标签
                conditions += "id NOT IN (SELECT DISTINCT itemId FROM item_tag)"
            } else if (filter.andMode && filter.tags.isNotEmpty()) {
                val placeholders = filter.tags.joinToString(",") { "?" }
                // 与：同时含有全部选中标签；保持既有集合语义。
                conditions += "id IN (SELECT itemId FROM item_tag WHERE tag IN ($placeholders) " +
                    "GROUP BY itemId HAVING COUNT(DISTINCT tag) = ${filter.tags.size})"
                args.addAll(filter.tags)
            }

            // 老 SQLite 总变量预算仍为 tags + 可选评分 + 可选搜索 <= 999；
            // 当前 Room 2.7.2 的分页 LIMIT/OFFSET 使用整数字面量，不额外占变量。
            val sql = buildString {
                append(if (tagJoin.isEmpty()) "SELECT * FROM items" else "SELECT items.* FROM items")
                append(tagJoin)
                if (conditions.isNotEmpty()) {
                    append(" WHERE ")
                    append(conditions.joinToString(" AND "))
                }
                append(" ORDER BY ")
                if (rankTagMatches) append("tag_matches.matchCount DESC, ")
                append(filter.sort.clause)
                // 基础排序字段相同也保持稳定，避免分页与大图索引在重查时漂移。
                append(", id ASC")
            }
            return SimpleSQLiteQuery(sql, args.toTypedArray())
        }
    }
}
