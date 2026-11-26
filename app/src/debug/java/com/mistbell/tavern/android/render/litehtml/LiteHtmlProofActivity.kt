package com.mistbell.tavern.android.render.litehtml

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import java.io.File
import java.io.FileOutputStream

/**
 * debug 包里的 litehtml 对照页。只渲染固定样本并保存 PNG，不接入聊天。
 * 启动：adb shell am start -n com.mistbell.tavern.android/.render.litehtml.LiteHtmlProofActivity
 */
class LiteHtmlProofActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        val column = verticalColumn(density)
        val outputDir = File(getExternalFilesDir(null), OUTPUT_DIR).apply { mkdirs() }
        samples.forEach { sample ->
            column.addView(label(sample.title, density))
            addRenderResult(column, sample, density, outputDir)
        }
        setContentView(ScrollView(this).apply { addView(column) })
    }

    private fun addRenderResult(
        column: LinearLayout,
        sample: Sample,
        density: Float,
        outputDir: File,
    ) {
        try {
            val bitmap = LiteHtmlRenderer.render(sample.html, WIDTH_CSS_PX, density)
            val file = File(outputDir, "${sample.fileName}.png")
            savePng(bitmap, file)
            Log.i(TAG, "saved ${file.absolutePath} ${bitmap.width}x${bitmap.height}")
            column.addView(bitmapView(bitmap))
            column.addView(label("${bitmap.width}x${bitmap.height} ${file.name}", density))
        } catch (error: RuntimeException) {
            Log.e(TAG, "render failed: ${sample.fileName}", error)
            column.addView(label(error.stackTraceToString(), density))
        }
    }

    private fun verticalColumn(density: Float): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            val pad = (PADDING_DP * density).toInt()
            setPadding(pad, pad, pad, pad)
        }

    private fun label(
        text: String,
        density: Float,
    ): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(Color.BLACK)
            textSize = LABEL_SP
            setPadding(0, (PADDING_DP * density).toInt(), 0, 0)
        }

    private fun bitmapView(bitmap: Bitmap): ImageView =
        ImageView(this).apply {
            setImageBitmap(bitmap)
            setBackgroundColor(PROOF_BACKGROUND)
        }

    private fun savePng(
        bitmap: Bitmap,
        file: File,
    ) {
        FileOutputStream(file).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, output)) {
                "写入 PNG 失败: ${file.name}"
            }
        }
    }

    private data class Sample(
        val fileName: String,
        val title: String,
        val html: String,
    )

    private companion object {
        const val TAG = "LiteHtmlProof"
        const val OUTPUT_DIR = "litehtml-proof"
        const val WIDTH_CSS_PX = 360
        const val PADDING_DP = 16
        const val LABEL_SP = 16f
        const val PNG_QUALITY = 100
        val PROOF_BACKGROUND: Int = Color.parseColor("#F4F1EA")

        val samples =
            listOf(
                Sample(
                    fileName = "table",
                    title = "表格原文",
                    html = "table fallback test: html <table><tr><TD>cell-a</TD></tr></table> end",
                ),
                Sample(
                    fileName = "style-div",
                    title = "style 规则写在 p 上，文字在 div.c",
                    html = "<style>p{color:red}</style><div class=\"c\">hello-native-fallback</div>",
                ),
                Sample(
                    fileName = "style-p",
                    title = "对照：style 的 p 规则命中 p",
                    html = "<style>p{color:red}</style><p>hello-native-fallback</p>",
                ),
            )
    }
}
