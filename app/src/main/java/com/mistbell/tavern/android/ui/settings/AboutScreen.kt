package com.mistbell.tavern.android.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mistbell.tavern.android.BuildConfig
import com.mistbell.tavern.android.ui.common.ModernSettingsGroup
import com.mistbell.tavern.android.ui.common.ModernSettingsItem
import com.mistbell.tavern.android.ui.common.ModernTopBar
import com.mistbell.tavern.android.ui.utils.clearFocusOnTap

@Suppress("FunctionNaming", "LongMethod") // Compose 屏幕级组件的既有形态（原由 detekt 基线吸收）
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onNavigateToVersionChangelog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            context.startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.e("AboutScreen", "无法打开链接: $url", e)
        }
    }

    Scaffold(
        modifier =
            modifier
                .fillMaxSize()
                .clearFocusOnTap(),
        topBar = {
            ModernTopBar(title = "关于", onBack = onBack)
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            // 应用信息头图
            item { AppInfoCard() }

            // 项目信息分组
            item {
                ModernSettingsGroup(title = "项目信息") {
                    ModernSettingsItem(
                        title = "Gitee 仓库",
                        subtitle = "查看源代码",
                        icon = Icons.AutoMirrored.Filled.OpenInNew,
                        onClick = {
                            openUrl("https://gitee.com/Wan2010/LonngMemoryAIChat")
                        },
                    )
                    ModernSettingsItem(
                        title = "开发者",
                        subtitle = "Wan",
                        icon = Icons.Default.Person,
                    )
                }
            }

            // 技术信息分组
            item {
                ModernSettingsGroup(title = "技术信息") {
                    ModernSettingsItem(
                        title = "开发框架",
                        subtitle = "Kotlin + Jetpack Compose",
                        icon = Icons.Default.Code,
                    )
                    ModernSettingsItem(
                        title = "设计系统",
                        subtitle = "Material Design 3",
                        icon = Icons.Default.Palette,
                    )
                    ModernSettingsItem(
                        title = "支持版本",
                        subtitle = "Android 8.0+",
                        icon = Icons.Default.PhoneAndroid,
                    )
                }
            }

            // 更多操作分组
            item {
                ModernSettingsGroup(title = "更多") {
                    ModernSettingsItem(
                        title = "检查更新",
                        subtitle = "查看版本更新日志",
                        icon = Icons.Default.Refresh,
                        onClick = onNavigateToVersionChangelog,
                    )
                }
            }

            // 底部留白
            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun AppInfoCard() {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 应用图标占位
            Box(
                modifier =
                    Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }

            // 应用名称
            Text(
                text = "Mistbell Tavern",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )

            // 版本信息
            Text(
                text = "版本 ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 应用简介
            Text(
                text = "LongMemoryAI 对话助手\n智能角色扮演聊天应用",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
