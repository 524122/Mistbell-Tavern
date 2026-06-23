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
        maxSize: Int = 0,  // 改为0表示不缩放，保留原图
        quality: Int = 85
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
            val finalBitmap = if (maxSize > 0 && (rotatedBitmap.width > maxSize || rotatedBitmap.height > maxSize)) {
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
    private fun fixImageRotation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        return try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            val exif = ExifInterface(inputStream!!)
            inputStream.close()

            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )

            val rotation = when (orientation) {
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
    private fun resizeBitmap(bitmap: Bitmap, maxSize: Int): Bitmap {
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
    private fun bitmapToBase64(bitmap: Bitmap, quality: Int): String {
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
}
