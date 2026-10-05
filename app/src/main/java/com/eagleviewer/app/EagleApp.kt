package com.eagleviewer.app

import android.app.Application
import android.content.Context
import android.os.Build
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
    val scanner: EagleScanner = EagleScanner(context, db, settings)
    val items: ItemRepository = ItemRepository(db)

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
