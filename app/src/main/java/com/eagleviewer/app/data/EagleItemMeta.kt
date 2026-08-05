package com.eagleviewer.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * 单张图片 `.info/metadata.json` 的模型。
 * 评分 `star` 为可选字段（未评分时 Eagle 不写出），这里默认 0。
 * 未知字段（如 palettes 内的 `$$hashKey`）通过 [json] 的 ignoreUnknownKeys 忽略。
 */
@Serializable
data class EagleItemMeta(
    val id: String,
    val name: String,
    val ext: String = "",
    val size: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val btime: Long = 0,
    val mtime: Long = 0,
    val modificationTime: Long = 0,
    val lastModified: Long = 0,
    val tags: List<String> = emptyList(),
    val folders: List<String> = emptyList(),
    val isDeleted: Boolean = false,
    val star: Int = 0,
    val annotation: String = "",
    val url: String = "",
    val palettes: List<Palette> = emptyList(),
) {
    @Serializable
    data class Palette(
        val color: List<Int> = emptyList(),
        val ratio: Double = 0.0,
    )

    /** 主色（palettes[0]）转为 ARGB Long，无数据时返回 null。 */
    val primaryColor: Long?
        get() = palettes.firstOrNull()?.color?.let { c ->
            if (c.size < 3) return null
            val r = c[0].coerceIn(0, 255).toLong()
            val g = c[1].coerceIn(0, 255).toLong()
            val b = c[2].coerceIn(0, 255).toLong()
            (0xFFL shl 24) or (r shl 16) or (g shl 8) or b
        }

    companion object {
        val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): EagleItemMeta = json.decodeFromString(text)
    }
}

/**
 * 解析根目录 `mtime.json`：`{ "<ID>": lastModified, "all": 总数 }`。
 * "all" 是统计信息，不作为条目返回。
 */
fun parseMtimeJson(text: String): Map<String, Long> {
    val obj = Json.parseToJsonElement(text).let {
        it as? kotlinx.serialization.json.JsonObject
    } ?: return emptyMap()
    return obj.entries
        .filter { (k, v) -> k != "all" && v is kotlinx.serialization.json.JsonPrimitive && v.jsonPrimitive.isString.not() }
        .associate { (k, v) -> k to v.jsonPrimitive.long }
}
