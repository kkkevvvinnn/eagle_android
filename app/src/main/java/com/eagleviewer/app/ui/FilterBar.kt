package com.eagleviewer.app.ui

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.FilterList
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
import com.eagleviewer.app.data.TagCatalog
import com.eagleviewer.app.data.TagSection
import com.eagleviewer.app.data.CatalogTag

/** 单一筛选入口与独立排序。筛选摘要优先展示标签名。 */
@Composable
fun FilterBar(
    filter: Filter,
    tagCatalog: TagCatalog,
    onFilterChange: ((Filter) -> Filter) -> Unit,
) {
    var showFilterSheet by remember { mutableStateOf(false) }
    val summary = buildList {
        if (filter.tags.isNotEmpty()) add(filter.tags.joinToString(if (filter.andMode) " & " else " | "))
        if (filter.untaggedOnly) add("未标记")
        if (filter.minStar > 0) add("≥${filter.minStar}星")
        filter.effectiveColor?.let { add(it.common?.label ?: "#%06X".format(it.rgb.argb and 0xffffff)) }
        if (filter.nameQuery.isNotBlank()) add("搜索：${filter.nameQuery}")
    }.joinToString(" · ").ifEmpty { "筛选" }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = filter.isActive,
            onClick = { showFilterSheet = true },
            modifier = Modifier.weight(1f),
            leadingIcon = { Icon(Icons.Default.FilterList, contentDescription = null, modifier = Modifier.size(18.dp)) },
            label = { Text(summary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        )
        Spacer(Modifier.width(6.dp))
        SortMenu(filter.sort, onSortChange = { s -> onFilterChange { it.copy(sort = s) } })
    }

    if (showFilterSheet) {
        FilterSheet(filter, tagCatalog, onFilterChange, onDismiss = { showFilterSheet = false })
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
            IconButton(onClick = { onChange(if (minStar == i) 0 else i) }, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = if (i <= minStar) Icons.Filled.Star else Icons.Outlined.Star,
                    contentDescription = "至少 $i 星",
                    tint = if (i <= minStar) Color(0xFFFFB74D) else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(
    filter: Filter,
    tagCatalog: TagCatalog,
    onFilterChange: ((Filter) -> Filter) -> Unit,
    onDismiss: () -> Unit,
) {
    var tagQuery by remember { mutableStateOf("") }
    var colorExpanded by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val shownSections = remember(tagCatalog, tagQuery, filter.tags) {
        val known = tagCatalog.sections.flatMap { it.tags }.map { it.tag }.toSet()
        val sections = tagCatalog.sections.map { section ->
            section.copy(tags = section.tags.filter { it.tag.contains(tagQuery.trim(), ignoreCase = true) })
        }
        val orphans = (filter.tags - known).map { CatalogTag(it, 0, 0, 0) }
        if (orphans.isEmpty()) sections else listOf(TagSection("已选（目录外）", orphans)) + sections
    }
    // 跳过部分展开态：否则「清除」按钮出现/消失导致内容高度变化时，
    // sheet 会重新停靠并滑动，视觉上像滚动位置被重置回顶部
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text("筛选", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                if (filter.isActive) {
                    TextButton(onClick = {
                        onFilterChange { Filter(sort = it.sort, andMode = it.andMode) }
                    }) { Text("清除全部") }
                }
                TextButton(onClick = onDismiss) { Text("完成") }
            }
            Column(Modifier.verticalScroll(rememberScrollState())) {
            // 标签数量多时的搜索框
            run {
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
            // 标签和未标记互斥，保留其他筛选条件。
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = filter.untaggedOnly,
                    onClick = { onFilterChange { it.copy(untaggedOnly = !it.untaggedOnly, tags = emptySet()) } },
                    label = { Text("未标记") },
                )
                Spacer(Modifier.weight(1f))
                if (filter.tags.size >= 2) {
                    FilterChip(selected = filter.andMode,
                        onClick = { onFilterChange { it.copy(andMode = true) } }, label = { Text("与") })
                    Spacer(Modifier.width(6.dp))
                    FilterChip(selected = !filter.andMode,
                        onClick = { onFilterChange { it.copy(andMode = false) } }, label = { Text("或") })
                }
                if (filter.tags.isNotEmpty() || filter.untaggedOnly) {
                    TextButton(onClick = { onFilterChange { it.copy(tags = emptySet(), untaggedOnly = false) } }) {
                        Text("清除标签")
                    }
                }
            }
            if (tagCatalog.status.isNotEmpty()) {
                Text(tagCatalog.status, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // One stable scroll owner remains composed even when search has no matches.
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(scrollState)) {
                if (shownSections.all { it.tags.isEmpty() }) {
                    Text(if (tagQuery.isEmpty()) "图库中还没有标签" else "没有匹配的标签",
                        modifier = Modifier.padding(vertical = 12.dp))
                }
                shownSections.forEach { section ->
                    if (section.tags.isNotEmpty() || tagQuery.isEmpty()) {
                        Text(section.name.ifEmpty { "未命名分组" },
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(vertical = 4.dp)) {
                            section.tags.forEach { tc ->
                                val selected = tc.tag in filter.tags
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        onFilterChange { f ->
                                            val next = if (tc.tag in f.tags) f.tags - tc.tag else f.tags + tc.tag
                                            f.copy(tags = next, untaggedOnly = false)
                                        }
                                    },
                                    label = { Text("${tc.tag} (${tc.current}/${tc.global})") },
                                )
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("评分", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                StarFilter(filter.minStar, onChange = { star -> onFilterChange { it.copy(minStar = star) } })
            }
            TextButton(onClick = { colorExpanded = !colorExpanded }, modifier = Modifier.fillMaxWidth()) {
                Text("主色" + if (filter.effectiveColor != null) " · 已选" else "")
                Spacer(Modifier.weight(1f))
                Text(if (colorExpanded) "收起" else "展开")
            }
            if (colorExpanded) ColorControls(filter, onFilterChange)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorControls(filter: Filter, onChange: ((Filter) -> Filter) -> Unit) {
    val condition = filter.effectiveColor
    var hex by remember(condition?.rgb) {
        mutableStateOf("%06X".format(condition?.rgb?.argb?.and(0xffffff) ?: 0xff0000))
    }
    val parsed = hex.takeIf { it.matches(Regex("[0-9A-Fa-f]{6}")) }?.toIntOrNull(16)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("占比 ≥15%", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.weight(1f))
        if (condition != null) {
            TextButton(onClick = { onChange { it.clearColor() } }) { Text("清除主色") }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        com.eagleviewer.app.data.CommonPaletteColor.entries.forEach { color ->
            FilterChip(
                selected = condition?.common == color,
                onClick = { onChange { it.copy(similarColor = null,
                    colorCondition = com.eagleviewer.app.data.ColorCondition(common = color, tolerance = it.effectiveColor?.tolerance ?: 25.0)) } },
                label = { Text(color.label) },
                leadingIcon = { Box(Modifier.size(14.dp).clip(CircleShape).background(Color(0xff000000.toInt() or color.swatch))) },
            )
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = hex, onValueChange = { hex = it }, modifier = Modifier.weight(1f),
            label = { Text("HEX（6位）") }, prefix = { Text("#") },
            singleLine = true, isError = parsed == null,
        )
        TextButton(enabled = parsed != null, onClick = {
            parsed?.let { rgb -> onChange { it.copy(similarColor = null,
                colorCondition = com.eagleviewer.app.data.ColorCondition(custom = com.eagleviewer.app.data.PaletteRGB.fromInt(rgb), tolerance = it.effectiveColor?.tolerance ?: 25.0)) } }
        }) { Text("使用") }
    }
    if (condition != null && (condition.custom != null || condition.common?.hue != null)) {
        Text("容差 ${condition.tolerance.toInt()}")
        androidx.compose.material3.Slider(
            value = condition.tolerance.toFloat(),
            onValueChange = { value -> onChange { it.copy(similarColor = null, colorCondition = it.effectiveColor?.copy(tolerance = value.toDouble())) } },
            valueRange = 10f..60f, steps = 49,
        )
    }
}
