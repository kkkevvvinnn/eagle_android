package com.eagleviewer.app.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun interface ReadonlyPendingGate { suspend fun check(root: String) }
fun interface ReadonlyPrivatePendingProbe { fun check() }

/** Conservative: any entry except the store's lock means private work needs review. */
class PrivatePendingProbe(private val dataDir: java.io.File, private val filesDir: java.io.File) : ReadonlyPrivatePendingProbe {
    override fun check() {
        val data = dataDir.canonicalFile.toPath()
        val files = filesDir.canonicalFile.toPath()
        require(Files.isDirectory(data, NOFOLLOW_LINKS) && Files.isDirectory(files, NOFOLLOW_LINKS))
        require(files != data && files.startsWith(data))
        val dir = files.resolve("manual-private-v1")
        val attrs = try { Files.readAttributes(dir, java.nio.file.attribute.BasicFileAttributes::class.java, NOFOLLOW_LINKS) }
            catch (_: java.nio.file.NoSuchFileException) { return }
        require(attrs.isDirectory) { "本机标签变更记录无法读取，图库暂时隐藏。" }
        Files.newDirectoryStream(dir).use { entries ->
            var count = 0
            for (entry in entries) {
                require(++count <= 1024) { "本机标签变更记录过多，需要先核对恢复状态。" }
                require(entry.fileName.toString() == ".lock" && Files.isRegularFile(entry, NOFOLLOW_LINKS)) { "本机有待核对的标签变更记录，请先完成恢复。" }
            }
        }
    }
}

/** Only root metadata and fixed-root child names are observed; marker contents are never opened. */
class AndroidReadonlyPendingGate(private val context: Context, private val privateProbe: ReadonlyPrivatePendingProbe) : ReadonlyPendingGate {
    override suspend fun check(root: String) = withContext(Dispatchers.IO) {
        privateProbe.check()
        val uri = Uri.parse(root)
        require(uri.scheme == "content" && !uri.authority.isNullOrEmpty() && DocumentsContract.isTreeUri(uri))
        require(context.contentResolver.persistedUriPermissions.any { it.isReadPermission && it.uri.toString() == root }) { "素材库访问权限已失效，请重新选择文件夹。" }
        val id = DocumentsContract.getTreeDocumentId(uri)
        val document = DocumentsContract.buildDocumentUriUsingTree(uri, id)
        val resolver = context.contentResolver
        resolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null).use { c ->
            require(c != null && c.moveToFirst())
            require(c.getString(0) == id && c.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR && !c.moveToNext())
        }
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, id)
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null).use { c ->
            require(c != null)
            var count = 0
            while (c.moveToNext()) {
                require(++count <= 100000)
                val name = c.getString(0) ?: error("missing child name")
                require(!isPortablePendingName(name)) { "素材库有未完成的标签变更，请确认其他编辑已停止、同步已完成后重试。" }
            }
        }
    }
}

internal fun isPortablePendingName(name: String): Boolean {
    val folded = name.map { if (it in 'A'..'Z') it + 32 else it }.joinToString("")
    return folded == "kevin-writeback-v1.json" || folded == "kevin-writeback-v1"
}
