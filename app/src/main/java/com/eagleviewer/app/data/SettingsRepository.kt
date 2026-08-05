package com.eagleviewer.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** 持久化图库根目录（SAF tree URI 字符串）、上次扫描时间、筛选状态与网格列数。 */
class SettingsRepository(private val context: Context) {

    private val keyLibraryUri = stringPreferencesKey("library_uri")
    private val keyLastScan = longPreferencesKey("last_scan_time")
    private val keyFilterJson = stringPreferencesKey("filter_json")
    private val keyColumnCount = intPreferencesKey("column_count")

    val libraryUri: Flow<String> = context.dataStore.data.map { it[keyLibraryUri] ?: "" }

    val lastScanTime: Flow<Long> = context.dataStore.data.map { it[keyLastScan] ?: 0L }

    /** 序列化后的 Filter，空串表示未保存过。 */
    val filterJson: Flow<String> = context.dataStore.data.map { it[keyFilterJson] ?: "" }

    /** 网格列数，默认 2。 */
    val columnCount: Flow<Int> = context.dataStore.data.map { it[keyColumnCount] ?: 2 }

    suspend fun setLibraryUri(uri: String) {
        context.dataStore.edit { it[keyLibraryUri] = uri }
    }

    suspend fun setLastScanTime(time: Long) {
        context.dataStore.edit { it[keyLastScan] = time }
    }

    suspend fun setFilterJson(json: String) {
        context.dataStore.edit { it[keyFilterJson] = json }
    }

    suspend fun setColumnCount(count: Int) {
        context.dataStore.edit { it[keyColumnCount] = count }
    }
}
