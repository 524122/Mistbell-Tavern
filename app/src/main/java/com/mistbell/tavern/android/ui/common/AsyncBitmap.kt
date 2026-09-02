package com.mistbell.tavern.android.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.mistbell.tavern.android.util.AvatarBitmapCache
import com.mistbell.tavern.android.util.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * data URI 位图的组合期异步加载工具。
 *
 * 为什么自建而不用 Coil：项目不引入新依赖；头像/背景都来自 data URI（base64），
 * 走统一的采样解码 + 进程内 LRU 缓存即可覆盖全部使用点。
 *
 * 缓存同步命中时以命中值作为 initialValue 直接显示，避免滚动列表时头像闪烁；
 * 未命中则先清空（避免显示上一次来源的旧图），再在 Default 调度器上采样解码并写入缓存。
 */
@Composable
fun rememberBitmap(
    dataUri: String?,
    maxDimPx: Int,
): ImageBitmap? {
    val initial = dataUri?.let { AvatarBitmapCache.getSync(it, maxDimPx) }?.asImageBitmap()
    return produceState(initial, dataUri, maxDimPx) {
        if (dataUri == null) {
            value = null
        } else {
            val hit = AvatarBitmapCache.getSync(dataUri, maxDimPx)
            if (hit != null) {
                value = hit.asImageBitmap()
            } else {
                // 先清空：dataUri 变化时 produceState 保留旧 value，异步解码期间不能展示旧图
                value = null
                value =
                    withContext(Dispatchers.Default) {
                        ImageUtils.decodeSampledBitmap(dataUri, maxDimPx)?.also {
                            AvatarBitmapCache.put(dataUri, maxDimPx, it)
                        }
                    }?.asImageBitmap()
            }
        }
    }.value
}

/**
 * 本地文件位图的组合期异步加载工具（角色背景图等 File 来源），
 * 与 [rememberBitmap] 共用同一 LRU 缓存，key 以 "file:" 前缀区分来源。
 */
@Composable
fun rememberFileBitmap(
    filePath: String?,
    maxDimPx: Int,
): ImageBitmap? {
    val initial = filePath?.let { AvatarBitmapCache.getSync("file:$it", maxDimPx) }?.asImageBitmap()
    return produceState(initial, filePath, maxDimPx) {
        if (filePath == null) {
            value = null
        } else {
            val hit = AvatarBitmapCache.getSync("file:$filePath", maxDimPx)
            if (hit != null) {
                value = hit.asImageBitmap()
            } else {
                value = null
                value =
                    withContext(Dispatchers.Default) {
                        ImageUtils.decodeSampledBitmapFile(filePath, maxDimPx)?.also {
                            AvatarBitmapCache.put("file:$filePath", maxDimPx, it)
                        }
                    }?.asImageBitmap()
            }
        }
    }.value
}
