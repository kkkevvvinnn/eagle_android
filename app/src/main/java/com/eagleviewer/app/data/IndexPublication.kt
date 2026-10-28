package com.eagleviewer.app.data

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** A single durable snapshot: legacy active/docId values never prove row ownership. */
@Serializable
data class IndexPublication(
    val activeUri: String = "",
    val root: String = "",
    val epoch: Long = 0,
    val ready: Boolean = false,
    val scanTime: Long = 0,
) {
    val readable: Boolean get() = ready && epoch > 0 && root.isNotEmpty() && root == activeUri
    val ticket: ColorTicket get() = ColorTicket(root, epoch, readable)
}

interface IndexPublicationStore {
    suspend fun readPublication(): IndexPublication
    suspend fun writePublication(value: IndexPublication)
}

/**
 * All index writers and selection reads share this lock. Preflight is read-only. A durable
 * building record precedes the first Room mutation; interrupted building always rebuilds.
 * Successful publication commits active/ready/time together; a concurrently revoked scan
 * attempts a subsequent durable block and stays hidden even if that write fails.
 */
class IndexCoordinator(private val store: IndexPublicationStore, private val pending: ReadonlyPendingGate = ReadonlyPendingGate { }) {
    @Volatile private var failedClosed = false
    private val mutex = Mutex()
    private var failureVersion = 0L
    @Synchronized private fun markFailure() { failedClosed = true; failureVersion = Math.addExact(failureVersion, 1) }
    @Synchronized private fun failureSerial() = failureVersion
    private var foregroundVersion = 0L
    private var foregroundPending = false
    private val live = MutableStateFlow(IndexPublication())
    val publication: StateFlow<IndexPublication> = live
    private val blockedListeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
    fun onBlocked(listener: () -> Unit): () -> Unit {
        blockedListeners.add(listener)
        return { blockedListeners.remove(listener) }
    }
    private val sources = java.util.concurrent.CopyOnWriteArraySet<androidx.paging.PagingSource<*, *>>()
    @Synchronized private fun publish(value: IndexPublication) {
        live.value = value
        if (!value.readable) blockedListeners.forEach { runCatching { it() } }
        sources.toList().forEach {
            sources.remove(it)
            runCatching { it.invalidate() }
        }
    }

    fun <T : Any> guard(source: androidx.paging.PagingSource<Int, T>, state: IndexPublication): androidx.paging.PagingSource<Int, T> {
        val guarded = IndexPagingSource(source) { accepts(state) }
        sources.add(guarded)
        guarded.registerInvalidatedCallback { sources.remove(guarded); source.invalidate() }
        if (!accepts(state)) guarded.invalidate()
        return guarded
    }

    private suspend fun checked(root: String, basis: IndexPublication = live.value) {
        try { pending.check(root); currentCoroutineContext().ensureActive() }
        catch (failure: Throwable) {
            markFailure()
            val blocked = basis.copy(ready = false)
            publish(blocked)
            // The await may have committed despite throwing; keep the in-memory latch either way.
            withContext(NonCancellable) { runCatching { store.writePublication(blocked) } }
            throw failure
        }
    }
    suspend fun initialize() = mutex.withLock {
        publish(live.value.copy(ready = false))
        val saved = try { store.readPublication() } catch (failure: Throwable) { markFailure(); throw failure }
        if (saved.readable && !failedClosed) {
            // Set identity while still blocked, so a failed check persists the right root/epoch.
            publish(saved.copy(ready = false))
            checked(saved.root)
            publishUnlessForegroundPending(saved)
        } else publish(saved.copy(ready = false))
    }

    /** The serial is changed under the same monitor that gates every ready publication. */
    @Synchronized fun suspendVisibility(): Long {
        foregroundVersion = Math.addExact(foregroundVersion, 1)
        foregroundPending = true
        publish(live.value.copy(ready = false))
        return foregroundVersion
    }
    @Synchronized private fun publishUnlessForegroundPending(value: IndexPublication) {
        publish(if (foregroundPending || failedClosed) value.copy(ready = false) else value)
    }
    @Synchronized private fun isLatestForeground(serial: Long) = serial == foregroundVersion
    @Synchronized private fun finishForeground(serial: Long, ready: IndexPublication?) {
        if (serial != foregroundVersion) return
        foregroundPending = false
        if (ready != null && !failedClosed) publish(ready)
    }
    @Synchronized private fun failForeground(serial: Long) {
        markFailure()
        if (serial == foregroundVersion) foregroundPending = false
        publish(live.value.copy(ready = false))
    }
    suspend fun foregroundCheck(serial: Long) {
        try {
            mutex.withLock {
                if (!isLatestForeground(serial)) return@withLock
                try {
                    val saved = store.readPublication()
                    if (saved.readable && !failedClosed) {
                        publish(saved.copy(ready = false))
                        checked(saved.root)
                        finishForeground(serial, saved)
                    } else finishForeground(serial, null)
                } catch (failure: Throwable) {
                    markFailure()
                    finishForeground(serial, null)
                    throw failure
                }
            }
        } catch (failure: Throwable) {
            // Even cancellation while waiting revokes a concurrent scan publication.
            failForeground(serial)
            throw failure
        }
    }

    fun accepts(value: IndexPublication): Boolean = value.readable && live.value == value

    /** Selection mutation and a synchronous block callback cannot cross each other. */
    @Synchronized fun ifCurrent(value: IndexPublication, action: () -> Unit) {
        if (accepts(value)) action()
    }

    @Synchronized private fun publishScanCompleted(value: IndexPublication, started: Long): Boolean {
        if (failureVersion == started) failedClosed = false
        publish(if (foregroundPending || failedClosed) value.copy(ready = false) else value)
        return failedClosed
    }

    suspend fun <P, R> scan(
        root: String,
        preflight: suspend () -> P,
        core: suspend (P, Boolean) -> R,
        beforeCommit: suspend (R) -> Unit = {},
        now: () -> Long = System::currentTimeMillis,
    ): R = mutex.withLock {
        val scanFailureSerial = failureSerial()
        val previous = try { store.readPublication() } catch (failure: Throwable) {
            markFailure()
            val blocked = live.value.copy(ready = false)
            publish(blocked)
            withContext(NonCancellable) { runCatching { store.writePublication(blocked) } }
            throw failure
        }
        checked(root, previous)
        val plan = preflight() // invalid/empty mtime cannot alter publication or Room
        val building = previous.copy(root = root, epoch = Math.addExact(previous.epoch, 1), ready = false)
        try {
            store.writePublication(building)
        } catch (failure: Throwable) {
            // A failed/cancelled storage await may already have committed building. No Room
            // work has begun, but hiding is the only conservative result until recovery.
            publish(building)
            throw failure
        }
        publish(building)
        val result = core(plan, failedClosed || !previous.readable || previous.root != root)
        beforeCommit(result) // optional snapshot failures must precede ready
        checked(root)
        currentCoroutineContext().ensureActive()
        val completed = building.copy(activeUri = root, ready = true, scanTime = now())
        // Cancellation cannot split the final successful store return from live publication.
        // No scanner/VM settings writes are allowed after this commit.
        withContext(NonCancellable) {
            store.writePublication(completed)
            if (publishScanCompleted(completed, scanFailureSerial)) {
                // A concurrent cancelled foreground revoked this scan; best-effort durable block.
                runCatching { store.writePublication(completed.copy(ready = false)) }
            }
        }
        result
    }

    suspend fun <T> readCurrent(
        expected: IndexPublication,
        read: suspend () -> T,
        consume: (T) -> Unit = {},
    ): T? = mutex.withLock {
        if (!accepts(expected)) return@withLock null
        val result = read()
        if (!accepts(expected)) return@withLock null
        consume(result)
        result
    }
}

/** An already-created Room or color source must stop serving cached/late pages at block. */
private class IndexPagingSource<T : Any>(
    private val source: androidx.paging.PagingSource<Int, T>,
    private val current: () -> Boolean,
) : androidx.paging.PagingSource<Int, T>() {
    init { source.registerInvalidatedCallback { invalidate() } }
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, T> {
        if (!current()) return LoadResult.Invalid()
        val result = source.load(params)
        return if (current()) result else LoadResult.Invalid()
    }
    override fun getRefreshKey(state: androidx.paging.PagingState<Int, T>): Int? = source.getRefreshKey(state)
}

/** Shared production preflight seam: an empty/partially synchronized root cannot delete
 * the existing index. Parsing preserves the established per-entry mtime tolerance. */
fun validatedScanMtime(text: String): Map<String, Long> = parseMtimeJson(text).also {
    require(it.isNotEmpty()) { "mtime.json 内容为空或格式异常，请等待同步完成后重试" }
}
