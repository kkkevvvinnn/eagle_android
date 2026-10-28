package com.eagleviewer.app.data

import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Runs the production coordinator and paging guard, with a fake durable store/Room seam.
 * These are JVM fault-injection checks, not DataStore/Room/SAF instrumentation. */
class IndexPublicationTest {
    private val a = "content://provider.a/tree/primary%3AA.library"
    private val b = "content://provider.b/tree/primary%3AA.library"
    private fun ready(root: String = a) = IndexPublication(root, root, 5, true, 10)
    private class Store(initial: IndexPublication) : IndexPublicationStore {
        var disk = Json.encodeToString(initial)
        var writes = 0
        var beforeRead: suspend () -> Unit = {}
        var beforeWrite: suspend (IndexPublication) -> Unit = {}
        var afterWrite: suspend (IndexPublication) -> Unit = {}
        override suspend fun readPublication(): IndexPublication { beforeRead(); return Json.decodeFromString<IndexPublication>(disk) }
        override suspend fun writePublication(value: IndexPublication) {
            beforeWrite(value)
            disk = Json.encodeToString(value)
            writes++
            afterWrite(value)
        }
    }
    private class Rows(root: String) {
        val rows = linkedMapOf("same" to "$root/same")
        var cleared = 0
        suspend fun build(root: String, rebuild: Boolean, boundary: suspend (String) -> Unit): Int {
            boundary("before-clear")
            if (rebuild) { rows.clear(); cleared++ }
            boundary("after-clear")
            // Same IDs/mtime intentionally model the audit collision: without rebuild,
            // unchanged overlapping rows would retain the old provider's URI.
            run {
                repeat(2) { batch ->
                    boundary("before-batch-$batch")
                    repeat(if (batch == 0) 200 else 1) { i -> rows["i${batch * 200 + i}"] = "$root/i${batch * 200 + i}" }
                    boundary("after-batch-$batch")
                }
            }
            boundary("core-complete")
            return rows.size
        }
    }
    @Test fun `cold generation zero and legacy active only are untrusted`() = runBlocking {
        val store = Store(IndexPublication(activeUri = a))
        val index = IndexCoordinator(store)
        assertFalse(index.publication.value.readable)
        index.initialize()
        assertFalse(index.publication.value.readable)
        assertFalse(ColorProjection.empty(ColorTicket(a, 0, true)).isCurrent(ColorTicket(a, 0, true), a))
        var rebuild = false
        index.scan(a, { Unit }, { _, full -> rebuild = full })
        assertTrue(rebuild); assertTrue(index.publication.value.readable)
    }
    @Test fun `full URI authority collision and moved path both force rebuild`() = runBlocking {
        for (target in listOf(b, "content://provider.a/tree/primary%3AMoved.library")) {
            val store = Store(ready()); val index = IndexCoordinator(store); val rows = Rows(a)
            index.initialize()
            index.scan(target, { Unit }, { _, rebuild -> rows.build(target, rebuild) {} }, now = { 42 })
            assertEquals(1, rows.cleared)
            assertEquals(201, rows.rows.size)
            assertTrue(rows.rows.values.all { it.startsWith(target) })
            assertEquals(IndexPublication(target, target, 6, true, 42), store.readPublication())
            assertEquals(store.readPublication(), index.publication.value)
        }
    }
    @Test fun `verified same root keeps incremental path`() = runBlocking {
        val index = IndexCoordinator(Store(ready())); index.initialize()
        index.scan(a, { Unit }, { _, rebuild -> assertFalse(rebuild) })
        assertEquals(6L, index.publication.value.epoch)
    }
    @Test fun `unreadable partial same root before clear always rebuilds on restart`() = runBlocking {
        for (state in listOf(ready().copy(ready = false), ready().copy(root = b), IndexPublication(activeUri = a))) {
            val store = Store(state); val index = IndexCoordinator(store); val rows = Rows(b)
            index.initialize()
            index.scan(a, { Unit }, { _, full -> rows.build(a, full) {} })
            assertEquals(1, rows.cleared)
            assertTrue(rows.rows.values.all { it.startsWith(a) })
        }
    }
    @Test fun `invalid empty corrupt and cancelled preflight preserve publication and rows`() = runBlocking {
        for (failure in listOf(IOException("missing"), IllegalArgumentException("corrupt"), IllegalStateException("empty"), CancellationException("cancel"))) {
            val store = Store(ready()); val index = IndexCoordinator(store); index.initialize()
            val old = index.publication.value
            val result = runCatching { index.scan(a, { throw failure; Unit }, { _, _ -> fail("Room mutation") }) }
            assertSame(failure, result.exceptionOrNull())
            assertEquals(0, store.writes); assertEquals(old, store.readPublication()); assertEquals(old, index.publication.value)
        }
    }
    @Test fun `production mtime preflight rejects missing corrupt empty before any durable or Room changes`() = runBlocking {
        for (text in listOf("", "{", "{}", "{\"all\":100}", "{\"all\":0,\"x\":null}")) {
            val store = Store(ready()); val index = IndexCoordinator(store); index.initialize()
            val original = store.disk
            val failed = runCatching { index.scan(b, { validatedScanMtime(text) }, { _, _ -> fail("Room mutation") }) }
            assertTrue(text, failed.isFailure)
            assertEquals(original, store.disk); assertEquals(0, store.writes); assertTrue(index.publication.value.readable)
        }
        assertEquals(mapOf("x" to 100L), validatedScanMtime("{\"all\":1,\"x\":100,\"bad\":null}"))
    }
    @Test fun `durable read failure hides existing publication without touching rows`() = runBlocking {
        val store = Store(ready()); val index = IndexCoordinator(store); index.initialize()
        val old = index.publication.value
        store.beforeRead = { throw IOException("read") }
        assertTrue(runCatching { index.scan(b, { fail("preflight") }, { _, _ -> fail("Room") }) }.isFailure)
        assertEquals(old.copy(ready = false), index.publication.value); assertEquals(1, store.writes)
    }
    @Test fun `every mutation await failure or cancellation stays blocked and retry rebuilds`() = runBlocking {
        val boundaries = listOf("before-clear", "after-clear", "before-batch-0", "after-batch-0", "before-batch-1", "after-batch-1", "core-complete", "optional-snapshot", "scan-time", "final-commit")
        var cases = 0
        for (target in listOf(a, b)) for (point in boundaries) for (cancel in listOf(false, true)) {
            val store = Store(ready()); val index = IndexCoordinator(store); val rows = Rows(a)
            index.initialize()
            val old = index.publication.value
            fun inject(name: String) { if (point == name) { if (cancel) throw CancellationException(name) else throw IOException(name) } }
            store.beforeWrite = { if (it.ready) inject("final-commit") }
            val result = runCatching {
                index.scan(target, { Unit }, { _, rebuild ->
                    assertFalse(index.publication.value.readable)
                    assertFalse(store.readPublication().readable)
                    rows.build(target, rebuild) { inject(it); yield() }
                }, beforeCommit = { inject("optional-snapshot"); yield() }, now = { inject("scan-time"); 20 })
            }
            assertTrue("$target $point $cancel", result.isFailure)
            assertFalse(index.accepts(old)); assertFalse(index.publication.value.readable)
            assertFalse(store.readPublication().readable)
            val restarted = IndexCoordinator(store); restarted.initialize()
            assertFalse(restarted.publication.value.readable)
            store.beforeWrite = {}
            restarted.scan(target, { Unit }, { _, full ->
                assertTrue("restart $point", full)
                rows.build(target, full) {}
            }, now = { 30 })
            assertTrue(restarted.publication.value.readable)
            assertEquals(target, restarted.publication.value.activeUri)
            assertEquals(201, rows.rows.size)
            assertTrue(rows.rows.values.all { it.startsWith(target) })
            cases++
        }
        println("production coordinator mutation await failure/cancellation cases=$cases")
        assertEquals(40, cases)
    }
    @Test fun `block storage before and after commit interruptions never mutate Room`() = runBlocking {
        for (after in listOf(false, true)) for (cancel in listOf(false, true)) {
            val store = Store(ready()); val index = IndexCoordinator(store); index.initialize()
            val fail: suspend (IndexPublication) -> Unit = { if (!it.ready) { if (cancel) throw CancellationException("block") else throw IOException("block") } }
            if (after) store.afterWrite = fail else store.beforeWrite = fail
            val outcome = runCatching { index.scan(b, { Unit }, { _, _ -> org.junit.Assert.fail("mutation after failed durable block") }) }
            assertTrue(outcome.isFailure); assertFalse(index.publication.value.readable)
            if (after) assertFalse(store.readPublication().readable) else assertTrue(store.readPublication().readable)
            store.beforeWrite = {}; store.afterWrite = {}
            val restarted = IndexCoordinator(store); restarted.initialize()
            restarted.scan(b, { Unit }, { _, full -> assertTrue(full) })
            assertTrue(restarted.publication.value.readable)
        }
    }
    @Test fun `durable block invalidates existing ordinary and color page sources synchronously`() = runBlocking {
        val store = Store(ready()); val index = IndexCoordinator(store); index.initialize()
        val state = index.publication.value
        val ordinary = index.guard(object : PagingSource<Int, String>() {
            override suspend fun load(params: LoadParams<Int>): LoadResult<Int, String> = LoadResult.Page(listOf("old"), null, null)
            override fun getRefreshKey(state: PagingState<Int, String>): Int? = null
        }, state)
        val color = index.guard(ColorPagingSource(emptyList()), state)
        assertFalse(ordinary.invalid); assertFalse(color.invalid)
        index.scan(b, { Unit }, { _, _ ->
            assertTrue(ordinary.invalid); assertTrue(color.invalid)
            assertTrue(ordinary.load(PagingSource.LoadParams.Refresh(null, 60, true)) is PagingSource.LoadResult.Invalid)
            assertFalse(index.accepts(state))
        })
    }
    @Test fun `blocking clears cached selection synchronously and observer failure cannot regress final commit`() = runBlocking {
        val store = Store(ready()); val index = IndexCoordinator(store); index.initialize()
        var selection = setOf("old-id")
        val old = index.publication.value
        val remove = index.onBlocked { selection = emptySet() }
        val removeThrowing = index.onBlocked { throw IllegalStateException("observer") }
        index.scan(b, { Unit }, { _, _ ->
            assertTrue(selection.isEmpty())
            index.ifCurrent(old) { selection = setOf("stale click") }
            assertTrue(selection.isEmpty())
        })
        assertTrue(index.publication.value.readable)
        assertEquals(store.readPublication(), index.publication.value)
        remove(); removeThrowing()
    }
    @Test fun `late page response cannot publish after ABA`() = runBlocking {
        val store = Store(ready()); val index = IndexCoordinator(store); index.initialize()
        val old = index.publication.value
        val release = CompletableDeferred<Unit>()
        val guarded = index.guard(object : PagingSource<Int, String>() {
            override suspend fun load(params: LoadParams<Int>): LoadResult<Int, String> {
                release.await(); return LoadResult.Page(listOf("old-A"), null, null)
            }
            override fun getRefreshKey(state: PagingState<Int, String>): Int? = null
        }, old)
        val pending = async(start = CoroutineStart.UNDISPATCHED) { guarded.load(PagingSource.LoadParams.Refresh(null, 60, true)) }
        index.scan(b, { Unit }, { _, _ -> Unit }); index.scan(a, { Unit }, { _, _ -> Unit })
        release.complete(Unit)
        assertTrue(pending.await() is PagingSource.LoadResult.Invalid)
        assertFalse(index.accepts(old)); assertNotEquals(old.epoch, index.publication.value.epoch)
        assertFalse(ColorProjection.empty(old.ticket).isCurrent(index.publication.value.ticket, a))
    }
    @Test fun `selection reads are serialized with publication and recheck exact epoch`() = runBlocking {
        val store = Store(ready()); val index = IndexCoordinator(store); index.initialize()
        val expected = index.publication.value
        val readStarted = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var consumed = false
        val read = async { index.readCurrent(expected, read = {
            readStarted.complete(Unit); release.await(); listOf("$a/image")
        }, consume = { uris ->
            assertTrue(index.accepts(expected)); assertEquals(listOf("$a/image"), uris); consumed = true
        }) }
        readStarted.await()
        var mutationStarted = false
        val scan = async { index.scan(b, { Unit }, { _, _ -> mutationStarted = true }) }
        yield(); assertFalse(mutationStarted)
        release.complete(Unit); assertEquals(listOf("$a/image"), read.await()); assertTrue(consumed); scan.await()
        var queried = false
        assertNull(index.readCurrent(expected, read = { queried = true })); assertFalse(queried)
    }
    @Test fun `scan lock covers preflight final commit and next scan`() = runBlocking {
        val store = Store(ready()); val index = IndexCoordinator(store); index.initialize()
        val committing = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        store.beforeWrite = { if (it.ready && it.root == b) { committing.complete(Unit); release.await() } }
        val first = async { index.scan(b, { Unit }, { _, _ -> Unit }) }
        committing.await()
        var secondPreflight = false
        val second = async { index.scan(a, { secondPreflight = true }, { _, _ -> Unit }) }
        yield(); assertFalse(secondPreflight)
        release.complete(Unit); first.await(); second.await()
        assertEquals(a, index.publication.value.activeUri); assertEquals(7L, index.publication.value.epoch)
    }
    @Test fun `cancel during final atomic commit completes coherent success publication`() = runBlocking {
        val store = Store(ready()); val index = IndexCoordinator(store); index.initialize()
        val committing = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        store.beforeWrite = { if (it.ready) { committing.complete(Unit); release.await() } }
        var afterCommitObserved = false
        store.afterWrite = { if (it.ready) { assertEquals(b, it.activeUri); assertEquals(99L, it.scanTime); afterCommitObserved = true } }
        var returned = false
        val worker = launch { index.scan(b, { Unit }, { _, _ -> Unit }, now = { 99 }); returned = true }
        committing.await(); worker.cancel(); release.complete(Unit); worker.join()
        assertTrue(returned); assertTrue(afterCommitObserved)
        assertEquals(IndexPublication(b, b, 6, true, 99), store.readPublication())
        assertEquals(store.readPublication(), index.publication.value)
        val restarted = IndexCoordinator(store); restarted.initialize(); assertTrue(restarted.publication.value.readable)
    }
}
