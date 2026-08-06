package com.eagleviewer.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.paging.compose.collectAsLazyPagingItems
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import com.eagleviewer.app.data.db.ItemWithTags

/**
 * 大图查看：HorizontalPager 左右切换（与网格的筛选结果集一致），
 * 双指缩放/平移/双击放大，单击切换元信息面板。
 */
@Composable
fun DetailScreen(
    vm: GridViewModel,
    startIndex: Int,
    onBack: () -> Unit,
    onFindSimilar: (Int) -> Unit,
) {
    val items = vm.pagingData.collectAsLazyPagingItems()
    var showInfo by remember { mutableStateOf(false) }

    BackHandler { onBack() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (items.itemCount == 0) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        } else {
            val safeStart = startIndex.coerceIn(0, items.itemCount - 1)
            val pagerState = rememberPagerState(initialPage = safeStart) { items.itemCount }

            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 1,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val data = items[page]
                if (data == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    DetailPage(data, onTap = { showInfo = !showInfo })
                }
            }

            if (showInfo) {
                // 顶部栏
                Row(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(Color(0x88000000))
                        .statusBarsPadding()
                        .padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = Color.White,
                        )
                    }
                    val current = items.peek(pagerState.currentPage)?.item
                    Text(
                        current?.name ?: "",
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                    )
                }

                // 底部元信息面板
                items.peek(pagerState.currentPage)?.let { data ->
                    InfoPanel(
                        data = data,
                        position = pagerState.currentPage + 1,
                        total = items.itemCount,
                        onFindSimilar = onFindSimilar,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
    }
}

/** 单页：原图加载完成前先显示缩略图。 */
@Composable
private fun DetailPage(data: ItemWithTags, onTap: () -> Unit) {
    val item = data.item
    Box(Modifier.fillMaxSize()) {
        // 底层缩略图（原图加载期间可见）
        AsyncImage(
            model = item.thumbUri,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
        ZoomableImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(item.imageUri)
                .crossfade(true)
                .build(),
            contentDescription = item.name,
            modifier = Modifier.fillMaxSize(),
            onTap = onTap,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InfoPanel(
    data: ItemWithTags,
    position: Int,
    total: Int,
    onFindSimilar: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val item = data.item
    Column(
        modifier
            .fillMaxWidth()
            .background(Color(0xCC10131A), RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text(item.name, color = Color.White, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.padding(2.dp))
        Text(
            buildString {
                append("$position / $total")
                append("   ${item.width}×${item.height}")
                append("   ${formatSize(item.size)}")
                append("   添加于 ${formatTime(item.btime)}")
            },
            color = Color(0xFFCCCCCC),
            style = MaterialTheme.typography.bodySmall,
        )
        if (item.star > 0) {
            Spacer(Modifier.padding(2.dp))
            Text(
                "★".repeat(item.star) + "☆".repeat(5 - item.star),
                color = Color(0xFFFFB300),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (data.tagNames.isNotEmpty()) {
            Spacer(Modifier.padding(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                data.tagNames.forEach { tag ->
                    AssistChip(onClick = {}, label = { Text(tag) })
                }
            }
        }
        if (data.palettes.isNotEmpty()) {
            Spacer(Modifier.padding(6.dp))
            Text(
                "主色板（点色卡找相似配色的图）",
                color = Color(0xFFCCCCCC),
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.padding(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                data.palettes.take(8).forEach { p ->
                    if (p.color.size >= 3) {
                        val rgb = (p.color[0].coerceIn(0, 255) shl 16) or
                            (p.color[1].coerceIn(0, 255) shl 8) or
                            p.color[2].coerceIn(0, 255)
                        Box(
                            Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(Color(p.color[0], p.color[1], p.color[2]))
                                .clickable { onFindSimilar(rgb) }
                        )
                    }
                }
            }
        }
        if (item.annotation.isNotBlank()) {
            Spacer(Modifier.padding(4.dp))
            Text(
                item.annotation,
                color = Color(0xFFCCCCCC),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
