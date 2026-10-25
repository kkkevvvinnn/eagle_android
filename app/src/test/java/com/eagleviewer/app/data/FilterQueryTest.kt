package com.eagleviewer.app.data

import androidx.sqlite.db.SupportSQLiteProgram
import androidx.sqlite.db.SupportSQLiteQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 筛选 SQL 组装的纯逻辑测试。 */
class FilterQueryTest {

    @Test
    fun `default filter has no where clause`() {
        val q = ItemRepository.buildQuery(Filter())
        assertEquals("SELECT * FROM items ORDER BY btime DESC, id ASC", q.sql)
        assertEquals(0, q.argCount)
    }

    @Test
    fun `star filter binds min star`() {
        val q = ItemRepository.buildQuery(Filter(minStar = 3))
        assertTrue(q.sql.contains("star >= ?"))
        assertEquals(1, q.argCount)
    }

    @Test
    fun `and mode requires all tags`() {
        val q = ItemRepository.buildQuery(Filter(tags = setOf("动漫", "花朵"), andMode = true))
        assertTrue(q.sql.contains("HAVING COUNT(DISTINCT tag) = 2"))
        assertTrue(q.sql.contains("tag IN (?,?)"))
        assertEquals(2, q.argCount)
    }

    @Test
    fun `or mode matches any tag`() {
        val q = ItemRepository.buildQuery(Filter(tags = setOf("动漫", "花朵"), andMode = false))
        assertTrue(q.sql.contains("COUNT(DISTINCT tag) AS matchCount"))
        assertTrue(q.sql.contains("INNER JOIN"))
        assertTrue(q.sql.contains("tag IN (?,?)"))
        assertTrue(q.sql.endsWith("ORDER BY tag_matches.matchCount DESC, btime DESC, id ASC"))
        assertTrue(!q.sql.contains("HAVING"))
        assertEquals(2, q.argCount)
    }

    @Test
    fun `combined star and tags`() {
        val q = ItemRepository.buildQuery(Filter(tags = setOf("动漫"), minStar = 4))
        assertTrue(q.sql.contains("star >= ?"))
        assertTrue(q.sql.contains("tag IN (?)"))
        assertEquals(2, q.argCount)
    }

    @Test
    fun `name query uses like`() {
        val q = ItemRepository.buildQuery(Filter(nameQuery = "截屏"))
        assertTrue(q.sql.contains("name LIKE '%' || ? || '%'"))
        assertEquals(1, q.argCount)
    }

    @Test
    fun `name query escapes like wildcards`() {
        // 用户输入的 %/_ 应按字面匹配，不能当通配符
        val q = ItemRepository.buildQuery(Filter(nameQuery = "100%_完成"))
        assertTrue(q.sql.contains("ESCAPE '\\'"))
        assertEquals(1, q.argCount)
    }

    @Test
    fun `untagged only excludes tagged items and ignores tag filter`() {
        val q = ItemRepository.buildQuery(Filter(tags = setOf("动漫"), untaggedOnly = true))
        assertTrue(q.sql.contains("id NOT IN (SELECT DISTINCT itemId FROM item_tag)"))
        assertTrue(!q.sql.contains("tag IN (?)"))
        assertEquals(0, q.argCount)
    }

    @Test
    fun `any binds selected tags only once before rating and literal name query`() {
        val tags = linkedSetOf("x') OR 1=1 --", "中文%_\\")
        val q = ItemRepository.buildQuery(Filter(tags = tags, minStar = 3, nameQuery = " 100%_\\ "))
        assertEquals(tags.toList() + listOf(3L, "100\\%\\_\\\\"), boundArgs(q))
        assertEquals(4, q.argCount)
        assertTrue(!q.sql.contains(tags.first()))
    }

    @Test
    fun `every base sort has stable id ties across any all and unfiltered`() {
        for (sort in Sort.entries) {
            for (filter in listOf(Filter(sort = sort),
                Filter(tags = setOf("a", "b"), sort = sort),
                Filter(tags = setOf("a", "b"), andMode = true, sort = sort))) {
                assertTrue(ItemRepository.buildQuery(filter).sql.endsWith("${sort.clause}, id ASC"))
            }
        }
    }

    @Test
    fun `single tag has only base order and large selections do not duplicate parameters`() {
        val single = ItemRepository.buildQuery(Filter(tags = setOf("a")))
        assertTrue(single.sql.endsWith("ORDER BY btime DESC, id ASC"))
        val q = ItemRepository.buildQuery(Filter(tags = (1..997).map { "tag$it" }.toSet(),
            minStar = 1, nameQuery = "name"))
        assertEquals(999, q.argCount)
        assertEquals(999, q.sql.count { it == '?' })
    }

    private fun boundArgs(query: SupportSQLiteQuery): List<Any?> {
        val args = MutableList<Any?>(query.argCount) { null }
        query.bindTo(object : SupportSQLiteProgram {
            override fun bindNull(index: Int) { args[index - 1] = null }
            override fun bindLong(index: Int, value: Long) { args[index - 1] = value }
            override fun bindDouble(index: Int, value: Double) { args[index - 1] = value }
            override fun bindString(index: Int, value: String) { args[index - 1] = value }
            override fun bindBlob(index: Int, value: ByteArray) { args[index - 1] = value }
            override fun clearBindings() { args.fill(null) }
            override fun close() {}
        })
        return args
    }
}
