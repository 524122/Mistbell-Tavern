package com.mistbell.tavern.android.render.engine

import android.content.Context
import android.graphics.Bitmap
import com.facebook.soloader.SoLoader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.paragraph.FontCollection

/**
 * 引擎引导（M3）：幂等初始化 native 依赖与字体收集器。M4 在 AppContainer 调用一次。
 *
 * - SoLoader.init：Yoga 的 YogaNative static 块走 SoLoader.loadLibrary("yoga")，首次使用前必须 init；
 * - FontCollection：skiko 段落字体收集器挂 FontMgr.default（M1 spike 验证：拉丁硬可用、
 *   CJK 在系统含中文字体的设备可用）。
 *
 * 未 init 就渲染 → PanelRenderer 捕获并以 fallback 标志返回（绝不上抛）。
 *
 * 共享渲染资源（跨渲染复用）：[renderDispatcher] / [bitmapCache] / [textMeasurer] 由本处
 * 单例持有，渲染调用点不再各自新建 PanelRenderer 默认缓存（24MB 位图预算与文本测量缓存
 * 每次重建会白白抖动内存）。三者**只允许在 [renderDispatcher] 上访问**：skia JNI 对象与
 * 缓存内部结构均不保证跨线程安全，共享后必须单线程串行。
 */
object EngineBootstrap {
    @Volatile
    private var fontCollectionRef: FontCollection? = null

    /** 已初始化的 FontCollection；null = 未引导 */
    val fontCollection: FontCollection? get() = fontCollectionRef

    val isInitialized: Boolean get() = fontCollectionRef != null

    /**
     * 引擎渲染专用调度器：Dispatchers.Default 限单并发——所有引擎渲染都切到它上执行，
     * 共享的 skia 对象与缓存由此获得"同一时刻至多一个渲染在跑"的串行保证。
     */
    val renderDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

    /** 共享位图缓存（PanelRenderer 默认构造的同规格 24MB LRU）；只允许在 [renderDispatcher] 上访问 */
    val bitmapCache: BitmapCache<BitmapCacheKey, Bitmap> by lazy {
        BitmapCache(BitmapCache.DEFAULT_MAX_BYTES) { bitmap -> bitmap.byteCount }
    }

    @Volatile
    private var textMeasurerRef: TextMeasurer? = null

    /**
     * 共享文本测量器（依赖 [fontCollection]，首次成功访问时构建并缓存）；null = 未引导，
     * 调用方保持 PanelRenderer 现有 fallback 语义。只允许在 [renderDispatcher] 上访问。
     */
    val textMeasurer: TextMeasurer?
        get() {
            textMeasurerRef?.let { return it }
            val collection = fontCollection ?: return null
            synchronized(this) {
                textMeasurerRef?.let { return it }
                return TextMeasurer(collection).also { textMeasurerRef = it }
            }
        }

    /** 幂等 init（重复调用无副作用） */
    fun init(context: Context) {
        if (fontCollectionRef != null) return
        synchronized(this) {
            if (fontCollectionRef != null) return
            SoLoader.init(context, 0)
            val collection = FontCollection()
            collection.setDefaultFontManager(FontMgr.default)
            fontCollectionRef = collection
        }
    }

    /** 取已初始化的 FontCollection；未引导即抛（调用方 catch 转 fallback，不外泄给宿主） */
    fun requireFontCollection(): FontCollection =
        fontCollectionRef
            ?: error("EngineBootstrap 未初始化：请先在宿主进程入口调用 EngineBootstrap.init(context)")
}
