package com.eagleviewer.app.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / 1048576f)
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / 1024f)
    else -> "$bytes B"
}

fun formatTime(epochMs: Long): String =
    if (epochMs <= 0) "-"
    else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))

/** 从 SAF tree URI 提取可读的目录名（如 "primary:Pictures/我的灵感.library" → "我的灵感.library"）。 */
fun libraryDisplayName(uri: String): String = runCatching {
    val decoded = android.net.Uri.decode(uri)
    decoded.substringAfterLast('/').substringAfterLast(':').ifBlank { decoded }
}.getOrElse { uri }
