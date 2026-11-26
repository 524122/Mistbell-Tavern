package com.mistbell.tavern.android.render.engine

import android.content.Context
import com.facebook.soloader.SoLoader
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
 */
object EngineBootstrap {
    @Volatile
    private var fontCollectionRef: FontCollection? = null

    /** 已初始化的 FontCollection；null = 未引导 */
    val fontCollection: FontCollection? get() = fontCollectionRef

    val isInitialized: Boolean get() = fontCollectionRef != null

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
