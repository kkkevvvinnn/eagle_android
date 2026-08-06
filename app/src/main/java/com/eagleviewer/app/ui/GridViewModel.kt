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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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

    @OptIn(ExperimentalCoroutinesApi::class)
    val pagingData: Flow<PagingData<ItemWithTags>> = filter
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

    val scanState = MutableStateFlow<ScanUiState>(ScanUiState.Idle)

    init {
        // 恢复上次保存的筛选状态与列数
        viewModelScope.launch {
            val savedFilter = container.settings.filterJson.first()
            if (savedFilter.isNotEmpty()) {
                runCatching {
                    filter.value = EagleItemMeta.json.decodeFromString<Filter>(savedFilter)
                }
            }
            columnCount.value = container.settings.columnCount.first().coerceIn(2, 4)
        }
    }

    fun updateFilter(transform: (Filter) -> Filter) {
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
        val clamped = count.coerceIn(2, 4)
        if (clamped == columnCount.value) return
        columnCount.value = clamped
        viewModelScope.launch { container.settings.setColumnCount(clamped) }
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

    /** 多选分享用：按选中 id 查原图 content URI。 */
    suspend fun selectedImageUris(): List<String> =
        container.db.itemDao().imageUrisFor(selection.value.toList())

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

    /** 设置页选定目录后调用：持久化 URI 并执行首次全量扫描。 */
    fun onLibraryPicked(uri: String) {
        viewModelScope.launch {
            container.settings.setLibraryUri(uri)
            container.settings.addLibrary(uri)
            runScan(uri)
        }
    }

    /** 设置页添加新图库并切换过去。 */
    fun addLibrary(uri: String) = onLibraryPicked(uri)

    /** 切换到已添加的另一个图库，并对新目录执行增量扫描重建索引。 */
    fun switchLibrary(uri: String) {
        if (uri == activeLibraryUri.value) return
        viewModelScope.launch {
            container.settings.setLibraryUri(uri)
            runScan(uri)
        }
    }

    fun removeLibrary(uri: String) {
        viewModelScope.launch { container.settings.removeLibrary(uri) }
    }

    suspend fun runScan(uri: String) {
        scanState.value = ScanUiState.Running(0, 0)
        try {
            val result = container.scanner.scan(uri) { p ->
                scanState.value = ScanUiState.Running(p.done, p.total)
            }
            container.settings.setLastScanTime(System.currentTimeMillis())
            scanState.value = ScanUiState.Done(
                "扫描完成：更新 ${result.scanned} 张，移除 ${result.deleted} 张，共 ${result.total} 张"
            )
        } catch (e: EagleScanner.ScannerException) {
            scanState.value = ScanUiState.Error(e.message ?: "扫描失败")
        } catch (e: Exception) {
            scanState.value = ScanUiState.Error("扫描失败：${e.message}")
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
