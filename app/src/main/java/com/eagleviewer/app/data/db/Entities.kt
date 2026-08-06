package com.eagleviewer.app.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.eagleviewer.app.data.EagleItemMeta

@Entity(tableName = "items")
data class ItemEntity(
    @PrimaryKey val id: String,
    val name: String,
    val ext: String,
    val width: Int,
    val height: Int,
    val size: Long,
    val star: Int,
    val btime: Long,
    val mtime: Long,
    val lastModified: Long,
    val annotation: String,
    val url: String,
    val imageUri: String,
    val thumbUri: String?,
    val paletteColor: Long?,
    /** palettes 原始 JSON（[{color:[r,g,b],ratio:..}]），供详情页色板展示。 */
    val palettesJson: String = "",
)

@Entity(
    tableName = "item_tag",
    primaryKeys = ["itemId", "tag"],
    foreignKeys = [ForeignKey(
        entity = ItemEntity::class,
        parentColumns = ["id"],
        childColumns = ["itemId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("tag"), Index("itemId")],
)
data class ItemTagCrossRef(
    val itemId: String,
    val tag: String,
)

data class ItemWithTags(
    @Embedded val item: ItemEntity,
    @Relation(parentColumn = "id", entityColumn = "itemId")
    val tags: List<ItemTagCrossRef>,
) {
    val tagNames: List<String> get() = tags.map { it.tag }

    /** 解析 palettesJson，失败时返回空列表。 */
    val palettes: List<EagleItemMeta.Palette> by lazy {
        if (item.palettesJson.isEmpty()) emptyList()
        else runCatching {
            EagleItemMeta.json.decodeFromString<List<EagleItemMeta.Palette>>(item.palettesJson)
        }.getOrDefault(emptyList())
    }
}

data class IdModified(
    val id: String,
    val lastModified: Long,
)

data class TagCount(
    val tag: String,
    val cnt: Int,
)

/** 颜色相似度检索用的轻量投影。 */
data class IdPalettes(
    val id: String,
    val palettesJson: String,
)
