package com.eagleviewer.app.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.eagleviewer.app.data.db.AppDatabase
import com.eagleviewer.app.data.db.ItemEntity
import com.eagleviewer.app.data.db.ItemTagCrossRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.FileNotFoundException

/**
 * 基于 SAF tree URI 的图库扫描器。
 *
 * 用 [DocumentsContract.buildDocumentUriUsingTree] 直接拼出每个文件的 content URI，
 * 避免 DocumentFile 逐个 listFiles 遍历（大图库下很慢）：
 * - 先读根 `mtime.json`，与 Room 中已索引的 lastModified 做 diff（见 [ScanDiffer]）
 * - 只对变化的条目读取 `images/<ID>.info/metadata.json` 并更新索引
 * - `isDeleted=true`（回收站）的条目不进入索引
 */
class EagleScanner(
    private val context: Context,
    private val db: AppDatabase,
) {

    class ScannerException(message: String) : Exception(message)

    data class Progress(val done: Int, val total: Int)

    data class ScanResult(
        val scanned: Int,
        val deleted: Int,
        val total: Int,
    )

    suspend fun scan(
        rootUriString: String,
        onProgress: (Progress) -> Unit = {},
    ): ScanResult = withContext(Dispatchers.IO) {
        val rootUri = Uri.parse(rootUriString)
        val treeDocId = DocumentsContract.getTreeDocumentId(rootUri)
        fun docUri(path: String): Uri =
            DocumentsContract.buildDocumentUriUsingTree(rootUri, "$treeDocId/$path")

        val mtimeText = try {
            readText(docUri("mtime.json"))
        } catch (e: Exception) {
            throw ScannerException("无法读取 mtime.json，请确认选择的是 Eagle 导出的 .library 根目录")
        }
        val mtime = parseMtimeJson(mtimeText)

        val dao = db.itemDao()
        val indexed = dao.allModified().associate { it.id to it.lastModified }
        val diff = ScanDiffer.diff(indexed, mtime)

        if (diff.toDelete.isNotEmpty()) {
            dao.deleteItems(diff.toDelete)
        }

        var scanned = 0
        val batch = mutableListOf<ItemEntity>()
        val batchTags = mutableListOf<ItemTagCrossRef>()
        val skippedDeleted = mutableListOf<String>()

        diff.toScan.forEachIndexed { index, id ->
            val metaText = try {
                readText(docUri("images/$id.info/metadata.json"))
            } catch (e: Exception) {
                null
            }
            val meta = metaText?.let {
                try {
                    EagleItemMeta.parse(it)
                } catch (e: Exception) {
                    null
                }
            }

            if (meta == null) {
                // 文件缺失或解析失败：若库里有旧记录则删掉，保持一致
                if (indexed.containsKey(id)) dao.deleteItems(listOf(id))
            } else if (meta.isDeleted) {
                skippedDeleted += id
                if (indexed.containsKey(id)) dao.deleteItems(listOf(id))
            } else {
                val imagePath = "images/$id.info/${meta.name}.${meta.ext}"
                val thumbPath = "images/$id.info/${meta.name}_thumbnail.png"
                batch += ItemEntity(
                    id = meta.id,
                    name = meta.name,
                    ext = meta.ext,
                    width = meta.width,
                    height = meta.height,
                    size = meta.size,
                    star = meta.star.coerceIn(0, 5),
                    btime = meta.btime,
                    mtime = meta.mtime,
                    lastModified = meta.lastModified,
                    annotation = meta.annotation,
                    url = meta.url,
                    imageUri = docUri(imagePath).toString(),
                    thumbUri = docUri(thumbPath).toString(),
                    paletteColor = meta.primaryColor,
                    palettesJson = if (meta.palettes.isEmpty()) ""
                        else EagleItemMeta.json.encodeToString(meta.palettes),
                )
                batchTags += meta.tags.distinct().map { ItemTagCrossRef(meta.id, it) }
                scanned++
            }

            if (batch.size >= 200) {
                dao.upsertFull(batch.toList(), batchTags.toList())
                batch.clear()
                batchTags.clear()
            }
            onProgress(Progress(index + 1, diff.toScan.size))
        }

        if (batch.isNotEmpty()) {
            dao.upsertFull(batch.toList(), batchTags.toList())
        }

        ScanResult(scanned = scanned, deleted = diff.toDelete.size, total = mtime.size)
    }

    private fun readText(uri: Uri): String {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw FileNotFoundException(uri.toString())
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
