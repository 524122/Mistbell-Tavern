package com.mistbell.tavern.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mistbell.tavern.android.data.vector.VectorStatistics
import java.util.Locale

/** S2 向量记忆召回设置：记忆源（自动/API/本地 ONNX）+ 召回条数 + 相似度阈值 */
@Suppress("FunctionNaming")
@Composable
internal fun VectorMemorySettingsCard(viewModel: SettingsViewModel) {
    val embeddingSource by viewModel.embeddingSource.collectAsState()
    val recallTopK by viewModel.memoryRecallTopK.collectAsState()
    val threshold by viewModel.memorySimilarityThreshold.collectAsState()
    val rebuildProgress by viewModel.vectorRebuildProgress.collectAsState()
    val indexUsage by viewModel.vectorIndexUsage.collectAsState()
    val isBusy by viewModel.isLoading.collectAsState()

    SettingsCard(title = "向量记忆") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "向量记忆（语义召回）",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )

            EmbeddingSourceChips(
                embeddingSource = embeddingSource,
                onSelect = { viewModel.setEmbeddingSource(it) },
            )

            LabeledSlider(
                label = "召回条数：$recallTopK",
                value = recallTopK.toFloat(),
                valueRange = 1f..20f,
                steps = 18,
                onValueChange = { viewModel.setMemoryRecallTopK(it.toInt()) },
            )

            LabeledSlider(
                label = "相似度阈值：${(threshold * 100).toInt()}%",
                value = threshold,
                valueRange = 0.05f..0.95f,
                steps = 17,
                onValueChange = { viewModel.setMemorySimilarityThreshold(it) },
            )

            Text(
                text =
                    "「本地 ONNX」（bge-small-zh，约 24MB 模型随应用内置）无需 API Key 即可使用语义记忆；" +
                        "切换记忆源后旧模型的向量会被自动清理——不同模型的向量不在同一空间，不会混搜。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            VectorIndexUsageLine(usage = indexUsage)

            VectorIndexRebuildSection(
                isBusy = isBusy,
                progress = rebuildProgress,
                onRebuild = { viewModel.rebuildVectorIndex() },
            )
        }
    }
}

/**
 * 索引占用行：条数 + 占盘大小 + 预算。
 *
 * 磁盘容量治理必须能被看见——不显示占用，用户只会看到"想不起来以前的事"，
 * 却不知道是索引满到在淘汰，也不知道离满还有多远。
 */
@Suppress("FunctionNaming")
@Composable
private fun VectorIndexUsageLine(usage: VectorStatistics?) {
    if (usage == null) return

    val budgetText =
        if (usage.budgetBytes > 0) {
            "${formatBytes(usage.bytes)} / ${formatBytes(usage.budgetBytes)}"
        } else {
            formatBytes(usage.bytes)
        }
    val nearFull = usage.budgetBytes > 0 && usage.bytes * PERCENT_FULL >= usage.budgetBytes * BUDGET_WARN_PERCENT

    Text(
        text = "索引占用：${usage.totalVectors} 条 · $budgetText" + if (nearFull) "（接近上限，旧内容会被淘汰）" else "",
        style = MaterialTheme.typography.bodySmall,
        color =
            if (nearFull) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
    )
}

/** 人类可读的字节数（索引体积从 KB 到几十 MB，一律保留一位小数足够） */
private fun formatBytes(bytes: Long): String =
    when {
        bytes >= BYTES_PER_MB -> String.format(Locale.ROOT, "%.1f MB", bytes / BYTES_PER_MB.toDouble())
        bytes >= BYTES_PER_KB -> String.format(Locale.ROOT, "%.1f KB", bytes / BYTES_PER_KB.toDouble())
        else -> "$bytes B"
    }

/** 占用达到预算的这个百分比时提示"接近上限" */
private const val BUDGET_WARN_PERCENT = 90L
private const val PERCENT_FULL = 100L

private const val BYTES_PER_KB = 1024L
private const val BYTES_PER_MB = 1024L * 1024L

@Suppress("FunctionNaming")
@Composable
private fun VectorIndexRebuildSection(
    isBusy: Boolean,
    progress: String?,
    onRebuild: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "索引重建",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = onRebuild,
            enabled = !isBusy,
        ) {
            Text(progress ?: "重建向量索引")
        }
        Text(
            text =
                "索引有容量上限，超出磁盘预算时会按价值淘汰最旧的内容，而被淘汰的向量不会自动重建。" +
                    "刚换过记忆源、或者觉得很久以前聊过的事想不起来时，可以从消息库重新生成一遍" +
                    "（索引已满时会提前停手，先去记忆管理里删掉不再需要的条目）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Suppress("FunctionNaming")
@Composable
private fun EmbeddingSourceChips(
    embeddingSource: String,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "记忆源",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = embeddingSource == "auto",
                onClick = { onSelect("auto") },
                label = { Text("自动") },
            )
            FilterChip(
                selected = embeddingSource == "api",
                onClick = { onSelect("api") },
                label = { Text("API") },
            )
            FilterChip(
                selected = embeddingSource == "local",
                onClick = { onSelect("local") },
                label = { Text("本地 ONNX（实验）") },
            )
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
        )
    }
}
