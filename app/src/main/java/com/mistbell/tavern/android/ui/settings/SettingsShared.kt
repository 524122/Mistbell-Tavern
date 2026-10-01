package com.mistbell.tavern.android.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 设置页通用卡片容器（internal：同包 SettingsPromptCards / SettingsVectorMemoryCards 复用）。
 * 原位于 SettingsScreen.kt，双轨 UI 收敛时迁出。
 */
@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名
@Composable
internal fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    SettingsCard(title = null, content = content)
}

/** 与 ModernSettingsGroup 对齐的设置卡片，支持需要单独分组标题的复杂区块。 */
@Suppress("FunctionNaming")
@Composable
internal fun SettingsCard(
    title: String?,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        if (!title.isNullOrBlank()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 2.dp, bottom = 8.dp),
            )
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            border =
                androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                ),
        ) {
            Column(
                modifier = Modifier.padding(vertical = 8.dp),
                content = content,
            )
        }
    }
}

/**
 * 设置页导航行（internal：同包 SettingsPromptCards 复用）。原位于 SettingsScreen.kt。
 */
@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名
@Composable
internal fun SettingsNavItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onClick() },
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 14.dp, horizontal = 4.dp),
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
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
