package com.mistbell.tavern.android.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mistbell.tavern.android.BuildConfig
import com.mistbell.tavern.android.data.model.ChangeItem
import com.mistbell.tavern.android.data.model.VersionInfo
import com.mistbell.tavern.android.ui.common.ModernTopBar
import com.mistbell.tavern.android.ui.common.TavernRadius
import com.mistbell.tavern.android.ui.utils.clearFocusOnTap

@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名
@Composable
fun VersionChangelogScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    val changelog by viewModel.changelog.collectAsState()
    val isLoading by viewModel.isLoadingChangelog.collectAsState()

    android.util.Log.d("VersionChangelogScreen", "页面已创建，isLoading=$isLoading, changelog.size=${changelog.size}")

    // 页面加载时获取数据
    LaunchedEffect(Unit) {
        android.util.Log.d("VersionChangelogScreen", "LaunchedEffect 触发")
        if (changelog.isEmpty()) {
            android.util.Log.d("VersionChangelogScreen", "开始加载 changelog")
            viewModel.loadChangelog()
        }
    }

    Scaffold(
        modifier =
            modifier
                .fillMaxSize()
                .clearFocusOnTap(),
        topBar = {
            ModernTopBar(
                title = "版本日志",
                subtitle = "当前版本 v${BuildConfig.VERSION_NAME}",
                onBack = onBack,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->
        if (isLoading) {
            // 加载指示器
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            // 版本列表
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(changelog) { versionInfo ->
                    VersionCard(versionInfo)
                }

                // 底部留白
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun VersionCard(versionInfo: VersionInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(TavernRadius.S),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 版本号和日期
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "v${versionInfo.version}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = versionInfo.releaseDate,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 更新内容列表
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                versionInfo.changes.forEach { change ->
                    ChangeItemRow(change)
                }
            }
        }
    }
}

@Composable
private fun ChangeItemRow(change: ChangeItem) {
    val (icon, color) =
        when (change.type) {
            "feature" -> "●" to MaterialTheme.colorScheme.primary
            "fix" -> "●" to MaterialTheme.colorScheme.tertiary
            "improvement" -> "▲" to MaterialTheme.colorScheme.errorContainer
            "chore" -> "●" to MaterialTheme.colorScheme.onSurfaceVariant
            else -> "●" to MaterialTheme.colorScheme.onSurfaceVariant
        }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = icon,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            modifier = Modifier.width(16.dp),
        )
        Text(
            text = change.description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}
