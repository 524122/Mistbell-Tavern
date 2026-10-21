package com.mistbell.tavern.android.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ElevatedAssistChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mistbell.tavern.android.data.repository.QuickReplyRepository

/**
 * 快捷回复栏：输入区上方横向滚动的按钮行。
 * 数据双源（全局自定义 + 启用规则包），规则包来源的按钮带包名下标。
 */
@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名
@Composable
fun QuickReplyBar(
    quickReplies: List<QuickReplyRepository.ActiveQuickReply>,
    enabled: Boolean,
    onClick: (QuickReplyRepository.ActiveQuickReply) -> Unit,
) {
    if (quickReplies.isEmpty()) return
    LazyRow(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(quickReplies, key = { it.id }) { qr ->
            ElevatedAssistChip(
                onClick = { onClick(qr) },
                enabled = enabled,
                // 规则包来源的 QR 加包名下标区分（subtitle 参数在部分 M3 版本不存在，用文案合成）
                label = { Text(if (qr.packName != null) "${qr.label} ⌁${qr.packName}" else qr.label) },
            )
        }
    }
}
