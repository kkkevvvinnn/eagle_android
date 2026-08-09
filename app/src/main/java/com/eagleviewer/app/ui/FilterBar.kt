package com.eagleviewer.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.eagleviewer.app.data.Filter
import com.eagleviewer.app.data.Sort
import com.eagleviewer.app.data.db.TagCount

/**
 * 网格页顶部的筛选栏：排序、评分下限、标签多选（与/或）。
 */
@Composable
fun FilterBar(
    filter: Filter,
    tagCounts: List<TagCount>,
    onFilterChange: ((Filter) -> Filter) -> Unit,
) {
    var showTagSheet by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 相似配色检索激活时，最前面显示色点 chip，点击退出
        filter.similarColor?.let { color ->
            FilterChip(
                selected = true,
                onClick = { onFilterChange { it.copy(similarColor = null) } },
                label = { Text("相似配色") },
                leadingIcon = {
                    Box(
                        Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(Color(color)),
                    )
                },
            )
            Spacer(Modifier.width(6.dp))
        }
        if (filter.untaggedOnly) {
            FilterChip(
                selected = true,
                onClick = { onFilterChange { it.copy(untaggedOnly = false) } },
                label = { Text("未标记") },
            )
            Spacer(Modifier.width(6.dp))
        }
        FilterChip(
            selected = filter.tags.isNotEmpty(),
            onClick = { showTagSheet = true },
            label = {
                // 显示真实标签名（与=&，或=|），而非「标签(N)」
                Text(
                    if (filter.tags.isEmpty()) "标签"
                    else filter.tags.joinToString(if (filter.andMode) " & " else " | "),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
        )
        if (filter.tags.size >= 2) {
            Spacer(Modifier.width(6.dp))
            FilterChip(
                selected = true,
                onClick = { onFilterChange { it.copy(andMode = !it.andMode) } },
                label = { Text(if (filter.andMode) "与" else "或") },
            )
        }
        Spacer(Modifier.width(6.dp))
        StarFilter(filter.minStar, onChange = { star -> onFilterChange { it.copy(minStar = star) } })
        Spacer(Modifier.width(6.dp))
        SortMenu(filter.sort, onSortChange = { s -> onFilterChange { it.copy(sort = s) } })
    }

    if (showTagSheet) {
        TagSheet(
            filter = filter,
            tagCounts = tagCounts,
            onFilterChange = onFilterChange,
            onDismiss = { showTagSheet = false },
        )
    }
}

@Composable
private fun SortMenu(current: Sort, onSortChange: (Sort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    // 必须用 Box 包裹，否则菜单锚定到外层 Row（出现在屏幕左侧而非按钮下方）
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(current.label)
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Sort.entries.forEach { sort ->
                DropdownMenuItem(
                    text = { Text(sort.label) },
                    onClick = {
                        onSortChange(sort)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** 评分下限筛选：点第 n 颗星表示「评分 ≥ n」，再点一次清除。 */
@Composable
private fun StarFilter(minStar: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        for (i in 1..5) {
            IconButton(onClick = { onChange(if (minStar == i) 0 else i) }, modifier = Modifier.size(30.dp)) {
                Icon(
                    imageVector = if (i <= minStar) Icons.Filled.Star else Icons.Outlined.Star,
                    contentDescription = "$i 星",
                    tint = if (i <= minStar) Color(0xFFFFB74D) else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TagSheet(
    filter: Filter,
    tagCounts: List<TagCount>,
    onFilterChange: ((Filter) -> Filter) -> Unit,
    onDismiss: () -> Unit,
) {
    var tagQuery by remember { mutableStateOf("") }
    val shownTags = remember(tagCounts, tagQuery) {
        if (tagQuery.isBlank()) tagCounts
        else tagCounts.filter { it.tag.contains(tagQuery.trim(), ignoreCase = true) }
    }
    // 跳过部分展开态：否则「清除」按钮出现/消失导致内容高度变化时，
    // sheet 会重新停靠并滑动，视觉上像滚动位置被重置回顶部
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            // 固定最小高度：「清除」「与/或」出现/消失不改变行高，sheet 高度保持稳定
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("按标签筛选", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                // 与/或切换：仅在选中 2 个及以上标签时出现
                if (filter.tags.size >= 2) {
                    Text("多标签", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    FilterChip(
                        selected = filter.andMode,
                        onClick = { onFilterChange { it.copy(andMode = true) } },
                        label = { Text("与") },
                    )
                    Spacer(Modifier.width(6.dp))
                    FilterChip(
                        selected = !filter.andMode,
                        onClick = { onFilterChange { it.copy(andMode = false) } },
                        label = { Text("或") },
                    )
                }
                if (filter.tags.isNotEmpty() || filter.untaggedOnly) {
                    TextButton(onClick = {
                        onFilterChange { it.copy(tags = emptySet(), untaggedOnly = false) }
                    }) {
                        Text("清除")
                    }
                }
            }
            // 标签数量多时的搜索框
            if (tagCounts.size > 8) {
                OutlinedTextField(
                    value = tagQuery,
                    onValueChange = { tagQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    placeholder = { Text("搜索标签") },
                    singleLine = true,
                    trailingIcon = {
                        if (tagQuery.isNotEmpty()) {
                            IconButton(onClick = { tagQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "清空")
                            }
                        }
                    },
                )
            }
            // 「未标记」与标签多选、相似配色互斥
            FilterChip(
                selected = filter.untaggedOnly,
                onClick = {
                    onFilterChange { f ->
                        f.copy(untaggedOnly = !f.untaggedOnly, tags = emptySet(), similarColor = null)
                    }
                },
                label = { Text("未标记（无标签图片）") },
                modifier = Modifier.padding(vertical = 4.dp),
            )
            if (shownTags.isEmpty()) {
                Text(
                    if (tagCounts.isEmpty()) "图库中还没有标签" else "没有匹配的标签",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                // 标签区自己持有滚动状态并限定高度：点选标签触发重组时保持滚动位置，
                // 不会被 BottomSheet 内部布局变化重置回顶部
                Column(
                    Modifier
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(vertical = 12.dp),
                    ) {
                        shownTags.forEach { tc ->
                            val selected = tc.tag in filter.tags
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    onFilterChange { f ->
                                        val next = if (selected) f.tags - tc.tag else f.tags + tc.tag
                                        // 选中任一标签即退出「未标记」与「相似配色」模式（互斥）
                                        f.copy(tags = next, untaggedOnly = false, similarColor = null)
                                    }
                                },
                                label = { Text("${tc.tag} (${tc.cnt})") },
                            )
                        }
                    }
                }
            }
        }
    }
}
