package com.mistbell.tavern.android.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mistbell.tavern.android.data.repository.ChatSettingsResolver

// 上下文长度档位：全局默认与会话级设置共用同一组预设
@Suppress("MagicNumber") // 档位表本身即一组语义化常量
val contextTokenPresets =
    listOf(
        2048,
        4096,
        8192,
        16384,
        32768,
        65536,
        131072,
        1_000_000,
    )

// 合法区间唯一定义在 ChatSettingsResolver（数据层），此处别名复用避免双源真相
const val CONTEXT_TOKEN_MIN = ChatSettingsResolver.CONTEXT_TOKEN_LIMIT_MIN
const val CONTEXT_TOKEN_MAX = ChatSettingsResolver.CONTEXT_TOKEN_LIMIT_MAX

fun formatTokenLimit(value: Int): String {
    return when {
        value >= CONTEXT_TOKEN_MAX -> "1M"
        value >= CONTEXT_TOKEN_MIN -> "${value / CONTEXT_TOKEN_MIN}K"
        else -> value.toString()
    }
}

fun tokenLimitToSliderValue(tokenLimit: Int): Float {
    val index =
        contextTokenPresets.indexOfFirst { it >= tokenLimit }
            .takeIf { it >= 0 }
            ?: (contextTokenPresets.lastIndex)
    return index.toFloat()
}

fun sliderValueToTokenLimit(value: Float): Int {
    val index = value.toInt().coerceIn(0, contextTokenPresets.lastIndex)
    return contextTokenPresets[index]
}

/**
 * 预设档滑杆 + 档位 FilterChip 行，全局设置页与会话级设置页共用。
 *
 * [value] 可空：null = 跟随全局默认（需配合 [followGlobalEffective] 展示当前生效档位）。
 * [followGlobalEffective] 非 null 时启用"跟随全局"档（会话级页面传入全局当前值；
 * 全局设置页自身即真相源，传 null 不提供该档）。拖动滑杆或点选档位即写出显式值。
 */
@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名
@Composable
fun ContextTokenLimitSelector(
    value: Int?,
    onValueChange: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    followGlobalEffective: Int? = null,
) {
    val followGlobal = followGlobalEffective != null
    Column(modifier = modifier) {
        Slider(
            value = tokenLimitToSliderValue(value ?: followGlobalEffective ?: contextTokenPresets[1]),
            onValueChange = { onValueChange(sliderValueToTokenLimit(it)) },
            valueRange = 0f..contextTokenPresets.lastIndex.toFloat(),
            steps = contextTokenPresets.size - 2,
        )
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (followGlobal) {
                FilterChip(
                    selected = value == null,
                    onClick = { onValueChange(null) },
                    label = { Text("跟随全局") },
                )
            }
            contextTokenPresets.forEach { preset ->
                FilterChip(
                    selected = value == preset,
                    onClick = { onValueChange(preset) },
                    label = { Text(formatTokenLimit(preset)) },
                )
            }
        }
    }
}
