package com.eagleviewer.app.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 首次启动（或更换图库）的设置页：
 * 通过 SAF 选择 .library 根目录，持久化读权限后执行首次全量扫描。
 * 与网格页共享同一个 activity 级 GridViewModel（扫描互斥锁的唯一持有者）。
 */
@Composable
fun LibrarySetupScreen(
    vm: GridViewModel,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val scanState by vm.scanState.collectAsState()

    val launcher = rememberLauncherForActivityResult(
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
            vm.onLibraryPicked(uri.toString())
        }
    }

    LaunchedEffect(scanState) {
        if (scanState is ScanUiState.Done) onDone()
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Eagle 图库浏览器", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(16.dp))
            Text(
                "请选择从电脑同步过来的 Eagle 图库目录（.library 结尾的文件夹）。",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(32.dp))

            when (val state = scanState) {
                is ScanUiState.Running -> {
                    LinearProgressIndicator(
                        progress = {
                            if (state.total > 0) state.done.toFloat() / state.total else 0f
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("正在建立索引：${state.done} / ${state.total}")
                }
                is ScanUiState.Error -> {
                    Text(
                        state.message,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { launcher.launch(null) }) {
                        Text("重新选择目录")
                    }
                }
                else -> {
                    Button(onClick = { launcher.launch(null) }) {
                        Text("选择图库目录")
                    }
                }
            }
        }
    }
}
