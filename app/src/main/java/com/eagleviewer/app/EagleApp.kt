package com.eagleviewer.app

import android.app.Application
import android.content.Context
import android.os.Build
import kotlinx.coroutines.launch
import coil.Coil
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.eagleviewer.app.data.EagleScanner
import com.eagleviewer.app.data.ItemRepository
import com.eagleviewer.app.data.SettingsRepository
import com.eagleviewer.app.data.db.AppDatabase

/** 手工依赖容器，避免引入 DI 框架。 */
class AppContainer(context: Context) {
    val db: AppDatabase = AppDatabase.get(context)
    val settings: SettingsRepository = SettingsRepository(context)
    val index = com.eagleviewer.app.data.IndexCoordinator(settings, com.eagleviewer.app.data.AndroidReadonlyPendingGate(context.applicationContext, com.eagleviewer.app.data.PrivatePendingProbe(context.dataDir, context.filesDir)))
    private val indexScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
    // Navigation must not reject a valid restored ticket before durable identity has loaded.
    val navigationIndex = com.eagleviewer.app.data.NavigationIndexInitialization()
    init { initializeIndex() }
    fun initializeIndex() { indexScope.launch { navigationIndex.load { index.initialize() } } }
    fun foregroundIndex() {
        val observation = index.suspendVisibility()
        indexScope.launch {
            try { index.foregroundCheck(observation) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { /* coordinator keeps the index blocked */ }
        }
    }
    val scanner: EagleScanner = EagleScanner(context, db, settings, index)
    val items: ItemRepository = ItemRepository(db)
    val localPalettes = com.eagleviewer.app.data.LocalPaletteResolver(
        com.eagleviewer.app.data.SafPaletteCapture(context.applicationContext),
        com.eagleviewer.app.data.AndroidPaletteDecoder(),
        com.eagleviewer.app.data.DeferredPaletteCache {
            com.eagleviewer.app.data.FilePaletteCache(com.eagleviewer.app.data.privatePaletteDirectory(context.dataDir,context.cacheDir,"local-palettes-v1"))
        },
    )

    val imageLoader: ImageLoader = ImageLoader.Builder(context)
        .components {
            // GIF 支持：API 28+ 用 ImageDecoder，以下用 GifDecoder
            if (Build.VERSION.SDK_INT >= 28) {
                add(ImageDecoderDecoder.Factory())
            } else {
                add(GifDecoder.Factory())
            }
        }
        .crossfade(true)
        .build()
}

class EagleApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Coil.setImageLoader(container.imageLoader)
    }
}
