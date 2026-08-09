package com.eagleviewer.app.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Dao
interface ItemDao {

    @Query("SELECT id, lastModified FROM items")
    suspend fun allModified(): List<IdModified>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItems(items: List<ItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTags(refs: List<ItemTagCrossRef>)

    @Query("DELETE FROM item_tag WHERE itemId IN (:itemIds)")
    suspend fun deleteTagsFor(itemIds: List<String>)

    @Query("DELETE FROM items WHERE id IN (:ids)")
    suspend fun deleteItems(ids: List<String>)

    /** 分享多选图片时按 id 取原图 URI。 */
    @Query("SELECT imageUri FROM items WHERE id IN (:ids)")
    suspend fun imageUrisFor(ids: List<String>): List<String>

    // IN (...) 列表每个元素占一个 SQL 变量；Android 8–11 的 SQLite 上限为 999，
    // 超过即抛 SQLiteException。所有可能传入大列表的调用方一律走下面的分批封装。
    companion object {
        const val SQL_BATCH = 400
    }

    /** 分批删除，规避 SQL 变量上限（切库时 toDelete 可能是整个旧图库）。 */
    suspend fun deleteItemsChunked(ids: List<String>) {
        ids.chunked(SQL_BATCH).forEach { deleteItems(it) }
    }

    /** 分批查询原图 URI，规避 SQL 变量上限（多选分享可能超过 999 张）。 */
    suspend fun imageUrisForChunked(ids: List<String>): List<String> =
        ids.chunked(SQL_BATCH).flatMap { imageUrisFor(it) }

    @Transaction
    suspend fun upsertFull(items: List<ItemEntity>, refs: List<ItemTagCrossRef>) {
        upsertItems(items)
        deleteTagsFor(items.map { it.id })
        upsertTags(refs)
    }

    /** 动态筛选查询由 [com.eagleviewer.app.data.ItemRepository] 组装 SQL。 */
    @Transaction
    @RawQuery(observedEntities = [ItemEntity::class, ItemTagCrossRef::class])
    fun pagedItems(query: SupportSQLiteQuery): PagingSource<Int, ItemWithTags>

    @Query("SELECT tag, COUNT(*) AS cnt FROM item_tag GROUP BY tag ORDER BY cnt DESC, tag ASC")
    fun tagCounts(): Flow<List<TagCount>>

    @Query("SELECT COUNT(*) FROM items")
    fun itemCount(): Flow<Int>

    /** 颜色相似度检索：取全部条目的色板数据在内存中排序。 */
    @Query("SELECT id, palettesJson FROM items")
    suspend fun allPalettes(): List<IdPalettes>
}
