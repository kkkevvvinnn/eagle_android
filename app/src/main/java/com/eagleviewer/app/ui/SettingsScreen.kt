package com.eagleviewer.app.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.eagleviewer.app.BuildConfig

/**
 * 设置页：多图库管理（添加/切换/移除）+ 关于信息（介绍、版本、版权、开源地址）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: GridViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val libraries by vm.libraries.collectAsState()
    val activeUri by vm.activeLibraryUri.collectAsState()
    val scanState by vm.scanState.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (e: SecurityException) {
                // 个别提供方不支持持久授权，本次会话内仍可读
            }
            vm.addLibrary(uri.toString())
        }
    }

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
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Text("图库目录", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "切换目录后会重新建立索引（仅读取元数据）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            libraries.forEach { uri ->
                val isActive = uri == activeUri
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isActive) { vm.switchLibrary(uri) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = if (isActive) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(libraryDisplayName(uri), style = MaterialTheme.typography.bodyLarge)
                        if (isActive) {
                            Text(
                                "当前使用",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    if (scanState is ScanUiState.Running && isActive) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else if (!isActive) {
                        IconButton(onClick = { vm.removeLibrary(uri) }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "移除",
                                tint = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
            }

            Button(
                onClick = { picker.launch(null) },
                modifier = Modifier.padding(vertical = 8.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("添加图库目录")
            }

            HorizontalDivider(Modifier.padding(vertical = 16.dp))

            Text("关于", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text("Eagle 图库浏览器", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "在安卓设备上本地浏览由 Eagle 素材管理软件导出的 .library 图库：" +
                    "按标签/评分筛选、瀑布流预览、大图手势查看与多选分享，" +
                    "全部数据离线处理，不上传任何内容。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            AboutRow("版本", "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            AboutRow("开源协议", "MIT License")
            AboutRow("版权", "© 2026 kkkevvvinnn")
            AboutRow("作者", "kkkevvvinnn")
            Spacer(Modifier.height(4.dp))
            Text(
                "开源地址：github.com/kkkevvvinnn/eagle_android",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable { uriHandler.openUri("https://github.com/kkkevvvinnn/eagle_android") }
                    .padding(vertical = 8.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
