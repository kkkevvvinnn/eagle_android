package com.eagleviewer.app.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Golden captured from production Python, not recomputed by the Kotlin under test. */
class TagCatalogGoldenTest {
    private fun text(name: String) = javaClass.classLoader!!.getResource("m5b/$name")!!.readText()
    @Test fun `same contract histories match Python ready blocked and recent counts`() {
        val golden = Json.parseToJsonElement(text("golden.json")).jsonObject
        val cases = Json.parseToJsonElement(text("cases.json")).jsonObject
        val now = golden.getValue("nowMs").jsonPrimitive.long
        val expected = golden.getValue("baseline").jsonObject.getValue("usage").jsonObject
            .getValue("recentCounts").jsonArray.associate {
                it.jsonObject.getValue("tag").jsonPrimitive.content to it.jsonObject.getValue("count").jsonPrimitive.int
            }
        assertEquals(expected, TagCatalogParser.recent(text("history.json"), now))
        val expectedCases = golden.getValue("historyCases").jsonObject
        cases.getValue("invalidHistoryReplacements").jsonObject.forEach { (name, value) ->
            assertEquals("blocked", expectedCases.getValue(name).jsonObject.getValue("status").jsonPrimitive.content)
            assertTrue(name, runCatching { TagCatalogParser.recent(value.toString(), now) }.isFailure)
        }
        val merged = golden.getValue("mergeExample").jsonObject
        val target = TagCatalogParser.recent(merged.getValue("after").toString(), now)
        assertEquals(merged.getValue("targetRecentCount").jsonPrimitive.int, target["目标"])
        assertEquals(1, target.size)
    }
    @Test fun `same contract group cases preserve Python accepted membership and reject blocked structures`() {
        val golden = Json.parseToJsonElement(text("golden.json")).jsonObject
        val cases = Json.parseToJsonElement(text("cases.json")).jsonObject
        cases.getValue("groupRootReplacements").jsonObject.forEach { (name, value) ->
            val expected = golden.getValue("groupCases").jsonObject.getValue(name).jsonObject
            val status = expected.getValue("status").jsonPrimitive.content
            val parsed = runCatching { TagCatalogParser.groups(value.toString()) }
            if (status in setOf("blocked", "unsupportedVersion")) assertTrue(name, parsed.isFailure)
            else {
                val actual = parsed.getOrThrow()
                val expectedGroups = expected.getValue("groups").jsonArray.map {
                    val group = it.jsonObject
                    TagGroup(group.getValue("id").jsonPrimitive.content, group.getValue("name").jsonPrimitive.content,
                        group.getValue("tags").jsonArray.map { tag -> tag.jsonPrimitive.content })
                }
                assertEquals(expectedGroups, actual.first)
                if (status == "membershipConflict") assertTrue(actual.second.contains("重复归属"))
            }
        }
        assertEquals(2, TagCatalogParser.groups(text("metadata.json")).first.size)
    }
}
