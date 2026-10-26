package com.eagleviewer.app.data

import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class TagCatalogTest {
    private val now = 4_000_000_000L
    private fun action(time: Long = now, token: Char = 'a', added: String = """[{"id":"ABCDEFGHIJKLM","tag":"猫"}]""") =
        """{"transactionID":"${token.toString().repeat(64)}","timestampMs":$time,"added":$added}"""
    private fun history(vararg actions: String) = """{"schemaVersion":1,"source":"Mac","actions":[${actions.joinToString(",")}]}"""
    private fun reject(label: String, block: () -> Any?) = assertTrue(label, runCatching(block).isFailure)

    @Test fun `window includes both ends excludes future and old but validates all`() {
        val h = history(action(now - TagCatalogParser.WINDOW_MS, 'a'), action(now, 'b'),
            action(now - TagCatalogParser.WINDOW_MS - 1, 'c'), action(now + 1, 'd'))
        assertEquals(mapOf("猫" to 2), TagCatalogParser.recent(h, now))
        // Out-of-window malformed actions still invalidate the whole file.
        reject("old action schema") { TagCatalogParser.recent(history(action(0).replace("timestampMs", "time")), now) }
    }

    @Test fun `association additions count across actions but duplicate pair within action rejects`() {
        val pair = """{"id":"ABCDEFGHIJKLM","tag":"猫"}"""
        assertEquals(mapOf("猫" to 2), TagCatalogParser.recent(history(action(token = 'a'), action(token = 'b')), now))
        reject("duplicate pair") { TagCatalogParser.recent(history(action(added = "[$pair,$pair]")), now) }
        reject("duplicate transaction") { TagCatalogParser.recent(history(action(), action()), now) }
        assertEquals(emptyMap<String, Int>(), TagCatalogParser.recent(history(action(added = "[]")), now))
    }

    @Test fun `history rejects extra keys at every level and wrong sources`() {
        val good = history(action())
        val invalid = listOf(
            good.replace("\"Mac\"", "\"Android\""),
            good.replace("\"actions\":", "\"extra\":0,\"actions\":"),
            good.replace("\"added\":", "\"extra\":0,\"added\":"),
            good.replace("\"tag\":", "\"extra\":0,\"tag\":"),
            good.replace("ABCDEFGHIJKLM", "bad"),
            good.replace("a".repeat(64), "A".repeat(64)),
            good.replace("\"猫\"", "null"),
        )
        invalid.forEachIndexed { i, text -> reject("schema $i") { TagCatalogParser.recent(text, now) } }
    }

    @Test fun `history requires real safe integers and exact schema`() {
        val good = history(action())
        listOf("true", "1.0", "1e0", "\"1\"", "null", "0", "2").forEach { value ->
            reject("schemaVersion $value") { TagCatalogParser.recent(good.replace("\"schemaVersion\":1", "\"schemaVersion\":$value"), now) }
        }
        listOf("true", "1.0", "1e0", "\"1\"", "null", "-1", "9007199254740992", "9999999999999999999999999999").forEach { value ->
            reject("timestamp $value") { TagCatalogParser.recent(good.replace("\"timestampMs\":$now", "\"timestampMs\":$value"), now) }
        }
        assertTrue(TagCatalogParser.recent(history(action(9007199254740991L)), now).isEmpty())
    }

    @Test fun `strict reader rejects duplicate keys including escaped aliases and nested keys`() {
        listOf("""{"a":1,"a":2}""", """{"a":1,"\u0061":2}""",
            """{"outer":[{"x":0,"x":1}]}""").forEach { reject("duplicate") { StrictTagJson.parse(it) } }
        assertEquals("{\"a\":{\"a\":1}}", StrictTagJson.parse("""{"a":{"a":1}}""").toString())
        reject("history duplicate") { TagCatalogParser.recent(history(action()).replace("\"source\":\"Mac\"", "\"source\":\"Android\",\"source\":\"Mac\""), now) }
    }

    @Test fun `strict reader rejects lone surrogates malformed UTF8 and invalid JSON syntax`() {
        listOf("\"\\ud800\"", "\"\\udc00\"", "\"\\ud800x\"", "\"${'\uD800'}\"",
            "{\"x\":NaN}", "[1,]", "{\"a\":1,}", "[01]", "[+1]", "{\"a\":Infinity}",
            "[true false]", "/*x*/{}", "{\"a\":\"\\x20\"}").forEach { reject(it) { StrictTagJson.parse(it) } }
        assertEquals("😀", (StrictTagJson.parse("\"\\ud83d\\ude00\"") as kotlinx.serialization.json.JsonPrimitive).content)
        reject("UTF8") { StrictTagJson.read(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28))) }
    }

    @Test fun `stream bound stops before consuming oversized source and allows exact limit`() {
        var consumed = 0
        val huge = object : InputStream() {
            override fun read(): Int { consumed++; return 32 }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                b.fill(32, off, off + len); consumed += len; return len
            }
        }
        reject("bounded") { StrictTagJson.read(huge) }
        assertTrue(consumed <= StrictTagJson.BYTE_LIMIT + 8192)
        assertEquals(StrictTagJson.BYTE_LIMIT,
            StrictTagJson.read(ByteArrayInputStream(ByteArray(StrictTagJson.BYTE_LIMIT) { 32 })).length)
        reject("text bound") { StrictTagJson.parse(" ".repeat(StrictTagJson.BYTE_LIMIT + 1)) }
        reject("nest bound") { StrictTagJson.parse("[".repeat(130) + "0" + "]".repeat(130)) }
    }

    @Test fun `group projection preserves original duplicate occurrences empty groups and warns`() {
        val parsed = TagCatalogParser.groups("""{"applicationVersion":"4.0.0","tagsGroups":[
            {"id":"same","name":"首组","tags":["b","a","a"],"custom":{}},
            {"id":"same","name":"次组","tags":["a"]},
            {"id":"empty","name":"空组","tags":[]}] }""")
        assertEquals(listOf("首组", "次组", "空组"), parsed.first.map { it.name })
        assertEquals(listOf("b", "a", "a"), parsed.first.first().tags)
        assertTrue(parsed.second.contains("重复归属")); assertTrue(parsed.second.contains("未知字段"))
        val catalog = TagCatalogParser.catalog(OptionalTags(parsed.first, groupsStatus = parsed.second), mapOf("a" to 1, "z" to 2), emptyMap())
        assertEquals(listOf("a", "a", "b"), catalog.sections.first().tags.map { it.tag })
        assertEquals(listOf("z"), catalog.sections.last().tags.map { it.tag })
        assertEquals(0, catalog.sections.first().tags.last().global)
    }

    @Test fun `malformed group data rejects whole projection independently from valid history`() {
        val base = """{"applicationVersion":"4.0.0","tagsGroups":[{"id":"g","name":"","tags":[""]}]}"""
        assertEquals(listOf(""), TagCatalogParser.groups(base).first.single().tags)
        assertTrue(TagCatalogParser.groups("""{"applicationVersion":"4.0.0"}""").first.isEmpty())
        listOf(base.replace("4.0.0", "5.0.0"), base.replace("[\"\"]", "null"),
            base.replace("\"id\":\"g\"", "\"id\":\"\""),
            base.replace("\"name\":\"\"", "\"name\":4"),
            base.replace("\"tags\":[\"\"]", "\"tags\":[3]"),
            base.replace("\"tags\":[\"\"]", "\"color\":null,\"tags\":[\"\"]")).forEach { reject("group") { TagCatalogParser.groups(it) } }
        assertEquals(mapOf("猫" to 1), TagCatalogParser.recent(history(action()), now))
    }

    @Test fun `literal UTF8 ordering ranks supplementary after BMP preserves NFC NFD and history cannot invent catalog`() {
        val nfc = "é"; val nfd = "e\u0301"; val bmp = "\uE000"; val supplementary = "\uD83D\uDE00"
        assertTrue(TagCatalogParser.utf8Order.compare(bmp, supplementary) < 0)
        val global = mapOf(nfc to 1, nfd to 1, bmp to 1, supplementary to 1, "most" to 20)
        val optional = OptionalTags(groups = listOf(TagGroup("g", "g", listOf("only-group"))),
            recent = mapOf("ghost" to 999, nfc to 5, nfd to 2))
        val result = TagCatalogParser.catalog(optional, global, mapOf(nfc to 0, nfd to 1))
        assertEquals(listOf(nfc, nfd, "most", bmp, supplementary), result.sections.last().tags.map { it.tag })
        assertEquals(0, result.sections.first().tags.single().global)
        assertFalse(result.sections.flatMap { it.tags }.any { it.tag == "ghost" })
    }

    @Test fun `empty long and literal history tags retain Python schema meaning`() {
        val longTag = "t".repeat(65537)
        val added = Json.encodeToString(listOf(mapOf("id" to "ABCDEFGHIJKLM", "tag" to longTag),
            mapOf("id" to "ABCDEFGHIJKLM", "tag" to ""), mapOf("id" to "ABCDEFGHIJKLM", "tag" to " x,\ny ")))
        assertEquals(mapOf(longTag to 1, "" to 1, " x,\ny " to 1), TagCatalogParser.recent(history(action(added = added)), now))
        val many = (0..20000).joinToString(",") { action(token = 'a').replace("a".repeat(64), it.toString(16).padStart(64, '0')) }
        reject("action limit") { TagCatalogParser.recent("""{"schemaVersion":1,"source":"Mac","actions":[$many]}""", now) }
    }

    @Test fun `optional failures are independent every refresh and cancellation propagates`() {
        val root = """{"applicationVersion":"4.0.0","tagsGroups":[{"id":"g","name":"G","tags":["猫"]}]}"""
        var reads = 0
        val good = TagCatalogParser.load({ name -> reads++; if (name == "metadata.json") root else history(action()) }, now)
        assertEquals(2, reads); assertEquals(1, good.groups.size); assertEquals(1, good.recent["猫"])
        val badRoot = TagCatalogParser.load({ name -> if (name == "metadata.json") "{" else history(action()) }, now)
        assertTrue(badRoot.groups.isEmpty()); assertEquals(1, badRoot.recent["猫"])
        val badHistory = TagCatalogParser.load({ name -> if (name == "metadata.json") root else "{" }, now)
        assertEquals(good.groups, badHistory.groups); assertTrue(badHistory.recent.isEmpty())
        val missing = TagCatalogParser.load({ throw java.io.FileNotFoundException() }, now)
        assertTrue(missing.groups.isEmpty()); assertTrue(missing.recent.isEmpty())
        assertTrue(missing.historyStatus.contains("未提供"))
        var secondRead = false
        try {
            TagCatalogParser.load({ name ->
                if (name == "metadata.json") throw kotlinx.coroutines.CancellationException("cancel")
                secondRead = true; history(action())
            }, now)
            fail("cancellation swallowed")
        } catch (_: kotlinx.coroutines.CancellationException) {}
        assertFalse(secondRead)
        try {
            TagCatalogParser.load({ name -> if (name == "metadata.json") root
                else throw kotlinx.coroutines.CancellationException("cancel") }, now)
            fail("history cancellation swallowed")
        } catch (_: kotlinx.coroutines.CancellationException) {}
    }

    @Test fun `history association and group structural limits have finite whole document fallback`() {
        val pairs = (0 until 100000).joinToString(",") {
            """{"id":"${it.toString().padStart(13, '0')}","tag":""}"""
        }
        val maximum = action(added = "[$pairs]")
        assertEquals(mapOf("" to 100000), TagCatalogParser.recent(history(maximum), now))
        reject("association limit") { TagCatalogParser.recent(history(maximum,
            action(token = 'b', added = """[{"id":"0000000000000","tag":""}]""")), now) }
        val group = """{"id":"g","name":"G","tags":[]}"""
        reject("group limit") { TagCatalogParser.groups("""{"applicationVersion":"4.0.0","tagsGroups":[${List(1001) { group }.joinToString(",")}]}""") }
        reject("group string byte limit") {
            TagCatalogParser.groups("""{"applicationVersion":"4.0.0","tagsGroups":[{"id":"g","name":"${"猫".repeat(21846)}","tags":[]}]}""")
        }
    }

    @Test fun `persistent snapshot round trip binds complete root URI and rejects late ABA replies`() {
        val a = "content://authorityA/tree/same"; val b = "content://authorityB/tree/same"
        val payload = OptionalTags(listOf(TagGroup("g", "A", listOf("a"))), mapOf("a" to 2), "", "")
        var cache = TagCatalogSnapshot().begin(a)
        val firstA = cache
        cache = cache.publish(firstA, payload)
        cache = Json.decodeFromString<TagCatalogSnapshot>(Json.encodeToString(cache))
        assertEquals(payload, cache.forRoot(a))
        assertTrue(cache.forRoot(b).groups.isEmpty())
        cache = cache.begin(b); val ticketB = cache
        assertNull(cache.payload) // No successful B reply: cancellation leaves old optional data invalidated.
        cache = cache.begin(a); val secondA = cache
        assertNull(cache.payload)
        assertEquals(cache, cache.publish(firstA, payload))
        assertEquals(cache, cache.publish(ticketB, payload))
        cache = cache.publish(secondA, payload)
        assertEquals(payload, cache.forRoot(a))
        val refresh = cache.begin(a)
        assertEquals(payload, refresh.payload) // Same-root last good until successful core scan.
        assertTrue(refresh.publish(refresh, OptionalTags()).forRoot(a).groups.isEmpty())
    }
}
