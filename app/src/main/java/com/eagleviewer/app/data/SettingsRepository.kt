package com.eagleviewer.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "settings")

/** 持久化图库根目录（SAF tree URI 字符串）、多图库列表、上次扫描时间、筛选状态与网格列数。 */
class SettingsRepository(private val context: Context) {

    private val keyLibraryUri = stringPreferencesKey("library_uri")
    private val keyLibraries = stringPreferencesKey("libraries_json")
    private val keyLastScan = longPreferencesKey("last_scan_time")
    private val keyFilterJson = stringPreferencesKey("filter_json")
    private val keyColumnCount = intPreferencesKey("column_count")
    private val keyThemeMode = intPreferencesKey("theme_mode")

    val libraryUri: Flow<String> = context.dataStore.data.map { it[keyLibraryUri] ?: "" }

    /** 已添加的图库 URI 列表；兼容旧版：列表为空但已设当前目录时迁移为单元素列表。 */
    val libraries: Flow<List<String>> = context.dataStore.data.map { prefs ->
        val stored = decodeUriList(prefs[keyLibraries] ?: "")
        val active = prefs[keyLibraryUri] ?: ""
        if (stored.isEmpty() && active.isNotEmpty()) listOf(active) else stored
    }

    val lastScanTime: Flow<Long> = context.dataStore.data.map { it[keyLastScan] ?: 0L }

    /** 序列化后的 Filter，空串表示未保存过。 */
    val filterJson: Flow<String> = context.dataStore.data.map { it[keyFilterJson] ?: "" }

    /** 网格列数，默认 2。 */
    val columnCount: Flow<Int> = context.dataStore.data.map { it[keyColumnCount] ?: 2 }

    /** 主题模式：0 = 跟随系统（默认），1 = 深色，2 = 浅色。 */
    val themeMode: Flow<Int> = context.dataStore.data.map { it[keyThemeMode] ?: 0 }

    suspend fun setLibraryUri(uri: String) {
        context.dataStore.edit { it[keyLibraryUri] = uri }
    }

    suspend fun addLibrary(uri: String) {
        context.dataStore.edit { prefs ->
            val current = decodeUriList(prefs[keyLibraries] ?: "")
            prefs[keyLibraries] = Json.encodeToString((current + uri).distinct())
        }
    }

    suspend fun removeLibrary(uri: String) {
        context.dataStore.edit { prefs ->
            val current = decodeUriList(prefs[keyLibraries] ?: "")
            prefs[keyLibraries] = Json.encodeToString(current - uri)
        }
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

    suspend fun setThemeMode(mode: Int) {
        context.dataStore.edit { it[keyThemeMode] = mode }
    }

    private fun decodeUriList(json: String): List<String> =
        if (json.isEmpty()) emptyList()
        else runCatching { Json.decodeFromString<List<String>>(json) }.getOrDefault(emptyList())
}
