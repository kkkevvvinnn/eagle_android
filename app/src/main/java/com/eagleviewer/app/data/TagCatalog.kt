package com.eagleviewer.app.data

import java.io.InputStream
import java.io.FileNotFoundException
import kotlinx.coroutines.CancellationException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Auxiliary Eagle documents are advisory; never used to decide which indexed items survive. */
object StrictTagJson {
    const val BYTE_LIMIT = 8 * 1024 * 1024

    fun read(stream: InputStream): String {
        val bytes = stream.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                require(output.size() + n <= BYTE_LIMIT) { "document exceeds 8 MiB" }
                output.write(buffer, 0, n)
            }
            output.toByteArray()
        }
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }

    /** Unlike the ordinary item decoder, rejects duplicate keys and lone surrogates. */
    fun parse(text: String): JsonElement {
        require(text.toByteArray(Charsets.UTF_8).size <= BYTE_LIMIT)
        return Reader(text).parse()
    }

    private class Reader(val text: String) {
        var i = 0
        fun whitespace() { while (i < text.length && text[i] in " \t\r\n") i++ }
        fun take(c: Char): Boolean { whitespace(); return if (i < text.length && text[i] == c) { i++; true } else false }
        fun parse(): JsonElement { val value = value(0); whitespace(); require(i == text.length); return value }
        fun value(depth: Int): JsonElement {
            require(depth <= 128) { "JSON nesting limit" }
            whitespace(); require(i < text.length)
            return when (text[i]) {
                '{' -> {
                    i++; val fields = linkedMapOf<String, JsonElement>()
                    if (!take('}')) do {
                        val key = string(); require(key !in fields) { "duplicate JSON key" }
                        require(take(':')); fields[key] = value(depth + 1)
                        if (take('}')) break
                        require(take(','))
                    } while (true)
                    JsonObject(fields)
                }
                '[' -> {
                    i++; val values = mutableListOf<JsonElement>()
                    if (!take(']')) do {
                        values += value(depth + 1)
                        if (take(']')) break
                        require(take(','))
                    } while (true)
                    JsonArray(values)
                }
                '"' -> JsonPrimitive(string())
                else -> {
                    val start = i
                    while (i < text.length && text[i] !in " \t\r\n,]}") i++
                    val token = text.substring(start, i)
                    require(token in listOf("null", "true", "false") ||
                        Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?").matches(token))
                    Json.parseToJsonElement(token)
                }
            }
        }
        fun string(): String {
            whitespace(); require(i < text.length && text[i] == '"')
            val start = i++
            var closed = false
            while (i < text.length) {
                val c = text[i++]
                require(c >= ' ') { "unescaped control" }
                if (c == '"') { closed = true; break }
                if (c == '\\') { require(i < text.length); i++ }
            }
            require(closed)
            val result = Json.decodeFromString<String>(text.substring(start, i))
            var p = 0
            while (p < result.length) {
                val c = result[p++]
                if (c.isHighSurrogate()) require(p < result.length && result[p++].isLowSurrogate())
                else require(!c.isLowSurrogate())
            }
            return result
        }
    }
}

@Serializable
data class TagGroup(val id: String, val name: String, val tags: List<String>)
@Serializable
data class OptionalTags(
    val groups: List<TagGroup> = emptyList(),
    val recent: Map<String, Int> = emptyMap(),
    val groupsStatus: String = "分组不可用，按索引标签显示",
    val historyStatus: String = "Mac 近期记录不可用，按覆盖数排序",
)
@Serializable
data class TagCatalogSnapshot(val rootUri: String = "", val generation: Long = 0, val payload: OptionalTags? = null) {
    /** Invalidate before changing the index; even A→B→A cannot accept an old A reply. */
    fun begin(root: String): TagCatalogSnapshot = TagCatalogSnapshot(root, generation + 1, if (root == rootUri) payload else null)
    fun publish(ticket: TagCatalogSnapshot, value: OptionalTags): TagCatalogSnapshot =
        if (rootUri == ticket.rootUri && generation == ticket.generation) copy(payload = value) else this
    fun forRoot(root: String): OptionalTags = if (rootUri == root) payload ?: OptionalTags() else OptionalTags()
}

data class CatalogTag(val tag: String, val current: Int, val global: Int, val recent: Int)
data class TagSection(val name: String, val tags: List<CatalogTag>)
data class TagCatalog(val sections: List<TagSection> = emptyList(), val status: String = "")

object TagCatalogParser {
    const val WINDOW_MS = 30L * 24 * 60 * 60 * 1000
    private fun JsonElement.obj() = this as? JsonObject ?: error("expected object")
    private fun JsonElement.arr() = this as? JsonArray ?: error("expected array")
    private fun JsonElement.str(bounded: Boolean = false): String {
        val p = this as? JsonPrimitive ?: error("expected string")
        require(p.isString)
        return p.content.also { require(!bounded || it.toByteArray(Charsets.UTF_8).size <= 65536) }
    }
    private fun JsonObject.exact(keys: Set<String>) { require(this.keys == keys) { "unexpected keys" } }
    private fun JsonElement.integer(max: Long): Long {
        val p = this as? JsonPrimitive ?: error("expected integer")
        require(!p.isString && Regex("-?(0|[1-9][0-9]*)").matches(p.content))
        return p.content.toLong().also { require(it in 0..max) }
    }

    fun groups(text: String): Pair<List<TagGroup>, String> {
        val root = StrictTagJson.parse(text).obj()
        require(root["applicationVersion"]?.str(true) == "4.0.0") { "unsupported group version" }
        val raw = root["tagsGroups"] ?: return emptyList<TagGroup>() to ""
        val groups = raw.arr(); require(groups.size <= 1000)
        var occurrences = 0
        var unknown = false
        val parsed = groups.map { value ->
            val g = value.obj()
            unknown = unknown || (g.keys - setOf("id", "name", "tags", "color", "description")).isNotEmpty()
            g["color"]?.str(true); g["description"]?.str(true)
            TagGroup(g.getValue("id").str(true).also { require(it.isNotEmpty()) }, g.getValue("name").str(true),
                g.getValue("tags").arr().map { it.str(true).also { occurrences++; require(occurrences <= 100000) } })
        }
        val allTags = parsed.flatMap { it.tags }
        val conflict = parsed.map { it.id }.distinct().size != parsed.size || allTags.distinct().size != allTags.size
        return parsed to listOfNotNull(
            if (conflict) "分组有重复归属，已保留原始成员" else null,
            if (unknown) "分组含未知字段，仅显示已知内容" else null,
        ).joinToString("；")
    }

    /** Each optional source fails independently. This reader has no index or write capability. */
    fun load(read: (String) -> String, now: Long): OptionalTags {
        val groups = try {
            groups(read("metadata.json"))
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            emptyList<TagGroup>() to "分组不可用，按索引标签显示"
        }
        var historyStatus = ""
        val recent = try {
            recent(read("kevin-tag-history.json"), now)
        } catch (e: CancellationException) { throw e
        } catch (e: FileNotFoundException) {
            historyStatus = "未提供 Mac 近期记录，按覆盖数排序"
            emptyMap()
        } catch (e: Exception) {
            historyStatus = "Mac 近期记录不可用，按覆盖数排序"
            emptyMap()
        }
        return OptionalTags(groups.first, recent, groups.second, historyStatus)
    }

    fun recent(text: String, now: Long): Map<String, Int> {
        val root = StrictTagJson.parse(text).obj()
        root.exact(setOf("schemaVersion", "source", "actions"))
        require(root.getValue("schemaVersion").integer(1) == 1L)
        require(root.getValue("source").str() == "Mac")
        val actions = root.getValue("actions").arr(); require(actions.size <= 20000)
        val transactions = hashSetOf<String>()
        val counts = mutableMapOf<String, Int>()
        var associationCount = 0
        actions.forEach { element ->
            val action = element.obj(); action.exact(setOf("transactionID", "timestampMs", "added"))
            val transaction = action.getValue("transactionID").str()
            require(Regex("[a-f0-9]{64}").matches(transaction) && transactions.add(transaction))
            val timestamp = action.getValue("timestampMs").integer(9007199254740991L)
            val pairs = hashSetOf<Pair<String, String>>()
            action.getValue("added").arr().forEach { association ->
                require(++associationCount <= 100000)
                val pair = association.obj(); pair.exact(setOf("id", "tag"))
                val id = pair.getValue("id").str()
                require(Regex("(?:[A-Za-z0-9]{13}|[A-Za-z0-9-]{36})").matches(id))
                val tag = pair.getValue("tag").str()
                require(pairs.add(id to tag))
                if (timestamp <= now && timestamp >= now - WINDOW_MS) counts[tag] = (counts[tag] ?: 0) + 1
            }
        }
        return counts
    }

    val utf8Order = Comparator<String> { a, b ->
        val x = a.toByteArray(Charsets.UTF_8); val y = b.toByteArray(Charsets.UTF_8)
        var result = 0
        for (i in 0 until minOf(x.size, y.size)) {
            result = (x[i].toInt() and 255).compareTo(y[i].toInt() and 255)
            if (result != 0) break
        }
        if (result == 0) x.size.compareTo(y.size) else result
    }

    fun catalog(optional: OptionalTags, global: Map<String, Int>, current: Map<String, Int>): TagCatalog {
        val comparator = Comparator<String> { a, b ->
            val recent = (optional.recent[b] ?: 0).compareTo(optional.recent[a] ?: 0)
            if (recent != 0) recent else {
                val coverage = (global[b] ?: 0).compareTo(global[a] ?: 0)
                if (coverage != 0) coverage else utf8Order.compare(a, b)
            }
        }
        fun tags(values: Collection<String>) = values.sortedWith(comparator).map {
            CatalogTag(it, current[it] ?: 0, global[it] ?: 0, optional.recent[it] ?: 0)
        }
        val grouped = optional.groups.flatMap { it.tags }.toSet()
        val sections = optional.groups.map { TagSection(it.name, tags(it.tags)) } +
            TagSection("未分组", tags(global.keys - grouped))
        return TagCatalog(sections, listOf(optional.groupsStatus, optional.historyStatus).filter { it.isNotEmpty() }.joinToString("；"))
    }
}
