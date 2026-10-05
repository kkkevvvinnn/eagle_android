package com.eagleviewer.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.eagleviewer.app.AppContainer
import com.eagleviewer.app.data.EagleItemMeta
import com.eagleviewer.app.data.EagleScanner
import com.eagleviewer.app.data.Filter
import com.eagleviewer.app.data.db.ItemWithTags
import com.eagleviewer.app.data.db.TagCount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString

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

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val pagingData: Flow<PagingData<ItemWithTags>> = filter
        // 输入搜索词时防抖 300ms，避免每击键一次都重启 Pager；
        // 搜索词为空时的其他筛选变化立即生效
        .debounce { if (it.nameQuery.isNotEmpty()) 300L else 0L }
        .flatMapLatest { container.items.pagedItems(it) }
        .cachedIn(viewModelScope)

    val tagCounts: StateFlow<List<TagCount>> = container.items.tagCounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val itemCount: StateFlow<Int> = container.items.itemCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** 已添加的图库 URI 列表。 */
    val libraries: StateFlow<List<String>> = container.settings.libraries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 当前激活的图库 URI。 */
    val activeLibraryUri: StateFlow<String> = container.settings.libraryUri
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    /** 上次扫描时间（毫秒），0 = 从未扫描。 */
    val lastScanTime: StateFlow<Long> = container.settings.lastScanTime
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val scanState = MutableStateFlow<ScanUiState>(ScanUiState.Idle)

    /** 扫描串行化：并发扫描（如扫描中切换图库）会把两个图库的条目混进同一索引。 */
    private val scanMutex = Mutex()

    // 恢复竞态防护：DataStore 恢复值晚到时，不覆盖用户已做的修改
    private var filterTouched = false
    private var columnCountTouched = false

    init {
        // 恢复上次保存的筛选状态与列数
        viewModelScope.launch {
            val savedFilter = container.settings.filterJson.first()
            if (savedFilter.isNotEmpty() && !filterTouched) {
                runCatching {
                    filter.value = EagleItemMeta.json.decodeFromString<Filter>(savedFilter)
                }
            }
            val savedColumns = container.settings.columnCount.first().coerceIn(2, 4)
            if (!columnCountTouched) columnCount.value = savedColumns
        }
    }

    fun updateFilter(transform: (Filter) -> Filter) {
        filterTouched = true
        val next = transform(filter.value)
        filter.value = next
        // 筛选变化即持久化（写入量极小）
        viewModelScope.launch {
            runCatching {
                container.settings.setFilterJson(EagleItemMeta.json.encodeToString(next))
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

    fun toggleSelection(id: String) {
        selection.value = if (id in selection.value) selection.value - id
        else selection.value + id
    }

    fun enterSelection(id: String) {
        selection.value = setOf(id)
    }

    fun clearSelection() {
        selection.value = emptySet()
    }

    /** 多选分享用：按选中 id 分批查原图 content URI（防 SQL 变量上限）。 */
    suspend fun selectedImageUris(): List<String> =
        container.db.itemDao().imageUrisForChunked(selection.value.toList())

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
            if (runScan(uri)) container.settings.setLibraryUri(uri)
        }
    }

    /** 设置页添加新图库并切换过去。 */
    fun addLibrary(uri: String) = onLibraryPicked(uri)

    /** 切换到已添加的另一个图库，并对新目录执行增量扫描重建索引。 */
    fun switchLibrary(uri: String) {
        if (uri == activeLibraryUri.value) return
        viewModelScope.launch {
            // 扫描成功后才切换持久化的当前图库：失败时保持旧图库与旧索引一致，
            // 避免「标题是新库、内容是旧库」的错配状态
            if (runScan(uri)) container.settings.setLibraryUri(uri)
        }
    }

    fun removeLibrary(uri: String) {
        viewModelScope.launch { container.settings.removeLibrary(uri) }
    }

    /** 执行扫描，返回是否成功。并发调用经 [scanMutex] 串行化。 */
    suspend fun runScan(uri: String): Boolean = scanMutex.withLock {
        scanState.value = ScanUiState.Running(0, 0)
        try {
            val indexedRoot = container.settings.indexedRoot.first()
            val result = container.scanner.scan(uri, indexedRoot) { p ->
                scanState.value = ScanUiState.Running(p.done, p.total)
            }
            container.settings.setLastScanTime(System.currentTimeMillis())
            // 扫描成功才持久化索引所属根目录：失败时保持旧值，
            // 下次扫描仍会触发重建（与「成功才持久化图库 URI」同一策略）
            container.settings.setIndexedRoot(result.rootTreeDocId)
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
