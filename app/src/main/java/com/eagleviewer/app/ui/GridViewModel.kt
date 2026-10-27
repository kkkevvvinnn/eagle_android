package com.eagleviewer.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.paging.PagingData
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.sqlite.db.SupportSQLiteQuery
import com.eagleviewer.app.data.ColorProjection
import com.eagleviewer.app.data.ColorPagingSource
import com.eagleviewer.app.data.IndexPublication
import com.eagleviewer.app.data.PaletteRequestGate
import com.eagleviewer.app.data.PaletteProgress
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.emitAll
import androidx.paging.cachedIn
import com.eagleviewer.app.AppContainer
import com.eagleviewer.app.data.EagleScanner
import com.eagleviewer.app.data.Filter
import com.eagleviewer.app.data.FilterPersistence
import com.eagleviewer.app.data.TagCatalog
import com.eagleviewer.app.data.TagCatalogParser
import com.eagleviewer.app.data.db.ItemWithTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 扫描进度/结果状态，设置页与网格页共用。 */
sealed interface ScanUiState {
    data object Idle : ScanUiState
    data class Running(val done: Int, val total: Int) : ScanUiState
    data class Done(val message: String) : ScanUiState
    data class Error(val message: String) : ScanUiState
}

class GridViewModel(private val container: AppContainer) : ViewModel() {

    val filter = MutableStateFlow(Filter())

    /** 网格列数（2–4），捏合手势调节并持久化。 */
    val columnCount = MutableStateFlow(2)

    /** 多选模式下的选中 id 集合；空集合 = 非多选模式。 */
    val selection = MutableStateFlow<Set<String>>(emptySet())

    val publication = container.index.publication
    // AppContainer outlives a ViewModel; generation alone may restart at zero in a new session.
    private val paletteSession=java.util.UUID.randomUUID().toString()
    private val paletteRequests=PaletteRequestGate()
    private val _paletteProgress=MutableStateFlow<PaletteProgress?>(null)
    val paletteProgress:StateFlow<PaletteProgress?> = _paletteProgress
    private sealed interface Prepared {
        val publication: IndexPublication
        data class Blocked(override val publication: IndexPublication): Prepared
        data class Sql(override val publication: IndexPublication, val query: SupportSQLiteQuery): Prepared
        data class Color(override val publication: IndexPublication, val projection: ColorProjection): Prepared
    }

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    private val preparedQuery = combine(filter, publication) { f, state -> f to state }
        .flatMapLatest { (f, state) ->
            paletteRequests.invalidate()
            _paletteProgress.value = null
            flow<Prepared> {
                if (!container.index.accepts(state)) {
                    emit(Prepared.Blocked(state))
                    return@flow
                }
                val condition = f.effectiveColor
                if (condition != null) {
                    val waiting = paletteRequests.begin(state.ticket)
                    _paletteProgress.value = PaletteProgress(waiting, 0, 0)
                    emit(Prepared.Blocked(state))
                }
                if (f.nameQuery.isNotEmpty()) delay(300)
                if (!container.index.accepts(state)) return@flow
                if (condition == null) emit(Prepared.Sql(state, container.items.prepareQuery(f)))
                else emitAll(container.items.observeItems(container.items.prepareQuery(f.clearColor()))
                    .mapLatest { rows ->
                        val request = paletteRequests.begin(state.ticket)
                        fun current() = paletteRequests.accepts(request) && filter.value == f && container.index.accepts(state)
                        try {
                            val projection = withContext(Dispatchers.Default) {
                                ColorProjection.create(state.ticket, rows, condition,
                                    palette = { container.localPalettes.resolve(state.ticket, it, paletteSession) },
                                    progress = { done, total ->
                                        if (current()) _paletteProgress.value = PaletteProgress(request, done, total)
                                    })
                            }
                            currentCoroutineContext().ensureActive()
                            if (current()) Prepared.Color(state, projection) else Prepared.Blocked(state)
                        } finally {
                            if (paletteRequests.accepts(request)) _paletteProgress.value = null
                        }
                    })
            }
        }.shareIn(viewModelScope, SharingStarted.Eagerly, replay = 1)

    /** Each durable epoch owns its own shared cache. An old cached PagingData cannot be
     * recollected under a new ready epoch, even before flatMapLatest handles the block. */
    data class PagingSession(val publication: IndexPublication, val data: Flow<PagingData<ItemWithTags>>)

    @OptIn(ExperimentalCoroutinesApi::class)
    val pagingSession: StateFlow<PagingSession> = publication.transformLatest { state -> coroutineScope {
        val data = combine(preparedQuery, publication) { prepared, live ->
            if (live == state && prepared.publication == state) prepared else Prepared.Blocked(state)
        }.flatMapLatest { prepared ->
            if (!container.index.accepts(state)) flowOf(PagingData.empty())
            else when (prepared) {
                is Prepared.Blocked -> flowOf(PagingData.empty())
                is Prepared.Sql -> container.items.pagedItems(prepared.query, container.index, state)
                is Prepared.Color -> Pager(PagingConfig(pageSize=60, initialLoadSize=120, enablePlaceholders=true)) {
                    container.index.guard(ColorPagingSource(prepared.projection.rows), state)
                }.flow
            }
        }.cachedIn(this)
        emit(PagingSession(state, data))
        awaitCancellation() // cancelling an epoch also releases its shared Paging cache
    } }.stateIn(viewModelScope, SharingStarted.Eagerly, PagingSession(IndexPublication(), flowOf(PagingData.empty())))

    data class PublishedValue<T>(val publication: IndexPublication, val value: T)

    @OptIn(ExperimentalCoroutinesApi::class)
    val tagCatalog: StateFlow<PublishedValue<TagCatalog>> = publication.flatMapLatest { state ->
        if (!state.readable) flowOf(PublishedValue(state, TagCatalog()))
        else combine(container.settings.tagSnapshot, container.items.tagCounts(),
            preparedQuery.filter { it.publication == state }.flatMapLatest { prepared ->
                when (prepared) {
                    is Prepared.Blocked -> flowOf(emptyList())
                    is Prepared.Sql -> container.items.currentTagCounts(prepared.query)
                    is Prepared.Color -> flowOf(prepared.projection.counts)
                }
            }) { snapshot, global, current ->
            PublishedValue(state, if (!container.index.accepts(state)) TagCatalog()
            else TagCatalogParser.catalog(snapshot.forRoot(state.root), global.associate { it.tag to it.cnt },
                current.associate { it.tag to it.cnt }))
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, PublishedValue(IndexPublication(), TagCatalog()))

    @OptIn(ExperimentalCoroutinesApi::class)
    val itemCount: StateFlow<PublishedValue<Int>> = publication.flatMapLatest { state ->
        if (!state.readable) flowOf(PublishedValue(state, 0))
        else container.items.itemCount().map { PublishedValue(state, if (container.index.accepts(state)) it else 0) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PublishedValue(IndexPublication(), 0))

    /** 已添加的图库 URI 列表。 */
    val libraries: StateFlow<List<String>> = container.settings.libraries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val scanState = MutableStateFlow<ScanUiState>(ScanUiState.Idle)

    // Serialize UI status as well; the app-scoped coordinator separately locks all index/publication/share work.
    private val scanMutex = Mutex()
    @Volatile private var selectionPublication: IndexPublication? = null
    private val removeBlockListener = container.index.onBlocked {
        clearSelection()
        paletteRequests.invalidate()
        _paletteProgress.value = null
    }

    override fun onCleared() {
        removeBlockListener()
        super.onCleared()
    }

    // 恢复竞态防护：DataStore 恢复值晚到时，不覆盖用户已做的修改
    private var filterTouched = false
    private var columnCountTouched = false

    init {
        // 恢复上次保存的筛选状态与列数
        viewModelScope.launch {
            val savedFilter = container.settings.filterJson.first()
            if (savedFilter.isNotEmpty() && !filterTouched) {
                runCatching {
                    filter.value = FilterPersistence.decode(savedFilter)
                }
            }
            val savedColumns = container.settings.columnCount.first().coerceIn(2, 4)
            if (!columnCountTouched) columnCount.value = savedColumns
        }
    }

    fun updateFilter(transform: (Filter) -> Filter) {
        filterTouched = true
        val next = transform(filter.value)
        paletteRequests.invalidate()
        _paletteProgress.value=null
        filter.value = next
        // 筛选变化即持久化（写入量极小）
        viewModelScope.launch {
            runCatching {
                container.settings.setFilterJson(FilterPersistence.encode(next))
            }
        }
    }

    fun setColumnCount(count: Int) {
        columnCountTouched = true
        val clamped = count.coerceIn(2, 4)
        if (clamped == columnCount.value) return
        columnCount.value = clamped
        viewModelScope.launch { container.settings.setColumnCount(clamped) }
    }

    /** 主题模式：0 = 跟随系统，1 = 深色，2 = 浅色。 */
    val themeMode: StateFlow<Int> = container.settings.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun setThemeMode(mode: Int) {
        viewModelScope.launch { container.settings.setThemeMode(mode) }
    }

    fun toggleSelection(id: String, state: IndexPublication) = container.index.ifCurrent(state) {
        if (selectionPublication != state) clearSelection()
        selectionPublication = state
        selection.value = if (id in selection.value) selection.value - id
        else selection.value + id
    }

    fun enterSelection(id: String, state: IndexPublication) = container.index.ifCurrent(state) {
        selectionPublication = state
        selection.value = setOf(id)
    }

    fun clearSelection() {
        selectionPublication = null
        selection.value = emptySet()
    }

    fun selectionIsCurrent(): Boolean = selectionPublication?.let(container.index::accepts) == true

    /** Query and hand off the verified URIs without letting a scan start between the
     * suspend DAO read and the synchronous Android share action. */
    suspend fun shareSelectedImages(share: (List<String>) -> Unit) {
        val expected = selectionPublication ?: return
        val ids = selection.value.toList()
        container.index.readCurrent(expected, { container.db.itemDao().imageUrisForChunked(ids) }) { uris ->
            if (uris.isNotEmpty()) share(uris)
        }
    }

    fun rescan() {
        viewModelScope.launch {
            val uri = container.settings.libraryUri.first()
            if (uri.isEmpty()) {
                scanState.value = ScanUiState.Error("尚未设置图库目录")
                return@launch
            }
            runScan(uri)
        }
    }

    /** 设置页选定目录后调用：加入图库列表并执行首次全量扫描，扫描成功后才持久化为当前图库。 */
    fun onLibraryPicked(uri: String) {
        viewModelScope.launch {
            container.settings.addLibrary(uri)
            runScan(uri)
        }
    }

    /** 设置页添加新图库并切换过去。 */
    fun addLibrary(uri: String) = onLibraryPicked(uri)

    /** 切换到已添加的另一个图库，并对新目录执行增量扫描重建索引。 */
    fun switchLibrary(uri: String) {
        if (uri == publication.value.activeUri && publication.value.readable) return
        viewModelScope.launch {
            // Ready/active/time are committed together by the scanner coordinator.
            // A failed mutation stays blocked until an explicit successful rescan.
            runScan(uri)
        }
    }

    fun removeLibrary(uri: String) {
        viewModelScope.launch { container.settings.removeLibrary(uri) }
    }

    /** 执行扫描，返回是否成功。并发调用经 [scanMutex] 串行化。 */
    suspend fun runScan(uri: String): Boolean = scanMutex.withLock {
        scanState.value = ScanUiState.Running(0, 0)
        try {
            val result = container.scanner.scan(uri) { p ->
                scanState.value = ScanUiState.Running(p.done, p.total)
            }
            val base = "扫描完成：更新 ${result.scanned} 张，移除 ${result.deleted} 张，共 ${result.total} 张"
            scanState.value = ScanUiState.Done(
                if (result.missing > 0)
                    "$base；${result.missing} 张图片文件未同步到本机，同步补全后重新扫描即可"
                else base
            )
            true
        } catch (e: CancellationException) {
            // 结构化并发：取消不是扫描失败
            scanState.value = ScanUiState.Idle
            throw e
        } catch (e: EagleScanner.ScannerException) {
            scanState.value = ScanUiState.Error(e.message ?: "扫描失败")
            false
        } catch (e: Exception) {
            scanState.value = ScanUiState.Error("扫描失败：${e.message}")
            false
        }
    }

    fun clearScanState() {
        scanState.value = ScanUiState.Idle
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { GridViewModel(container) }
        }
    }
}
