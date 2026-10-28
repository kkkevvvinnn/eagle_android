package com.eagleviewer.app.data

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ReadonlyPendingGateTest {
    private val root = "content://provider/tree/root"
    private fun ready() = IndexPublication(root, root, 4, true, 1)
    private class Store(var state: IndexPublication) : IndexPublicationStore {
        override suspend fun readPublication() = state
        override suspend fun writePublication(value: IndexPublication) { state = value }
    }
    @Test fun startupBlocksFailureAndCancellation() = runBlocking {
        for (failure in listOf(IllegalStateException(), CancellationException())) {
            val store = Store(ready()); val index = IndexCoordinator(store, ReadonlyPendingGate { throw failure })
            assertSame(failure, runCatching { index.initialize() }.exceptionOrNull())
            assertFalse(index.publication.value.readable); assertFalse(store.state.readable)
        }
    }
    @Test fun foregroundBlocksExistingAndOnlyExplicitScanRecovers() = runBlocking {
        var blocked = false; var calls = 0
        val store = Store(ready()); val index = IndexCoordinator(store, ReadonlyPendingGate { calls++; check(!blocked) })
        index.initialize(); val old = index.publication.value; var invalidated = 0
        index.onBlocked { invalidated++ }; blocked = true
        val observation = index.suspendVisibility(); runCatching { index.foregroundCheck(observation) }
        assertFalse(index.accepts(old)); assertTrue(invalidated > 0); assertFalse(store.state.readable)
        blocked = false; index.initialize(); assertFalse(index.publication.value.readable)
        var rebuild = false; index.scan(root, { Unit }, { _, full -> rebuild = full })
        assertTrue(rebuild); assertTrue(index.publication.value.readable); assertTrue(calls >= 4)
    }
    @Test fun pendingAtScanBoundariesCannotPublish() = runBlocking {
        for (failAt in 1..2) {
            var calls = 0; var built = false
            val store = Store(ready()); val index = IndexCoordinator(store, ReadonlyPendingGate { if (++calls == failAt) error("pending") })
            runCatching { index.scan(root, { Unit }, { _, _ -> built = true }) }
            assertFalse(index.publication.value.readable); assertFalse(store.state.readable)
            assertEquals(failAt == 2, built)
        }
    }
    @Test fun foregroundFailureAndCancellationRemainHidden() = runBlocking {
        for (failure in listOf(IllegalStateException("unreadable"), CancellationException("cancelled"))) {
            var failing = false
            val store = Store(ready()); val index = IndexCoordinator(store, ReadonlyPendingGate { if (failing) throw failure })
            index.initialize(); failing = true
            val observation = index.suspendVisibility()
            assertSame(failure, runCatching { index.foregroundCheck(observation) }.exceptionOrNull())
            assertFalse(index.publication.value.readable); assertFalse(store.state.readable)
            failing = false; index.initialize(); assertFalse(index.publication.value.readable)
            index.scan(root, { Unit }, { _, rebuild -> assertTrue(rebuild) })
            assertTrue(index.publication.value.readable)
        }
    }
    @Test fun queuedForegroundKeepsScanHiddenUntilItsCheckCompletes() = runBlocking {
        val inCore = CompletableDeferred<Unit>(); val releaseCore = CompletableDeferred<Unit>()
        val inForeground = CompletableDeferred<Unit>(); val releaseForeground = CompletableDeferred<Unit>()
        var calls = 0
        val store = Store(ready()); val index = IndexCoordinator(store, ReadonlyPendingGate {
            calls++
            if (calls == 4) { inForeground.complete(Unit); releaseForeground.await() }
        })
        index.initialize()
        val scan = async(start = CoroutineStart.UNDISPATCHED) {
            index.scan(root, { Unit }, { _, _ -> inCore.complete(Unit); releaseCore.await() })
        }
        inCore.await()
        val observation = index.suspendVisibility()
        val foreground = async(start = CoroutineStart.UNDISPATCHED) { index.foregroundCheck(observation) }
        assertFalse(index.publication.value.readable)
        releaseCore.complete(Unit); scan.await(); inForeground.await()
        assertTrue(store.state.readable) // persisted scan, awaiting foreground observation
        assertFalse(index.publication.value.readable)
        releaseForeground.complete(Unit); foreground.await()
        assertTrue(index.publication.value.readable)
    }
    @Test fun cancelledQueuedForegroundCannotBeUndoneByEarlierScan() = runBlocking {
        val inCore = CompletableDeferred<Unit>(); val releaseCore = CompletableDeferred<Unit>()
        val store = Store(ready()); val index = IndexCoordinator(store, ReadonlyPendingGate { })
        index.initialize()
        val scan = async(start = CoroutineStart.UNDISPATCHED) {
            index.scan(root, { Unit }, { _, _ -> inCore.complete(Unit); releaseCore.await() })
        }
        inCore.await()
        val observation = index.suspendVisibility()
        val foreground = launch(start = CoroutineStart.UNDISPATCHED) { index.foregroundCheck(observation) }
        foreground.cancelAndJoin()
        releaseCore.complete(Unit); scan.await()
        assertFalse(index.publication.value.readable); assertFalse(store.state.readable)
        index.initialize(); assertFalse(index.publication.value.readable)
        index.scan(root, { Unit }, { _, rebuild -> assertTrue(rebuild) })
        assertTrue(index.publication.value.readable)
    }
    @Test fun namespaceMustBeUnderTrustedFilesDirectory() {
        val data = Files.createTempDirectory("private-data")
        val other = Files.createTempDirectory("private-outside")
        try {
            val files = Files.createDirectory(data.resolve("files"))
            val probe = PrivatePendingProbe(data.toFile(), files.toFile())
            val wrong = Files.createDirectory(data.resolve("manual-private-v1"))
            Files.write(wrong.resolve(".inflight"), byteArrayOf(1)); probe.check()
            val actual = Files.createDirectory(files.resolve("manual-private-v1"))
            for (entry in listOf(".inflight", "abc.plan")) {
                val item = Files.write(actual.resolve(entry), byteArrayOf(1))
                assertTrue(runCatching { probe.check() }.isFailure); Files.delete(item)
            }
            assertTrue(runCatching { PrivatePendingProbe(data.toFile(), other.toFile()).check() }.isFailure)
            Files.delete(actual)
            Files.createSymbolicLink(actual, other)
            assertTrue(runCatching { probe.check() }.isFailure)
        } finally { data.toFile().deleteRecursively(); other.toFile().deleteRecursively() }
    }
    @Test fun rootNamesDetectAsciiCaseButAllowArchive() {
        assertTrue(isPortablePendingName("KEVIN-WRITEBACK-V1.JSON"))
        assertTrue(isPortablePendingName("Kevin-Writeback-V1"))
        assertFalse(isPortablePendingName("kevin-writeback-archive-v1"))
    }
    @Test fun privateNamespaceIsBoundedAndFailClosed() {
        val base = Files.createTempDirectory("private-gate")
        try {
            val files = Files.createDirectory(base.resolve("files"))
            val probe = PrivatePendingProbe(base.toFile(), files.toFile()); probe.check()
            val dir = Files.createDirectory(files.resolve("manual-private-v1")); probe.check()
            Files.write(dir.resolve(".lock"), byteArrayOf()); probe.check()
            for (name in listOf(".inflight", ".tmp", "garbage.plan", "orphan.progress")) {
                val path = Files.write(dir.resolve(name), byteArrayOf(0))
                assertTrue(runCatching { probe.check() }.isFailure); Files.delete(path)
            }
        } finally { base.toFile().deleteRecursively() }
    }
}
