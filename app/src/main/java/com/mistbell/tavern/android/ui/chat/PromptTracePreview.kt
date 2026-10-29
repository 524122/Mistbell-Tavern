package com.mistbell.tavern.android.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mistbell.tavern.android.data.prompt.PromptBuilder
import com.mistbell.tavern.android.data.prompt.PromptBuilder.PromptTrace

/**
 * 提示词预览底部抽屉。
 *
 * 内容与真实请求**同一次装配**（PromptBuilder.buildPromptTrace），因此这里看到的就是
 * 发给模型的那份，逐字节一致——不存在"预览一份、发送另一份"的漂移。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptTraceSheet(
    trace: PromptTrace?,
    requestParams: List<Pair<String, String>>,
    isLoading: Boolean,
    error: String?,
    callbacks: PromptTraceSheetCallbacks,
) {
    var showFullText by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = callbacks.onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        PromptTraceHeader(
            trace = trace,
            showFullText = showFullText,
            onShowFullTextChange = { showFullText = it },
            onCopyAll = callbacks.onCopyAll,
        )
        PromptTraceContent(
            trace = trace,
            requestParams = requestParams,
            isLoading = isLoading,
            error = error,
            showFullText = showFullText,
        )
    }
}

@Composable
private fun PromptTraceHeader(
    trace: PromptTrace?,
    showFullText: Boolean,
    onShowFullTextChange: (Boolean) -> Unit,
    onCopyAll: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "本次提示词",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                trace?.let {
                    Text(
                        text =
                            "${it.segments.size} 条消息 · 约 ${it.totalEstimatedTokens} tokens" +
                                if (it.truncationReasons.isNotEmpty()) " · 已裁剪" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (trace != null) {
                TextButton(onClick = onCopyAll) { Text("复制全文") }
            }
        }
        if (trace != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = !showFullText,
                    onClick = { onShowFullTextChange(false) },
                    label = { Text("按消息") },
                )
                FilterChip(
                    selected = showFullText,
                    onClick = { onShowFullTextChange(true) },
                    label = { Text("完整文本") },
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun PromptTraceContent(
    trace: PromptTrace?,
    requestParams: List<Pair<String, String>>,
    isLoading: Boolean,
    error: String?,
    showFullText: Boolean,
) {
    val listState = rememberLazyListState()
    when {
        isLoading -> {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(modifier = Modifier.size(28.dp)) }
        }
        error != null -> {
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(24.dp),
            )
        }
        trace == null -> {
            Text(
                text = "暂无数据",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        }
        else -> {
            PromptTraceList(
                trace = trace,
                requestParams = requestParams,
                showFullText = showFullText,
                listState = listState,
            )
        }
    }
}

@Composable
private fun PromptTraceList(
    trace: PromptTrace,
    requestParams: List<Pair<String, String>>,
    showFullText: Boolean,
    listState: LazyListState,
) {
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (showFullText) {
            item(key = "__full_text__") {
                FullPromptTextBlock(trace = trace)
            }
        } else {
            if (requestParams.isNotEmpty()) {
                item(key = "__params__") {
                    RequestParamsSection(params = requestParams)
                }
            }
            items(
                count = trace.segments.size,
                key = { index -> "prompt-${trace.segments[index].message.role}-$index" },
                contentType = { "prompt-segment" },
            ) { index ->
                PromptSegmentCard(
                    segment = trace.segments[index],
                    ordinal = index + 1,
                    tokens = trace.tokensOf(trace.segments[index]),
                )
            }
        }
        if (trace.truncationReasons.isNotEmpty() || trace.protocolConversionResults.isNotEmpty()) {
            item(key = "__trace_meta__") {
                TraceMetadataSection(trace)
            }
        }
        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
    Spacer(modifier = Modifier.navigationBarsPadding().height(12.dp))
}

/** 完整文本视图：按真实发送顺序展示 role、来源和全部正文。 */
@Composable
private fun FullPromptTextBlock(trace: PromptTrace) {
    val fullText =
        remember(trace) {
            buildString {
                trace.segments.forEachIndexed { index, segment ->
                    if (index > 0) appendLine("\n")
                    appendLine("─── #${index + 1} [${segment.message.role}] ${segment.source} ───")
                    appendLine(segment.message.content)
                }
            }
        }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
            ),
    ) {
        SelectionContainer {
            Text(
                text = fullText,
                modifier = Modifier.padding(14.dp),
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 19.sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * 请求参数折叠区：本次请求实际生效的采样/请求参数。
 *
 * 取值与真实请求同源（`SettingsRepository.getLlmConfig` 解析后的结果），排查
 * "两次回复差别大"时先看这一栏。密钥只显示"是否已配置"，不显示内容。
 */
@Composable
private fun RequestParamsSection(params: List<Pair<String, String>>) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
            ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "请求参数",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "收起请求参数" else "展开请求参数",
                    modifier = Modifier.size(20.dp),
                )
            }
            if (expanded) {
                Spacer(modifier = Modifier.height(6.dp))
                params.forEach { (key, value) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = key,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(132.dp),
                        )
                        Text(
                            text = value,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/** 单条提示词消息卡：序号 + role 徽标 + 来源标签 + 估算 token + 可展开全文 */
@Composable
private fun PromptSegmentCard(
    segment: PromptBuilder.Segment,
    ordinal: Int,
    tokens: Int,
) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            PromptSegmentHeader(
                segment = segment,
                ordinal = ordinal,
                tokens = tokens,
                expanded = expanded,
                onExpandedChange = { expanded = it },
            )
            val metadata =
                listOfNotNull(
                    segment.activationReason?.let { "激活：$it" },
                    segment.insertionPosition?.let { "位置：$it" },
                    segment.truncationReason?.let { "裁剪：$it" },
                    segment.protocolConversionResult?.let { "协议：$it" },
                )
            if (metadata.isNotEmpty()) {
                Text(
                    text = metadata.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Text(
                text = segment.message.content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.clickable { expanded = !expanded },
            )
        }
    }
}

@Composable
private fun PromptSegmentHeader(
    segment: PromptBuilder.Segment,
    ordinal: Int,
    tokens: Int,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    val roleColor =
        when (segment.message.role) {
            "user" -> MaterialTheme.colorScheme.tertiary
            "assistant" -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.secondary
        }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "$ordinal",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(24.dp),
        )
        Text(
            text = segment.message.role,
            style = MaterialTheme.typography.labelMedium,
            color = roleColor,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = segment.source,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        Text(
            text = "~$tokens",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(
            onClick = { onExpandedChange(!expanded) },
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun TraceMetadataSection(trace: PromptTrace) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f),
            ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "最终请求：${trace.segments.size} 条消息，估算输入 ${trace.totalEstimatedTokens} token",
                style = MaterialTheme.typography.labelSmall,
            )
            trace.truncationReasons.forEach { Text("裁剪：$it", style = MaterialTheme.typography.labelSmall) }
            trace.protocolConversionResults.forEach { Text("协议：$it", style = MaterialTheme.typography.labelSmall) }
        }
    }
}
