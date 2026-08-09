package com.eagleviewer.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 筛选 SQL 组装的纯逻辑测试。 */
class FilterQueryTest {

    @Test
    fun `default filter has no where clause`() {
        val q = ItemRepository.buildQuery(Filter())
        assertEquals("SELECT * FROM items ORDER BY btime DESC", q.sql)
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
        assertTrue(q.sql.contains("SELECT DISTINCT itemId FROM item_tag WHERE tag IN (?,?)"))
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
}
