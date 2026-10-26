package com.eagleviewer.app.data

import androidx.sqlite.db.SupportSQLiteProgram
import androidx.sqlite.db.SupportSQLiteQuery
import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TagCountQueryTest {
    private fun args(q: SupportSQLiteQuery): List<Any?> {
        val args = MutableList<Any?>(q.argCount) { null }
        q.bindTo(object : SupportSQLiteProgram {
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

    @Test fun `count query preserves exact prepared query and parameter order across all filter modes`() {
        val poison = "x') OR 1=1 --"
        for (sort in Sort.entries) for (all in listOf(false, true)) {
            val query = ItemRepository.buildQuery(Filter(tags = linkedSetOf(poison, "b"), andMode = all,
                minStar = 4, nameQuery = " 100%_\\ ", sort = sort))
            val count = ItemRepository.buildTagCountQuery(query)
            assertTrue(count.sql.contains("(${query.sql}) AS current_items"))
            assertFalse(count.sql.contains(poison))
            assertTrue(count.sql.contains("COUNT(DISTINCT tags.itemId)"))
            assertEquals(query.argCount, count.argCount)
            assertEquals(args(query), args(count))
        }
        val max = ItemRepository.buildTagCountQuery(ItemRepository.buildQuery(Filter(
            tags = (1..997).map { "t$it" }.toSet(), minStar = 1, nameQuery = "x")))
        assertEquals(999, max.argCount)
    }

    @Test fun `same color ranked list is used by paging and counts including empty result`() {
        for (ids in listOf(emptyList(), listOf("i3", "i1"), (1..150).map { "color$it" })) {
            val result = ItemRepository.buildSimilarQuery(ids)
            val count = ItemRepository.buildTagCountQuery(result)
            assertEquals(args(result), args(count))
            assertEquals(ids.size * 2, count.argCount)
            assertTrue(count.sql.contains(result.sql))
        }
    }

    /** Optional evidence export contains the actual production Kotlin SQL and bound parameters. */
    @Test fun `export bounded real queries for host SQLite semantic execution`() {
        val output = System.getenv("M5B_SQL_EXPORT") ?: return
        val poison = "x') OR 1=1 --"
        val filters = linkedMapOf(
            "default" to Filter(),
            "any" to Filter(tags = linkedSetOf("a", "b")),
            "all" to Filter(tags = linkedSetOf("a", "b"), andMode = true),
            "literal_name" to Filter(tags = setOf("a"), nameQuery = "100%_"),
            "injection" to Filter(tags = linkedSetOf(poison, "b"), minStar = 4, nameQuery = "100%_"),
            "zero" to Filter(tags = setOf("absent")),
            "untagged" to Filter(tags = setOf("a"), untaggedOnly = true),
            "slash" to Filter(nameQuery = "\\_%"),
        )
        val queries = filters.mapValues { ItemRepository.buildQuery(it.value) }.toMutableMap()
        queries["similar"] = ItemRepository.buildSimilarQuery(listOf("i3", "i1"))
        queries["similar_empty"] = ItemRepository.buildSimilarQuery(emptyList())
        queries["similar_150"] = ItemRepository.buildSimilarQuery((1..150).map { "color$it" })
        for (sort in Sort.entries) queries["all_${sort.name}"] = ItemRepository.buildQuery(
            Filter(tags = linkedSetOf("a", "b"), andMode = true, sort = sort))
        val json = buildJsonObject {
            put("origin", "ItemRepository.buildQuery/buildSimilarQuery/buildTagCountQuery JVM export")
            put("queries", buildJsonObject {
                queries.forEach { (name, query) ->
                    val count = ItemRepository.buildTagCountQuery(query)
                    put(name, buildJsonObject {
                        put("resultSql", query.sql); put("countSql", count.sql)
                        put("args", JsonArray(args(count).map { value -> when(value) {
                            null -> JsonNull
                            is String -> JsonPrimitive(value)
                            is Number -> JsonPrimitive(value)
                            else -> error("unsupported export binding")
                        } }))
                    })
                }
            })
        }
        File(output).writeText(json.toString())
        assertTrue(File(output).length() > 100)
    }
}
