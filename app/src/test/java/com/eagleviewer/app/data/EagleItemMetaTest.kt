package com.eagleviewer.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用来自 `/Users/cwk/Pictures/我的灵感.library` 的真实样本（只读拷贝其 JSON 文本）
 * 验证元数据解析。
 */
class EagleItemMetaTest {

    // images/MS1RIJ0HJTER8.info/metadata.json：无标签、无评分（star 字段不存在）
    private val samplePlain = """
        {"id":"MS1RIJ0HJTER8","name":"Screenshot_20260726_165835","size":934817,"btime":1785056714510,"mtime":1785068147755,"ext":"jpg","tags":[],"folders":[],"isDeleted":false,"url":"","annotation":"","modificationTime":1785068195777,"height":2246,"width":1193,"lastModified":1785068196035,"palettes":[{"color":[28,21,18],"ratio":41},{"color":[152,127,117],"ratio":23}]}
    """.trimIndent()

    // images/MS8XSOIKXC4VX.info/metadata.json：带标签，palettes 含 $$hashKey 未知字段
    private val sampleTagged = """
        {"id":"MS8XSOIKXC4VX","name":"截屏2026-07-31 20.46.37","size":1552762,"btime":1785502005246,"mtime":1785502005296,"ext":"png","tags":["动漫"],"folders":[],"isDeleted":false,"url":"","annotation":"","modificationTime":1785502010396,"height":1050,"width":1660,"lastModified":1785833884490,"palettes":[{"color":[249,225,239],"ratio":70,"${'$'}${'$'}hashKey":"object:1624"},{"color":[68,46,63],"ratio":12,"${'$'}${'$'}hashKey":"object:1629"}]}
    """.trimIndent()

    // images/MS693ZHZIX8JJ.info/metadata.json：回收站条目
    private val sampleDeleted = """
        {"id":"MS693ZHZIX8JJ","name":"Screenshot_20260729_232650","size":432011,"btime":1785338872597,"mtime":1785338873236,"ext":"jpg","tags":[],"folders":[],"isDeleted":true,"url":"","annotation":"","modificationTime":1785339615100,"height":3168,"width":1440,"palettes":[{"color":[19,19,22],"ratio":87,"${'$'}${'$'}hashKey":"object:806"}],"deletedTime":1785339635901,"lastModified":1785339635926}
    """.trimIndent()

    @Test
    fun `plain sample parses with defaults`() {
        val meta = EagleItemMeta.parse(samplePlain)
        assertEquals("MS1RIJ0HJTER8", meta.id)
        assertEquals("Screenshot_20260726_165835", meta.name)
        assertEquals("jpg", meta.ext)
        assertEquals(1193, meta.width)
        assertEquals(2246, meta.height)
        assertEquals(0, meta.star) // 未评分时字段缺省为 0
        assertTrue(meta.tags.isEmpty())
        assertFalse(meta.isDeleted)
        assertEquals(1785068196035L, meta.lastModified)
        // palettes[0] = [28,21,18]
        assertEquals(0xFF1C1512L, meta.primaryColor)
    }

    @Test
    fun `tagged sample keeps chinese tag and ignores hashKey`() {
        val meta = EagleItemMeta.parse(sampleTagged)
        assertEquals(listOf("动漫"), meta.tags)
        assertEquals("png", meta.ext)
        assertEquals(0xFFF9E1EFL, meta.primaryColor)
    }

    @Test
    fun `deleted sample flagged`() {
        val meta = EagleItemMeta.parse(sampleDeleted)
        assertTrue(meta.isDeleted)
    }

    @Test
    fun `mtime json parses and excludes all`() {
        val text = """{"MS1RIJ0HJTER8":1785068196035,"MS693ZHZIX8JJ":1785339635926,"all":33}"""
        val map = parseMtimeJson(text)
        assertEquals(2, map.size)
        assertEquals(1785068196035L, map["MS1RIJ0HJTER8"])
        assertNull(map["all"])
    }
}
