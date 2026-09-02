package com.mistbell.tavern.android.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.min

object ImageUtils {
    /**
     * 从 URI 加载并处理图片
     * @param context Android Context
     * @param uri 图片 URI
     * @param maxSize 最大尺寸（像素），0表示保留原图
     * @param quality 压缩质量 (0-100)
     * @return Base64 编码的图片数据，带 data URI 前缀
     */
    fun processImage(
        context: Context,
        uri: Uri,
        maxSize: Int = 0, // 改为0表示不缩放，保留原图
        quality: Int = 85,
    ): String? {
        return try {
            android.util.Log.d("ImageUtils", "Processing image from URI: $uri")

            val inputStream = context.contentResolver.openInputStream(uri) ?: return null

            // 读取图片
            val originalBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()

            if (originalBitmap == null) {
                android.util.Log.e("ImageUtils", "Failed to decode bitmap from URI")
                return null
            }

            android.util.Log.d("ImageUtils", "Original bitmap size: ${originalBitmap.width}x${originalBitmap.height}")

            // 修正图片方向
            val rotatedBitmap = fixImageRotation(context, uri, originalBitmap)

            // 如果设置了maxSize且大于0，才进行缩放，否则保留原图尺寸
            val finalBitmap =
                if (maxSize > 0 && (rotatedBitmap.width > maxSize || rotatedBitmap.height > maxSize)) {
                    android.util.Log.d("ImageUtils", "Resizing to max: $maxSize")
                    resizeBitmap(rotatedBitmap, maxSize)
                } else {
                    android.util.Log.d("ImageUtils", "Keeping original size")
                    rotatedBitmap
                }

            android.util.Log.d("ImageUtils", "Final bitmap size: ${finalBitmap.width}x${finalBitmap.height}")

            // 压缩并转换为 Base64（PNG格式）
            val base64 = bitmapToBase64(finalBitmap, quality)
            android.util.Log.d("ImageUtils", "Base64 length: ${base64.length}, starts with: ${base64.take(50)}")

            // 清理 Bitmap 资源
            if (rotatedBitmap != originalBitmap) {
                rotatedBitmap.recycle()
            }
            if (finalBitmap != rotatedBitmap) {
                finalBitmap.recycle()
            }
            originalBitmap.recycle()

            base64
        } catch (e: Exception) {
            android.util.Log.e("ImageUtils", "Error processing image", e)
            e.printStackTrace()
            null
        }
    }

    /**
     * 修正图片旋转方向
     */
    private fun fixImageRotation(
        context: Context,
        uri: Uri,
        bitmap: Bitmap,
    ): Bitmap {
        return try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            val exif = ExifInterface(inputStream!!)
            inputStream.close()

            val orientation =
                exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )

            val rotation =
                when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }

            if (rotation != 0f) {
                val matrix = Matrix()
                matrix.postRotate(rotation)
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            } else {
                bitmap
            }
        } catch (e: Exception) {
            e.printStackTrace()
            bitmap
        }
    }

    /**
     * 裁剪图片为正方形（居中裁剪）
     */
    private fun cropToSquare(bitmap: Bitmap): Bitmap {
        val size = min(bitmap.width, bitmap.height)
        val x = (bitmap.width - size) / 2
        val y = (bitmap.height - size) / 2
        return Bitmap.createBitmap(bitmap, x, y, size, size)
    }

    /**
     * 缩放 Bitmap 到目标尺寸
     */
    private fun resizeBitmap(
        bitmap: Bitmap,
        maxSize: Int,
    ): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        if (width <= maxSize && height <= maxSize) {
            return bitmap
        }

        val scale = maxSize.toFloat() / min(width, height)
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    /**
     * 将 Bitmap 转换为 Base64 字符串（带 data URI 前缀）
     * 使用 JPEG 格式以减小文件大小（PNG对于照片来说太大）
     */
    private fun bitmapToBase64(
        bitmap: Bitmap,
        quality: Int,
    ): String {
        val outputStream = ByteArrayOutputStream()
        // 使用 JPEG 格式以减小文件大小，避免超过数据库限制
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        val bytes = outputStream.toByteArray()
        val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        return "data:image/jpeg;base64,$base64"
    }

    /**
     * 计算处理后的图片大小（KB）
     */
    fun estimateImageSize(base64WithPrefix: String): Int {
        // 移除 data URI 前缀
        val base64 = base64WithPrefix.substringAfter("base64,")
        // Base64 编码后大小约为原始数据的 4/3
        return (base64.length * 3 / 4) / 1024
    }

    /**
     * 从 data URI 解析并创建 Bitmap
     * 支持 data:image/jpeg;base64,... 和 data:image/png;base64,... 格式
     */
    fun dataUriToBitmap(dataUri: String): Bitmap? {
        return try {
            // 检查是否是 data URI 格式
            if (!dataUri.startsWith("data:image/")) {
                android.util.Log.e("ImageUtils", "Invalid data URI format")
                return null
            }

            // 提取 Base64 部分
            val base64Data = dataUri.substringAfter("base64,")
            if (base64Data.isEmpty()) {
                android.util.Log.e("ImageUtils", "Empty base64 data")
                return null
            }

            // 解码 Base64
            val imageBytes = android.util.Base64.decode(base64Data, android.util.Base64.NO_WRAP)

            // 从字节数组创建 Bitmap
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

            if (bitmap == null) {
                android.util.Log.e("ImageUtils", "Failed to decode bitmap from base64 data")
            } else {
                android.util.Log.d("ImageUtils", "Successfully decoded bitmap: ${bitmap.width}x${bitmap.height}")
            }

            bitmap
        } catch (e: Exception) {
            android.util.Log.e("ImageUtils", "Error decoding data URI to bitmap", e)
            null
        }
    }

    /**
     * 按 maxDimPx 采样解码 data URI 位图。
     * 先用 inJustDecodeBounds 只测边界，再按 2 的幂计算 inSampleSize 降采样解码：
     * 4096px 原图仅显示为 256px 头像时无需整图解码，内存与耗时都大幅下降。
     */
    fun decodeSampledBitmap(
        dataUri: String,
        maxDimPx: Int,
    ): Bitmap? {
        // base64 载荷校验与解码抽为具名步骤：主函数保持 2 个 return，失败原因在辅助函数记日志
        val payload = base64ImagePayloadOrNull(dataUri) ?: return null
        return try {
            val imageBytes = android.util.Base64.decode(payload, android.util.Base64.NO_WRAP)
            // 第一步：只读边界，不解码像素
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)
            val inSampleSize = calcInSampleSize(bounds.outWidth, bounds.outHeight, maxDimPx)
            // 第二步：按采样率真正解码
            val options = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)
        } catch (e: Exception) {
            android.util.Log.e("ImageUtils", "Error decoding sampled bitmap from data URI", e)
            null
        }
    }

    /**
     * 校验 data:image/ 前缀并取出 base64 载荷；非图片 URI 或空载荷记日志后返回 null。
     */
    private fun base64ImagePayloadOrNull(dataUri: String): String? {
        if (!dataUri.startsWith("data:image/")) {
            android.util.Log.e("ImageUtils", "Invalid data URI format for sampled decode")
            return null
        }
        val payload = dataUri.substringAfter("base64,", "")
        if (payload.isEmpty()) {
            android.util.Log.e("ImageUtils", "Empty base64 data for sampled decode")
        }
        // 空载荷同样视为无效：takeIf 统一收敛为单一返回出口
        return payload.takeIf { it.isNotEmpty() }
    }

    /**
     * 按 maxDimPx 采样解码本地文件位图（角色背景图等 File 来源），
     * 采样策略与 [decodeSampledBitmap] 一致。
     */
    fun decodeSampledBitmapFile(
        path: String,
        maxDimPx: Int,
    ): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val inSampleSize = calcInSampleSize(bounds.outWidth, bounds.outHeight, maxDimPx)
            val options = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
            BitmapFactory.decodeFile(path, options)
        } catch (e: Exception) {
            android.util.Log.e("ImageUtils", "Error decoding sampled bitmap from file: $path", e)
            null
        }
    }

    /**
     * 计算 inSampleSize：取最大的 2 的幂，使采样后两边仍不小于 maxDimPx，
     * 避免降过头导致显示尺寸下明显模糊。
     */
    private fun calcInSampleSize(
        outWidth: Int,
        outHeight: Int,
        maxDimPx: Int,
    ): Int {
        var inSampleSize = 1
        if (outHeight > maxDimPx || outWidth > maxDimPx) {
            while (outHeight / (inSampleSize * 2) >= maxDimPx &&
                outWidth / (inSampleSize * 2) >= maxDimPx
            ) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}

/**
 * 位图进程内 LRU 缓存：按 (解码上限, 来源标识) 联合 key 存放解码结果。
 *
 * 为什么 key 要带 maxDimPx：同一 dataUri 可能同时以 256px（列表头像）和
 * 1280px（整屏背景）两种上限解码，若只用 dataUri 做 key 两种尺寸会互相覆盖，
 * 导致背景图拿到头像尺寸的模糊位图（或反之）。
 */
object AvatarBitmapCache {
    // 缓存字节上限常量：约 1/8 可用堆内存，最大 32MB，防止低端机占用过大
    private const val MAX_CACHE_BYTES = 32 * 1024 * 1024
    private const val MEMORY_DIVISOR = 8

    private val cache =
        object : android.util.LruCache<String, Bitmap>(
            (Runtime.getRuntime().maxMemory() / MEMORY_DIVISOR)
                .toInt()
                .coerceAtMost(MAX_CACHE_BYTES)
                .coerceAtLeast(1),
        ) {
            override fun sizeOf(
                key: String,
                value: Bitmap,
            ): Int = value.byteCount
        }

    /** 组合缓存 key：解码上限 + 来源标识 */
    fun keyFor(
        maxDimPx: Int,
        source: String,
    ): String = "${maxDimPx}x|$source"

    /** 按 (dataUri, maxDimPx) 同步查询缓存 */
    fun getSync(
        dataUri: String,
        maxDimPx: Int,
    ): Bitmap? = cache.get(keyFor(maxDimPx, dataUri))

    /** 按 (来源标识, maxDimPx) 同步查询缓存（文件等非 dataUri 来源使用） */
    fun getSyncBySource(
        source: String,
        maxDimPx: Int,
    ): Bitmap? = cache.get(keyFor(maxDimPx, source))

    /** 写入缓存（dataUri 来源） */
    fun put(
        dataUri: String,
        maxDimPx: Int,
        bitmap: Bitmap,
    ) {
        cache.put(keyFor(maxDimPx, dataUri), bitmap)
    }

    /** 写入缓存（文件等非 dataUri 来源） */
    fun putBySource(
        source: String,
        maxDimPx: Int,
        bitmap: Bitmap,
    ) {
        cache.put(keyFor(maxDimPx, source), bitmap)
    }
}
