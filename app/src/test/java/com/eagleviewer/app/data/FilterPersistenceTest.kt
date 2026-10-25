package com.eagleviewer.app.data

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterPersistenceTest {
    @Test
    fun `fresh filter and no saved state default to any`() {
        assertFalse(Filter().andMode)
        assertFalse(FilterPersistence.decode("").andMode)
    }

    @Test
    fun `legacy omitted mode remains all while explicit modes are respected`() {
        assertTrue(FilterPersistence.decode("{}").andMode)
        assertTrue(FilterPersistence.decode("""{"tags":["动漫","花朵"]}""").andMode)
        assertTrue(FilterPersistence.decode("""{"andMode":true}""").andMode)
        assertFalse(FilterPersistence.decode("""{"andMode":false}""").andMode)
    }

    @Test
    fun `new saves always include mode and preserve all filter fields`() {
        for (andMode in listOf(false, true)) {
            val filter = Filter(tags = linkedSetOf("动漫", "花朵"), andMode = andMode,
                minStar = 3, sort = Sort.SIZE_ASC, nameQuery = "100%_\\", untaggedOnly = false)
            val saved = FilterPersistence.encode(filter)
            assertEquals(andMode.toString(), EagleItemMeta.json.parseToJsonElement(saved)
                .jsonObject.getValue("andMode").jsonPrimitive.content)
            assertEquals(filter, FilterPersistence.decode(saved))
        }
        assertEquals("false", EagleItemMeta.json.parseToJsonElement(FilterPersistence.encode(Filter()))
            .jsonObject.getValue("andMode").jsonPrimitive.content)
    }
}
