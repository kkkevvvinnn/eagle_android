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
 * The final snapshot is the last suspend operation, committing active/ready/time together.
 */
class IndexCoordinator(private val store: IndexPublicationStore) {
    private val mutex = Mutex()
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

    suspend fun initialize() = mutex.withLock { publish(store.readPublication()) }

    fun accepts(value: IndexPublication): Boolean = value.readable && live.value == value

    /** Selection mutation and a synchronous block callback cannot cross each other. */
    @Synchronized fun ifCurrent(value: IndexPublication, action: () -> Unit) {
        if (accepts(value)) action()
    }

    suspend fun <P, R> scan(
        root: String,
        preflight: suspend () -> P,
        core: suspend (P, Boolean) -> R,
        beforeCommit: suspend (R) -> Unit = {},
        now: () -> Long = System::currentTimeMillis,
    ): R = mutex.withLock {
        val previous = store.readPublication()
        if (live.value != previous) publish(previous)
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
        val result = core(plan, !previous.readable || previous.root != root)
        beforeCommit(result) // optional snapshot failures must precede ready
        currentCoroutineContext().ensureActive()
        val completed = building.copy(activeUri = root, ready = true, scanTime = now())
        // Cancellation cannot split the final successful store return from live publication.
        // No scanner/VM settings writes are allowed after this commit.
        withContext(NonCancellable) {
            store.writePublication(completed)
            publish(completed)
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
