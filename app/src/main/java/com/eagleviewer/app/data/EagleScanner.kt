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
        /** 图片本体文件未同步到本机的条目数（已跳过索引）。 */
        val missing: Int = 0,
    )

    suspend fun scan(
        rootUriString: String,
        onProgress: (Progress) -> Unit = {},
    ): ScanResult = withContext(Dispatchers.IO) {
        val rootUri = Uri.parse(rootUriString)
        val treeDocId = DocumentsContract.getTreeDocumentId(rootUri)
        // 直接拼接原始 documentId：buildDocumentUriUsingTree 内部用 appendPath，
        // 会对整个段做一次 URL 编码、provider 侧再解码还原，#/?/空格/中文都安全。
        // 切勿自行预编码——appendPath 会二次编码（% → %25），导致文件全部探测不到
        fun docUri(path: String): Uri =
            DocumentsContract.buildDocumentUriUsingTree(rootUri, "$treeDocId/$path")

        val mtimeText = try {
            readText(docUri("mtime.json"))
        } catch (e: Exception) {
            throw ScannerException("无法读取 mtime.json，请确认选择的是 Eagle 导出的 .library 根目录")
        }
        val mtime = try {
            parseMtimeJson(mtimeText)
        } catch (e: Exception) {
            throw ScannerException("mtime.json 解析失败：文件可能正在同步写入，请稍后重试")
        }
        // 防御：mtime.json 内容合法但为空（同步工具中间态/文件残缺）时，
        // diff 会把全库当作待删除，直接拒绝扫描
        if (mtime.isEmpty()) {
            throw ScannerException("mtime.json 内容为空或格式异常，请等待同步完成后重试")
        }

        val dao = db.itemDao()
        val indexed = dao.allModified().associate { it.id to it.lastModified }
        val diff = ScanDiffer.diff(indexed, mtime)

        if (diff.toDelete.isNotEmpty()) {
            dao.deleteItemsChunked(diff.toDelete)
        }

        // URI 自修复：1.7.0 曾错误地对 documentId 预编码，被 appendPath 二次编码后
        // 存入索引（含 %25），mtime 未变导致 diff 不会重扫这些条目；
        // 这里把 URI 含 %25 的已索引条目补进重扫列表（误伤"%"结尾的正常文件名
        // 无害，upsert 是幂等的）
        val brokenUriIds = dao.allImageUris()
            .filter { "%25" in it.imageUri }
            .map { it.id }
        val toScan = (diff.toScan + brokenUriIds).distinct()

        var scanned = 0
        var missing = 0
        val batch = mutableListOf<ItemEntity>()
        val batchTags = mutableListOf<ItemTagCrossRef>()
        // 条目缺失/解析失败/回收站时待删除的 id，循环结束后统一分批删除，
        // 避免逐条单行事务
        val toRemove = mutableListOf<String>()

        toScan.forEachIndexed { index, id ->
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
                if (indexed.containsKey(id)) toRemove += id
            } else if (meta.isDeleted) {
                if (indexed.containsKey(id)) toRemove += id
            } else {
                val imageDocUri = docUri("images/$id.info/${meta.name}.${meta.ext}")
                val thumbDocUri = docUri("images/$id.info/${meta.name}_thumbnail.png")
                if (!exists(imageDocUri)) {
                    // 图片本体未同步到本机（如源目录权限不足导致同步工具只拷了 JSON）。
                    // 跳过不索引：由于 DB 中没有该 id，下次扫描 diff 会自动重试，同步补全后自愈。
                    missing++
                    if (indexed.containsKey(id)) toRemove += id
                } else {
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
                        imageUri = imageDocUri.toString(),
                        // 不再逐条探测缩略图是否存在（每条一次跨进程 IPC，大库下代价高）：
                        // 直接存 URI，缺失时网格/详情页 Coil 加载失败会自动降级原图
                        thumbUri = thumbDocUri.toString(),
                        paletteColor = meta.primaryColor,
                        palettesJson = if (meta.palettes.isEmpty()) ""
                            else EagleItemMeta.json.encodeToString(meta.palettes),
                    )
                    batchTags += meta.tags.distinct().map { ItemTagCrossRef(meta.id, it) }
                    scanned++
                }
            }

            if (batch.size >= 200) {
                dao.upsertFull(batch.toList(), batchTags.toList())
                batch.clear()
                batchTags.clear()
            }
            // 进度回调节流：每条一次的状态写入会让 UI 侧持续重组
            if ((index + 1) % 20 == 0 || index + 1 == toScan.size) {
                onProgress(Progress(index + 1, toScan.size))
            }
        }

        if (batch.isNotEmpty()) {
            dao.upsertFull(batch.toList(), batchTags.toList())
        }
        if (toRemove.isNotEmpty()) {
            dao.deleteItemsChunked(toRemove)
        }

        ScanResult(
            scanned = scanned,
            deleted = diff.toDelete.size,
            // 总数直接取扫描后的索引实数，与网格/设置页口径严格一致
            // （不能按 mtime 推算：回收站条目留在 mtime 里但永不进索引，
            // 且 mtime 未变的回收站条目不会再被扫描统计到）
            total = dao.itemCountNow(),
            missing = missing,
        )
    }

    /** 探测文档是否存在且可读（不读取内容）。 */
    private fun exists(uri: Uri): Boolean = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.close()
        true
    } catch (e: Exception) {
        false
    }

    private fun readText(uri: Uri): String {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw FileNotFoundException(uri.toString())
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
