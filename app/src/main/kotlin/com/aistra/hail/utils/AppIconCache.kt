package com.aistra.hail.utils

import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.widget.ImageView
import androidx.collection.LruCache
import com.aistra.hail.R
import com.aistra.hail.app.HailData
import kotlinx.coroutines.*
import me.zhanghai.android.appiconloader.AppIconLoader
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext

/**
 * @author Rikka
 * Source
 * https://raw.githubusercontent.com/RikkaApps/Shizuku/master/manager/src/main/java/moe/shizuku/manager/utils/AppIconCache.kt
 */
object AppIconCache : CoroutineScope {

    private class AppIconLruCache constructor(maxSize: Int) :
        LruCache<Triple<String, Int, Int>, Bitmap>(maxSize) {

        override fun sizeOf(key: Triple<String, Int, Int>, value: Bitmap): Int {
            return value.byteCount / 1024
        }
    }

    override val coroutineContext: CoroutineContext get() = Dispatchers.Main

    private val lruCache: LruCache<Triple<String, Int, Int>, Bitmap>

    private val dispatcher: CoroutineDispatcher

    private var appIconLoaders = mutableMapOf<Int, AppIconLoader>()

    private var shrinkNonAdaptiveIcons: Boolean

    private val cf by lazy { ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) }

    /** 内存压力监听只需注册一次 */
    private var trimListenerRegistered = false

    /**
     * 系统报内存压力时丢掉图标缓存：图标随时可以按需重新解码，
     * 留着只会挡住真正需要内存的地方。
     */
    private fun ensureTrimListener(context: Context) {
        if (trimListenerRegistered) return
        trimListenerRegistered = true
        runCatching {
            context.applicationContext.registerComponentCallbacks(
                object : ComponentCallbacks2 {
                    override fun onConfigurationChanged(newConfig: Configuration) {}
                    override fun onLowMemory() = lruCache.evictAll()
                    override fun onTrimMemory(level: Int) {
                        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) lruCache.evictAll()
                    }
                }
            )
        }
    }

    init {
        // Initialize app icon lru cache
        val maxMemory = Runtime.getRuntime().maxMemory() / 1024
        // 上限 = 堆的 1/8 且封顶 20MB。原来取 1/4：512MB 堆的机型可以养出 128MB 图标缓存，
        // 而 LruCache 只在触到上限时才淘汰，等于任由图标把内存吃满。
        val availableCacheSize = minOf(maxMemory / 8, 20L * 1024)
            .coerceAtLeast(4L * 1024).toInt()
        lruCache = AppIconLruCache(availableCacheSize)

        // Initialize load icon scheduler
        val availableProcessorsCount = try {
            Runtime.getRuntime().availableProcessors()
        } catch (ignored: Exception) {
            1
        }
        val threadCount = 1.coerceAtLeast(availableProcessorsCount / 2)
        val loadIconExecutor: Executor = Executors.newFixedThreadPool(threadCount)
        dispatcher = loadIconExecutor.asCoroutineDispatcher()
        shrinkNonAdaptiveIcons = HailData.synthesizeAdaptiveIcons
    }

    private fun get(packageName: String, userId: Int, size: Int): Bitmap? {
        return lruCache[Triple(packageName, userId, size)]
    }

    private fun put(packageName: String, userId: Int, size: Int, bitmap: Bitmap) {
        if (get(packageName, userId, size) == null) {
            lruCache.put(Triple(packageName, userId, size), bitmap)
        }
    }

    fun clear() = lruCache.evictAll()

    @SuppressLint("NewApi")
    fun getOrLoadBitmap(context: Context, info: ApplicationInfo, userId: Int, size: Int): Bitmap {
        ensureTrimListener(context)
        val cachedBitmap = get(info.packageName, userId, size)
        if (cachedBitmap != null) {
            return cachedBitmap
        }
        var loader = appIconLoaders[size]
        if (loader == null || shrinkNonAdaptiveIcons != HailData.synthesizeAdaptiveIcons) {
            shrinkNonAdaptiveIcons = HailData.synthesizeAdaptiveIcons
            loader = AppIconLoader(size, shrinkNonAdaptiveIcons, context)
            appIconLoaders[size] = loader
        }
        val bitmap = IconPack.loadIcon(info.packageName) ?: loader.loadIcon(info, false)
        put(info.packageName, userId, size, bitmap)
        return bitmap
    }

    @JvmStatic
    fun loadIconBitmapAsync(
        context: Context,
        info: ApplicationInfo,
        userId: Int,
        view: ImageView,
        setColorFilter: Boolean = false,
        sizePx: Int = 0
    ): Job {
        return launch {
            ensureTrimListener(context)
            // 固定设计尺寸，不再用 view.measuredWidth：后者差 1px 就会让同一应用在缓存里多存一份
            val size = if (sizePx > 0) sizePx else context.resources.getDimensionPixelSize(R.dimen.app_icon_size)
            if (shrinkNonAdaptiveIcons != HailData.synthesizeAdaptiveIcons) {
                lruCache.evictAll()
            } else {
                val cachedBitmap = get(info.packageName, userId, size)
                if (cachedBitmap != null) {
                    view.setImageBitmap(cachedBitmap)
                    view.colorFilter = if (setColorFilter) cf else null
                    return@launch
                }
            }

            val bitmap = try {
                withContext(dispatcher) {
                    getOrLoadBitmap(context, info, userId, size)
                }
            } catch (e: CancellationException) {
                // do nothing if canceled
                return@launch
            } catch (e: Throwable) {
                null
            }

            if (bitmap != null) {
                view.setImageBitmap(bitmap)
            } else {
                view.setImageDrawable(if (HTarget.O) context.packageManager.defaultActivityIcon else null)
            }
            view.colorFilter = if (setColorFilter) cf else null
        }
    }
}