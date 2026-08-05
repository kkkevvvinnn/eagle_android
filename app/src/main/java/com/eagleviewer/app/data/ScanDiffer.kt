package com.eagleviewer.app.data

/**
 * 增量扫描 diff 的纯逻辑（不依赖 Android，便于 JVM 单测）。
 *
 * 对比数据库中已索引的 `id -> lastModified` 与根目录 mtime.json 的内容：
 * - lastModified 不一致或本地缺失的 id 需要重新读取其 `.info/metadata.json`
 * - 数据库有但 mtime.json 没有的 id 说明源库已移除，需要删除索引
 */
object ScanDiffer {

    data class Diff(
        val toScan: List<String>,
        val toDelete: List<String>,
    )

    fun diff(indexed: Map<String, Long>, mtime: Map<String, Long>): Diff {
        val toScan = mtime.filter { (id, lastModified) -> indexed[id] != lastModified }.keys
        val toDelete = indexed.keys - mtime.keys
        return Diff(toScan = toScan.sorted(), toDelete = toDelete.toList())
    }
}
