package com.mistbell.tavern.android.ui.chat.render

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import com.mistbell.tavern.android.render.litehtml.LiteHtmlPolicy
import com.mistbell.tavern.android.render.litehtml.LiteHtmlRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 用 litehtml 画一段不含脚本的 HTML（style / table）。失败时把原文留在气泡里，不改走 WebView。
 */
@Composable
fun LiteHtmlMessage(
    html: String,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    val colorCss = LiteHtmlPolicy.colorToCss(textColor.toArgb())
    val source = LiteHtmlPolicy.withBubbleTextColor(html, colorCss)
    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current.density
        val widthCssPx =
            if (density > 0f) {
                (constraints.maxWidth / density).roundToInt().coerceAtLeast(1)
            } else {
                0
            }
        val frame =
            produceState<LiteHtmlFrame>(
                initialValue = LiteHtmlFrame.Loading,
                source,
                widthCssPx,
                density,
            ) {
                value =
                    if (widthCssPx <= 0 || html.isEmpty()) {
                        LiteHtmlFrame.Loading
                    } else {
                        withContext(Dispatchers.Default) {
                            // 原生渲染失败必须兜底降级为纯文本气泡，绝不能让聊天页崩溃；
                            // require 校验与 JNI 侧异常形态不定，RuntimeException 已是无法再收窄的边界。
                            @Suppress("TooGenericExceptionCaught")
                            try {
                                LiteHtmlFrame.Ready(LiteHtmlRenderer.render(source, widthCssPx, density))
                            } catch (error: RuntimeException) {
                                Log.e(TAG, "litehtml 渲染失败", error)
                                LiteHtmlFrame.Failed
                            }
                        }
                    }
            }
        when (val rendered = frame.value) {
            LiteHtmlFrame.Loading -> Unit
            is LiteHtmlFrame.Ready ->
                Image(
                    bitmap = rendered.bitmap.asImageBitmap(),
                    contentDescription = html.take(ACCESSIBILITY_TEXT_MAX),
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth,
                )
            LiteHtmlFrame.Failed -> Text(text = html, color = textColor)
        }
    }
}

private sealed interface LiteHtmlFrame {
    data object Loading : LiteHtmlFrame

    data class Ready(val bitmap: Bitmap) : LiteHtmlFrame

    data object Failed : LiteHtmlFrame
}

private const val TAG = "LiteHtmlMessage"
private const val ACCESSIBILITY_TEXT_MAX = 300
