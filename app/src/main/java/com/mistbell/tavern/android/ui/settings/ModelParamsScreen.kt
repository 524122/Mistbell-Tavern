package com.mistbell.tavern.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mistbell.tavern.android.data.api.ApiConfigDocument
import com.mistbell.tavern.android.data.api.ApiConfigJson
import com.mistbell.tavern.android.data.api.ApiConfigJsonResult
import com.mistbell.tavern.android.data.api.ApiType
import com.mistbell.tavern.android.data.api.LlmConfig
import com.mistbell.tavern.android.data.api.RequestSamplingParams
import com.mistbell.tavern.android.data.api.normalizeApiType
import com.mistbell.tavern.android.ui.common.ModernDivider
import com.mistbell.tavern.android.ui.common.ModernTopBar
import kotlin.math.roundToInt

private val MAX_TOKENS_STEPS = listOf(256, 512, 1024, 2048, 4096, 8192, 16384, 32768, 65536)

private const val TIMEOUT_MIN_SECONDS = 15
private const val TIMEOUT_MAX_SECONDS = 600
private const val RETRIES_MAX = 5

/**
 * 模型参数独立页面：编辑默认 API 配置的采样参数（request JSON）与超时/重试（runtime）。
 *
 * 与 API 配置编辑面板共用同一份 configJson —— 此处调整参数，面板「请求参数」页的
 * JSON 同步变化；面板里改 JSON 保存后，这里的值也随之更新（同一张数据库表）。
 */
@Suppress("FunctionNaming")
@Composable
fun ModelParamsScreen(
    onBack: () -> Unit,
    viewModel: ApiConfigViewModel = viewModel(),
) {
    val defaultConfig by viewModel.defaultApiConfig.collectAsState()

    Scaffold(
        topBar = {
            ModernTopBar(
                title = "模型参数",
                subtitle = "采样与请求策略 · 作用于默认 API 配置",
                onBack = onBack,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState()),
        ) {
            val config = defaultConfig
            val document =
                remember(config?.configJson) {
                    config?.configJson?.let {
                        (ApiConfigJson.parse(it) as? ApiConfigJsonResult.Valid)?.document
                    }
                }
            when {
                config == null ->
                    SectionNotice("请先在 设置 → AI 连接 中添加 API 配置")
                document == null ->
                    SectionNotice("默认配置的 JSON 无法解析，请在 API 编辑面板中修正")
                else ->
                    ModelParamsEditor(
                        configName = config.name,
                        document = document,
                        onSave = { transform -> viewModel.updateDefaultConfigDocument(transform) },
                    )
            }
        }
    }
}

@Suppress("FunctionNaming", "LongMethod") // 表单区块的既有形态；参数行均为声明式控件
@Composable
private fun ModelParamsEditor(
    configName: String,
    document: ApiConfigDocument,
    onSave: ((ApiConfigDocument) -> ApiConfigDocument) -> Unit,
) {
    val type = normalizeApiType(document.type)
    val params = RequestSamplingParams.read(document.request, type)
    val showFrequencyPenalty = type == ApiType.OPENAI || type == ApiType.CUSTOM

    fun saveSampling(next: RequestSamplingParams) =
        onSave { d -> d.copy(request = RequestSamplingParams.write(d.request, type, next)) }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "作用于默认配置：$configName · 与 API 面板 JSON 同步",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "关闭开关 = 未设置（请求体不发送该参数）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        NullableSliderParamRow(
            title = "Temperature",
            subtitle = "采样温度，越高回复越发散（0–2）",
            value = params.temperature,
            defaultValue = LlmConfig.DEFAULT_TEMPERATURE,
            range = 0f..2f,
            onCommit = { v -> saveSampling(params.copy(temperature = v)) },
        )
        ModernDivider()
        NullableSliderParamRow(
            title = "Top P",
            subtitle = "核采样概率上限（0–1）",
            value = params.topP,
            defaultValue = 0.9,
            range = 0f..1f,
            onCommit = { v -> saveSampling(params.copy(topP = v)) },
        )
        ModernDivider()
        NullableSliderParamRow(
            title = "Top K",
            subtitle = "候选词数量上限（1–100，整数）",
            value = params.topK?.toDouble(),
            defaultValue = 40.0,
            range = 1f..100f,
            integer = true,
            onCommit = { v -> saveSampling(params.copy(topK = v?.roundToInt())) },
        )
        if (showFrequencyPenalty) {
            ModernDivider()
            NullableSliderParamRow(
                title = "Frequency Penalty",
                subtitle = "重复惩罚，正值抑制复读（-2–2）",
                value = params.frequencyPenalty,
                defaultValue = 0.0,
                range = -2f..2f,
                onCommit = { v -> saveSampling(params.copy(frequencyPenalty = v)) },
            )
        }
        ModernDivider()
        MaxTokensParamRow(
            value = params.maxTokens,
            onCommit = { v -> saveSampling(params.copy(maxTokens = v)) },
        )
        ModernDivider()
        TimeoutRetriesRows(
            document = document,
            onSave = onSave,
        )
    }
}

/** 可置空（未设置不发送）的滑块参数行：开关控制是否发送，拖动结束才落库。 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
private fun NullableSliderParamRow(
    title: String,
    subtitle: String,
    value: Double?,
    defaultValue: Double,
    range: ClosedFloatingPointRange<Float>,
    integer: Boolean = false,
    onCommit: (Double?) -> Unit,
) {
    var dragValue by remember(value) { mutableStateOf<Float?>(null) }
    val shown = dragValue?.toDouble() ?: value

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = shown?.let { formatParamValue(it, integer) } ?: "未设置",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Switch(
                checked = value != null,
                onCheckedChange = { enabled -> onCommit(if (enabled) defaultValue else null) },
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        if (value != null) {
            Slider(
                value = (shown ?: defaultValue).toFloat().coerceIn(range.start, range.endInclusive),
                onValueChange = { dragValue = it },
                onValueChangeFinished = {
                    // dragValue 留给 remember(value) 在落库回读时重置，避免滑块回跳闪烁
                    onCommit(dragValue?.toDouble()?.let { if (integer) it.roundToInt().toDouble() else round2(it) })
                },
                valueRange = range,
            )
        }
    }
}

/** max_tokens 档位滑块：256–65536，未设置 = 走协议默认输出预算。 */
@Suppress("FunctionNaming")
@Composable
private fun MaxTokensParamRow(
    value: Int?,
    onCommit: (Int?) -> Unit,
) {
    var dragIndex by remember(value) { mutableStateOf<Int?>(null) }
    val shownIndex = dragIndex ?: value?.let { maxTokensStepIndex(it) }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Max Tokens",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "模型单次回复的输出预算",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = shownIndex?.let { "${MAX_TOKENS_STEPS[it]}" } ?: "未设置",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Switch(
                checked = value != null,
                onCheckedChange = { enabled -> onCommit(if (enabled) LlmConfig.DEFAULT_MAX_TOKENS else null) },
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        if (value != null) {
            Slider(
                value = (shownIndex ?: maxTokensStepIndex(LlmConfig.DEFAULT_MAX_TOKENS)).toFloat(),
                onValueChange = { dragIndex = it.roundToInt().coerceIn(0, MAX_TOKENS_STEPS.lastIndex) },
                onValueChangeFinished = {
                    onCommit(dragIndex?.let { MAX_TOKENS_STEPS[it] })
                },
                valueRange = 0f..MAX_TOKENS_STEPS.lastIndex.toFloat(),
                steps = MAX_TOKENS_STEPS.size - 2,
            )
        }
    }
}

/** runtime 请求策略：超时（15–600 秒）与重试（0–5 次），始终随文档发送。 */
@Suppress("FunctionNaming")
@Composable
private fun TimeoutRetriesRows(
    document: ApiConfigDocument,
    onSave: ((ApiConfigDocument) -> ApiConfigDocument) -> Unit,
) {
    var timeoutText by remember(document.runtime.timeoutSeconds) {
        mutableStateOf(document.runtime.timeoutSeconds.toString())
    }
    var dragRetries by remember(document.runtime.retries) { mutableStateOf<Float?>(null) }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "请求超时",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "单次请求最长等待时间（$TIMEOUT_MIN_SECONDS–$TIMEOUT_MAX_SECONDS 秒）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = timeoutText,
                onValueChange = { input ->
                    if (input.all { it.isDigit() }) {
                        timeoutText = input.take(4)
                        input.toIntOrNull()?.let { seconds ->
                            if (seconds in TIMEOUT_MIN_SECONDS..TIMEOUT_MAX_SECONDS) {
                                onSave { d -> d.copy(runtime = d.runtime.copy(timeoutSeconds = seconds)) }
                            }
                        }
                    }
                },
                modifier = Modifier.width(96.dp),
                singleLine = true,
                trailingIcon = { Text("秒", style = MaterialTheme.typography.labelMedium) },
                shape = RoundedCornerShape(8.dp),
                colors =
                    OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                    ),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "重试次数",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "请求失败后的自动重试上限（0–$RETRIES_MAX 次）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = (dragRetries?.roundToInt() ?: document.runtime.retries).toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = dragRetries ?: document.runtime.retries.toFloat(),
            onValueChange = { dragRetries = it },
            onValueChangeFinished = {
                onSave { d ->
                    d.copy(
                        runtime =
                            d.runtime.copy(
                                retries = dragRetries?.roundToInt()?.coerceIn(0, RETRIES_MAX) ?: d.runtime.retries,
                            ),
                    )
                }
            },
            valueRange = 0f..RETRIES_MAX.toFloat(),
            steps = RETRIES_MAX - 1,
        )
    }
}

@Suppress("FunctionNaming")
@Composable
private fun SectionNotice(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun maxTokensStepIndex(value: Int): Int =
    MAX_TOKENS_STEPS
        .indexOfFirst { it >= value }
        .coerceAtLeast(0)

private fun round2(value: Double): Double = (value * 100).roundToInt() / 100.0

private fun formatParamValue(
    value: Double,
    integer: Boolean,
): String =
    if (integer) {
        value.roundToInt().toString()
    } else {
        val rounded = round2(value)
        if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
    }
