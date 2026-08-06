package com.eagleviewer.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 相似配色检索的纯逻辑测试。 */
class ColorSearchTest {

    private val palettesA = """[{"color":[249,225,239],"ratio":70},{"color":[68,46,63],"ratio":12}]"""
    private val palettesB = """[{"color":[28,21,18],"ratio":41},{"color":[152,127,117],"ratio":23}]"""

    @Test
    fun `exact palette color has zero distance`() {
        val pink = (249 shl 16) or (225 shl 8) or 239
        assertEquals(0.0, ItemRepository.paletteDistance(palettesA, pink), 0.001)
    }

    @Test
    fun `closer color ranks smaller distance`() {
        val pink = (250 shl 16) or (220 shl 8) or 240
        val dark = (10 shl 16) or (10 shl 8) or 10
        val distA = ItemRepository.paletteDistance(palettesA, pink)
        val distB = ItemRepository.paletteDistance(palettesB, pink)
        assertTrue(distA < distB)
        assertTrue(ItemRepository.paletteDistance(palettesA, dark) > 0)
    }

    @Test
    fun `empty palettes excluded`() {
        assertEquals(Double.MAX_VALUE, ItemRepository.paletteDistance("", 0), 0.0)
        assertEquals(Double.MAX_VALUE, ItemRepository.paletteDistance("[]", 0), 0.0)
        assertEquals(Double.MAX_VALUE, ItemRepository.paletteDistance("not json", 0), 0.0)
    }

    @Test
    fun `similar query preserves ranking via case order`() {
        val q = ItemRepository.buildSimilarQuery(listOf("C", "A", "B"))
        assertTrue(q.sql.contains("id IN (?,?,?)"))
        assertTrue(q.sql.contains("ORDER BY CASE id WHEN ? THEN 0 WHEN ? THEN 1 WHEN ? THEN 2 END"))
        assertEquals(6, q.argCount)
    }

    @Test
    fun `empty ranking matches nothing`() {
        assertEquals("SELECT * FROM items WHERE 0", ItemRepository.buildSimilarQuery(emptyList()).sql)
    }
}
