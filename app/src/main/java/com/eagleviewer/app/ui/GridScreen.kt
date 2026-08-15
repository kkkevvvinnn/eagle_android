package com.eagleviewer.app.ui

import android.content.ClipData
import android.content.Intent
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Precision
import com.eagleviewer.app.data.db.ItemWithTags
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GridScreen(
    vm: GridViewModel,
    onOpenDetail: (Int) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val items = vm.pagingData.collectAsLazyPagingItems()
    val filter by vm.filter.collectAsState()
    val tagCounts by vm.tagCounts.collectAsState()
    val itemCount by vm.itemCount.collectAsState()
    val scanState by vm.scanState.collectAsState()
    val columnCount by vm.columnCount.collectAsState()
    val activeLibraryUri by vm.activeLibraryUri.collectAsState()
    val selection by vm.selection.collectAsState()
    val selectionMode = selection.isNotEmpty()
    var searchActive by remember { mutableStateOf(filter.nameQuery.isNotBlank()) }
    // 筛选状态从 DataStore 异步恢复：恢复出非空搜索词时同步展开搜索栏
    LaunchedEffect(filter.nameQuery) {
        if (filter.nameQuery.isNotBlank()) searchActive = true
    }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(scanState) {
        when (val s = scanState) {
            is ScanUiState.Done -> {
                snackbar.showSnackbar(s.message)
                vm.clearScanState()
            }
            is ScanUiState.Error -> {
                snackbar.showSnackbar(s.message)
                vm.clearScanState()
            }
            else -> Unit
        }
    }

    Scaffold(
        topBar = {
            when {
                selectionMode -> SelectionTopBar(
                    count = selection.size,
                    onClose = { vm.clearSelection() },
                    onShare = {
                        scope.launch {
                            val uris = runCatching { vm.selectedImageUris() }.getOrElse {
                                snackbar.showSnackbar("读取选中图片失败：${it.message}")
                                return@launch
                            }
                            if (uris.isEmpty()) return@launch
                            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                type = "image/*"
                                putParcelableArrayListExtra(
                                    Intent.EXTRA_STREAM,
                                    ArrayList(uris.map { it.toUri() }),
                                )
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                clipData = ClipData.newUri(
                                    context.contentResolver,
                                    "images",
                                    uris.first().toUri(),
                                )
                            }
                            runCatching {
                                context.startActivity(Intent.createChooser(intent, "分享图片"))
                            }.onFailure {
                                // 选中过多时 intent 可能超出 binder 事务上限
                                snackbar.showSnackbar("分享失败：${it.message ?: "选中图片过多"}")
                            }.onSuccess { vm.clearSelection() }
                        }
                    },
                )
                searchActive -> SearchTopBar(
                    query = filter.nameQuery,
                    onQueryChange = { q -> vm.updateFilter { it.copy(nameQuery = q) } },
                    onClose = {
                        searchActive = false
                        vm.updateFilter { it.copy(nameQuery = "") }
                    },
                )
                else -> TopAppBar(
                    title = {
                        // 有筛选条件时显示「筛选结果数 / 总数」，否则显示当前图库目录名
                        Text(
                            if (filter.isActive)
                                "筛选 ${items.itemCount} / $itemCount"
                            else libraryDisplayName(activeLibraryUri).ifBlank { "Eagle 图库" },
                            maxLines = 1,
                        )
                    },
                    actions = {
                        IconButton(onClick = { searchActive = true }) {
                            Icon(Icons.Default.Search, contentDescription = "搜索")
                        }
                        if (scanState is ScanUiState.Running) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .padding(12.dp)
                                    .size(24.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            IconButton(onClick = { vm.rescan() }) {
                                Icon(Icons.Default.Refresh, contentDescription = "重新扫描")
                            }
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "设置")
                        }
                    },
                )
            }
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            FilterBar(
                filter = filter,
                tagCounts = tagCounts,
                onFilterChange = vm::updateFilter,
            )

            when {
                // 仅在没有任何数据时显示转圈；已有数据时刷新在后台进行，
                // 避免筛选变化时网格与转圈来回切换造成的「双闪」
                items.itemCount == 0 && items.loadState.refresh is LoadState.Loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                items.itemCount == 0 -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (itemCount == 0) "索引为空，点右上角重新扫描"
                            else "没有符合筛选条件的图片"
                        )
                    }
                }
                else -> {
                    // 滚动位置记忆：从详情页返回时保持原位
                    val gridState = rememberSaveable(
                        saver = LazyStaggeredGridState.Saver,
                    ) { LazyStaggeredGridState() }
                    // 捏合过程中的整体缩放预览系数（1 = 无预览）
                    val pinchScale = remember { androidx.compose.animation.core.Animatable(1f) }
                    // 列数切换重排时整体淡入：遮盖无法参与位移动画的新组合项的「突然出现」
                    val gridAlpha = remember { androidx.compose.animation.core.Animatable(1f) }
                    LaunchedEffect(columnCount) {
                        gridAlpha.snapTo(0.3f)
                        gridAlpha.animateTo(1f, tween(180))
                    }

                    // 缩略图预取：瀑布流组件在本 Compose 版本无视口外预取 API，
                    // 手动对可见末位之后的 12 项提前 enqueue Coil 请求，把解码
                    // 挪到滚动到达之前完成（高刷屏下"滚到才解码"必掉帧）。
                    // 用 peek 访问（不触发分页加载）；prefetched 集合去重防重复 enqueue。
                    val prefetched = remember { mutableSetOf<String>() }
                    LaunchedEffect(Unit) {
                        snapshotFlow {
                            gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                        }.collect { lastVisible ->
                            val end = minOf(lastVisible + 12, items.itemCount - 1)
                            for (i in (lastVisible + 1)..end) {
                                val data = items.peek(i) ?: continue
                                if (prefetched.add(data.item.id)) {
                                    if (prefetched.size > 2000) prefetched.clear()
                                    context.imageLoader.enqueue(
                                        ImageRequest.Builder(context)
                                            .data(data.item.thumbUri ?: data.item.imageUri)
                                            .size(360)
                                            .precision(Precision.INEXACT)
                                            .build(),
                                    )
                                }
                            }
                        }
                    }

                    PullToRefreshBox(
                        isRefreshing = scanState is ScanUiState.Running,
                        onRefresh = { vm.rescan() },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                    LazyVerticalStaggeredGrid(
                        state = gridState,
                        // 固定列数：捏合直接决定 2/3/4 列。
                        // 之前用 Adaptive(100dp) 时竖屏手机宽度不足 400dp，
                        // 选 4 列实际只显示 3 列
                        columns = StaggeredGridCells.Fixed(columnCount),
                        modifier = Modifier
                            .fillMaxSize()
                            // 捏合预览：手势过程中整体连续缩放，松手后回弹
                            .graphicsLayer {
                                scaleX = pinchScale.value
                                scaleY = pinchScale.value
                                alpha = gridAlpha.value
                            }
                            // 双指捏合调节列数：张开=更大的图（列数-1），收拢=更多的图（列数+1）
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    var accumulated = 1f
                                    var prevPointers = 1
                                    do {
                                        val event = awaitPointerEvent()
                                        val pointers = event.changes.size
                                        if (pointers >= 2) {
                                            if (pointers != prevPointers) {
                                                // 指针数变化的那一帧 calculateZoom 会失真
                                                // （新手指的 previousPosition == position，
                                                // 旧距离趋近 0 产生 zoom 尖峰），跳过并重置
                                                accumulated = 1f
                                            } else {
                                                accumulated = (accumulated * event.calculateZoom())
                                                    .coerceIn(0.5f, 2f)
                                                // 手势期间即时更新预览缩放（收窄幅度，避免过于激烈）
                                                scope.launch {
                                                    pinchScale.snapTo(accumulated.coerceIn(0.7f, 1.4f))
                                                }
                                            }
                                            event.changes.forEach { it.consume() }
                                        }
                                        prevPointers = pointers
                                    } while (event.changes.any { it.pressed })
                                    // 松手：提交列数变化，预览系数动画回到 1
                                    when {
                                        accumulated > 1.3f -> vm.setColumnCount(columnCount - 1)
                                        accumulated < 0.75f -> vm.setColumnCount(columnCount + 1)
                                    }
                                    scope.launch { pinchScale.animateTo(1f, tween(200)) }
                                }
                            },
                        contentPadding = PaddingValues(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalItemSpacing = 8.dp,
                    ) {
                        items(
                            count = items.itemCount,
                            key = items.itemKey { it.item.id },
                        ) { index ->
                            val data = items[index]
                            if (data != null) {
                                GridCell(
                                    data = data,
                                    selectionMode = selectionMode,
                                    selected = data.item.id in selection,
                                    onClick = {
                                        if (selectionMode) vm.toggleSelection(data.item.id)
                                        else onOpenDetail(index)
                                    },
                                    onLongClick = {
                                        // 多选模式下长按另一张图应追加/取消，
                                        // 而不是清空整个选择集重来
                                        if (selectionMode) vm.toggleSelection(data.item.id)
                                        else vm.enterSelection(data.item.id)
                                    },
                                )
                            } else {
                                // 占位符：未知尺寸，用方形占位
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                )
                            }
                        }
                    }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(count: Int, onClose: () -> Unit, onShare: () -> Unit) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "取消选择")
            }
        },
        title = { Text("已选 $count 张") },
        actions = {
            IconButton(onClick = onShare) {
                Icon(Icons.Default.Share, contentDescription = "分享")
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchTopBar(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "关闭搜索")
            }
        },
        title = {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                placeholder = { Text("按文件名搜索") },
                singleLine = true,
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = "清空")
                        }
                    }
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridCell(
    data: ItemWithTags,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val item = data.item
    val ratio = if (item.width > 0 && item.height > 0) {
        (item.width.toFloat() / item.height).coerceIn(0.4f, 2.5f)
    } else 1f
    val placeholderColor = item.paletteColor?.let { Color(it) }
        ?: MaterialTheme.colorScheme.surfaceVariant
    // 缩略图缺失/损坏时降级加载原图
    var useOriginal by remember(item.id) { mutableStateOf(false) }
    // 网格滚动性能：不做 crossfade 动画，解码尺寸限定在缩略图级别；
    // INEXACT 允许直接复用内存缓存中更小的位图，减少重复解码
    val context = LocalContext.current
    val imageRequest = remember(item.id, useOriginal) {
        ImageRequest.Builder(context)
            .data(if (useOriginal) item.imageUri else item.thumbUri ?: item.imageUri)
            .size(360)
            .precision(Precision.INEXACT)
            .crossfade(false)
            .build()
    }

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(12.dp))
            .background(placeholderColor)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        AsyncImage(
            model = imageRequest,
            contentDescription = item.name,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            onError = {
                if (!useOriginal) useOriginal = true
            },
        )
        if (item.star > 0 && !selectionMode) {
            Text(
                text = "★".repeat(item.star),
                color = Color(0xFFFFB74D),
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .background(Color(0x99000000), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        if (selectionMode) {
            // 选中态遮罩 + 勾选标
            if (selected) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0x66FFB74D))
                )
            }
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = if (selected) "已选中" else "未选中",
                tint = if (selected) Color(0xFFFFB74D) else Color(0x99FFFFFF),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(22.dp)
                    .background(Color.Black.copy(alpha = 0.3f), CircleShape)
                    .padding(1.dp),
            )
        }
    }
}
