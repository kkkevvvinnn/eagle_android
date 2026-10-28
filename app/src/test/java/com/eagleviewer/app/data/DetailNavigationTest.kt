package com.eagleviewer.app.data

import java.util.Base64
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/** Production ticket codec/admission; no Compose/NavHost or Android lifecycle execution. */
class DetailNavigationTest {
    private val a = "content://provider.a/tree/primary%3AA.library"
    private val b = "content://provider.b/tree/primary%3AA.library"
    private fun ready(root: String = a, epoch: Long = 41) = IndexPublication(root, root, epoch, true, 10)
    private class Store(initial: IndexPublication) : IndexPublicationStore {
        var disk = Json.encodeToString(initial)
        override suspend fun readPublication(): IndexPublication = Json.decodeFromString(disk)
        override suspend fun writePublication(value: IndexPublication) { disk = Json.encodeToString(value) }
    }
    private fun savedTicket(route: String): DetailNavigationTicket? = DetailNavigation.decode(route.split('/').getOrNull(2))

    @Test fun `full URI roundtrips losslessly through route token with Unicode and special characters`() {
        for (root in listOf(a, "content://提供方/tree/主目录:素材/路径 空格#片段?参数=100%&值=🦅", "content://x/tree/a%2Fb%252F/c#d?e=f")) {
            val ticket = DetailNavigation.capture(ready(root))!!
            val route = DetailNavigation.route(17, ticket)
            assertEquals(3, route.split('/').size)
            assertEquals("detail-v2/17", route.substringBeforeLast('/'))
            assertTrue(route.substringAfterLast('/').all { it.isLetterOrDigit() || it == '-' || it == '_' })
            val restored = savedTicket(route)
            assertEquals(root, restored!!.root); assertEquals(41L, restored.epoch)
            assertTrue(DetailNavigation.admits(restored, ready(root)))
        }
    }
    @Test fun `immediate click rotation and cold restore admit the saved verified identity`() = runBlocking {
        val store = Store(ready()); val first = IndexCoordinator(store); first.initialize()
        val clicked = DetailNavigation.capture(first.publication.value)!!
        val persistedRoute = DetailNavigation.route(17, clicked)
        assertTrue(DetailNavigation.admits(savedTicket(persistedRoute), first.publication.value))
        assertTrue(DetailNavigation.admits(savedTicket(persistedRoute), first.publication.value))
        val cold = IndexCoordinator(store)
        assertFalse(DetailNavigation.admits(savedTicket(persistedRoute), cold.publication.value))
        cold.initialize()
        assertTrue(DetailNavigation.admits(savedTicket(persistedRoute), cold.publication.value))
    }
    @Test fun `queued A click is refused if B completes before first detail composition including ABA`() = runBlocking {
        val store = Store(ready()); val index = IndexCoordinator(store); index.initialize()
        val preflight = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val switching = async { index.scan(b, { preflight.complete(Unit); release.await() }, { _, _ -> Unit }) }
        preflight.await()
        val queuedRoute = DetailNavigation.route(17, DetailNavigation.capture(index.publication.value)!!)
        release.complete(Unit); switching.await()
        val delayedEntry = savedTicket(queuedRoute)
        assertEquals(a, delayedEntry!!.root)
        assertFalse(DetailNavigation.admits(delayedEntry, index.publication.value))
        index.scan(a, { Unit }, { _, _ -> Unit })
        assertEquals(a, index.publication.value.root); assertEquals(43L, index.publication.value.epoch)
        assertFalse(DetailNavigation.admits(savedTicket(queuedRoute), index.publication.value))
        assertTrue(DetailNavigation.admits(DetailNavigation.capture(index.publication.value), index.publication.value))
    }
    @Test fun `same root rescan or blocked legacy and mismatched publications reject old ticket`() {
        val ticket = DetailNavigation.capture(ready())!!
        for (state in listOf(ready().copy(ready = false), ready(epoch = 42), ready(b),
            ready().copy(activeUri = b), IndexPublication(activeUri = a), IndexPublication())) {
            assertFalse(DetailNavigation.admits(ticket, state))
        }
        assertNull(DetailNavigation.capture(IndexPublication(activeUri = a)))
        assertNull(DetailNavigation.capture(ready().copy(ready = false)))
    }
    @Test fun `same identity temporarily unreadable waits and becomes ready again`() {
        val ticket = DetailNavigationTicket(a, 41)
        assertEquals(DetailDisposition.READY, DetailNavigation.disposition(ticket, ready()))
        assertEquals(DetailDisposition.WAIT, DetailNavigation.disposition(ticket, ready().copy(ready = false)))
        assertFalse(DetailNavigation.admits(ticket, ready().copy(ready = false)))
        assertEquals(DetailDisposition.READY, DetailNavigation.disposition(ticket, ready()))
    }
    @Test fun `invalid or changed identity exits even when unreadable`() {
        val ticket = DetailNavigationTicket(a, 41)
        val waiting = ready().copy(ready = false)
        for (state in listOf(waiting.copy(activeUri = b), waiting.copy(root = b),
            waiting.copy(epoch = 42), waiting.copy(epoch = 0), IndexPublication(activeUri = a), ready(b), ready(epoch = 42))) {
            assertEquals(DetailDisposition.EXIT, DetailNavigation.disposition(ticket, state))
        }
        for (bad in listOf(null, DetailNavigationTicket("", 41), DetailNavigationTicket(a, 0), DetailNavigationTicket(a, -1))) {
            assertEquals(DetailDisposition.EXIT, DetailNavigation.disposition(bad, waiting))
        }
    }
    @Test fun `legacy missing malformed and untrusted tickets fail closed`() {
        assertEquals("detail/{index}", DetailNavigation.LEGACY_ROUTE)
        assertFalse(DetailNavigation.admits(null, ready()))
        val jsonTokens = listOf("{}", "{\"root\":\"$a\",\"epoch\":0}", "{\"root\":\"$a\",\"epoch\":-1}",
            "{\"root\":\"\",\"epoch\":41}", "{\"root\":\"$a\",\"epoch\":\"41\"}",
            "{\"root\":\"$a\",\"epoch\":41,\"extra\":true}")
        val encodedBad = jsonTokens.map { Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray()) }
        val invalidUtf8 = Base64.getUrlEncoder().withoutPadding().encodeToString(byteArrayOf(0xC3.toByte(), 0x28))
        for (value in listOf(null, "", "%%%", "a", "a/b", "a?b", "a#b", "a+b", "a=b", "a".repeat(65537), invalidUtf8) + encodedBad) {
            assertNull(value?.take(80), DetailNavigation.decode(value))
        }
        assertFalse(DetailNavigation.admits(DetailNavigationTicket(a, 0), ready()))
        assertTrue(runCatching { DetailNavigation.route(-1, DetailNavigationTicket(a, 41)) }.isFailure)
    }
    @Test fun `initialization failure and cancellation leave retryable state and success releases navigation`() = runBlocking {
        val loading = NavigationIndexInitialization()
        assertEquals(NavigationIndexState.Loading, loading.state.value)
        loading.load { throw java.io.IOException("unavailable") }
        assertEquals(NavigationIndexState.Failed, loading.state.value)
        loading.load { Unit }
        assertEquals(NavigationIndexState.Ready, loading.state.value)
        assertTrue(runCatching { loading.load { throw CancellationException("cancel") } }.exceptionOrNull() is CancellationException)
        assertEquals(NavigationIndexState.Failed, loading.state.value)
    }
    @Test fun `cold restored saved route rejects interrupted index until successful rebuild changes epoch`() = runBlocking {
        val persistedRoute = DetailNavigation.route(17, DetailNavigation.capture(ready())!!)
        val store = Store(ready().copy(ready = false, epoch = 42))
        val cold = IndexCoordinator(store); cold.initialize()
        assertFalse(DetailNavigation.admits(savedTicket(persistedRoute), cold.publication.value))
        cold.scan(a, { Unit }, { _, rebuild -> assertTrue(rebuild) })
        assertFalse(DetailNavigation.admits(savedTicket(persistedRoute), cold.publication.value))
    }
}
