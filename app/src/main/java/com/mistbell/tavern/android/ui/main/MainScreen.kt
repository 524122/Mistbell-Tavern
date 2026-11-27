package com.mistbell.tavern.android.ui.main

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.ui.character.CharacterListScreen
import com.mistbell.tavern.android.ui.chatlist.ChatListScreen
import com.mistbell.tavern.android.ui.regex.RegexHubScreen
import com.mistbell.tavern.android.ui.settings.ModernSettingsScreen
import com.mistbell.tavern.android.ui.settings.SettingsViewModel
import com.mistbell.tavern.android.ui.settings.UserModeSelectionDialog
import com.mistbell.tavern.android.ui.worldbook.WorldBookListScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Tab 的界面皮（文案与图标）；模式 → 可见性的纯逻辑在 MainTab.kt（零 Android 依赖，可单测） */
private fun MainTab.label(): String =
    when (this) {
        MainTab.CHAT -> "聊天"
        MainTab.CHARACTERS -> "角色"
        MainTab.WORLD_BOOK -> "世界书"
        MainTab.REGEX -> "正则"
        MainTab.SETTINGS -> "设置"
    }

private fun MainTab.icon(): ImageVector =
    when (this) {
        MainTab.CHAT -> Icons.Default.Chat
        MainTab.CHARACTERS -> Icons.Default.Person
        MainTab.WORLD_BOOK -> Icons.Default.Book
        MainTab.REGEX -> Icons.Default.Rule
        MainTab.SETTINGS -> Icons.Default.Settings
    }

@OptIn(
    ExperimentalMaterial3Api::class,
    // calculateWindowSizeClass 为实验 API
    androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi::class,
)
@Suppress("FunctionNaming", "LongMethod", "LongParameterList") // Compose 屏幕级组件的既有形态（原由 detekt 基线吸收）
@Composable
fun MainScreen(
    onChatClick: (sessionId: String, characterId: String) -> Unit,
    onNewChatClick: () -> Unit,
    onEditCharacter: (String) -> Unit,
    onNewCharacter: () -> Unit,
    onNavigateToPromptManagement: () -> Unit = {},
    onNavigateToRulePacks: () -> Unit = {},
    onNavigateToExtensionPacks: () -> Unit = {},
    onNavigateToQuickReplies: () -> Unit = {},
    onNavigateToModelParams: () -> Unit = {},
    onNavigateToWorldBookDetail: (String) -> Unit,
    onNavigateToChatSetup: (String) -> Unit,
    onNavigateToVersionChangelog: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onNavigateToThemeManager: () -> Unit = {},
    onOpenGlobalRegex: () -> Unit = {},
    onOpenCharacterRegex: (String) -> Unit = {},
) {
    val app = LocalContext.current.applicationContext as android.app.Application
    val factory = ViewModelProvider.AndroidViewModelFactory.getInstance(app)
    val scope = rememberCoroutineScope()

    // 界面模式（P3 第 2 项「按模式收拢界面」）：主导航层自持一份 SettingsViewModel，
    // 既驱动 Tab 收拢（visibleMainTabs），也供世界书 / 设置 Tab 复用（同一实例）。
    // uiMode 是进程级常驻流 AppContainer.uiMode 的直通暴露（StateFlow<UiMode?>，Eagerly 不停）：
    // null = 模式未知，此时 visibleTabs 为空（导航栏空一帧）且选中回落聊天——
    // 不渲染随模式变化的 Tab，与执行闸门「未知不放行」同一口径（docs/UI_MODE.md §1）
    val settingsViewModel: SettingsViewModel = viewModel(factory = factory)
    val uiMode by settingsViewModel.uiMode.collectAsState()

    // 选中 Tab 存稳定 id（MainTab.SAVE_KEY 是新保存键；旧版本存的是下标 0–4，
    // 恢复出未知 id 一律回落聊天），显示层按模式回落（settledMainTab）
    var selectedTabId by rememberSaveable(key = MainTab.SAVE_KEY) { mutableStateOf(MainTab.CHAT.id) }
    val visibleTabs = visibleMainTabs(uiMode)
    val selectedTab = settledMainTab(MainTab.fromId(selectedTabId), uiMode)

    // 升级清零一次性提示：挂在主导航层，覆盖横竖屏两种形态下的首页。
    // 点击外部/返回不允许绕过确认——"1" 标记保留，下次启动继续提示，保证用户一定看到升级说明
    val noticeViewModel: SessionMemoryResetNoticeViewModel = viewModel(factory = factory)
    val showResetNotice by noticeViewModel.showNotice.collectAsState()
    if (showResetNotice) {
        AlertDialog(
            onDismissRequest = { /* 仅"知道了"确认后清除标记，见 ViewModel 注释 */ },
            title = { Text("记忆系统已升级") },
            text = {
                Text(
                    "本次升级重构了记忆系统：历史记忆已全部清空，此后每个会话的记忆相互独立，" +
                        "不会同步到其他聊天。聊天消息、角色和世界书均不受影响。",
                )
            },
            confirmButton = {
                TextButton(onClick = { noticeViewModel.dismissNotice() }) {
                    Text("知道了")
                }
            },
        )
    }

    // 升级首启模式选择（P3 第 2 项，一次性）：弹层内容复用 UserModeSelectionDialog（含规划
    // 要求的两句说明）。卡片点击只改本地选中；「完成」/点外部/返回都视为确认当前选中——
    // 未动过时即预选项，因此「关掉 = 保持预选项」（绝不悄悄变成简洁）
    val promptViewModel: UiModePromptViewModel = viewModel(factory = factory)
    val promptPreselect by promptViewModel.pendingPrompt.collectAsState()
    if (promptPreselect != null) {
        var selectedMode by remember(promptPreselect) { mutableStateOf(promptPreselect!!) }
        UserModeSelectionDialog(
            currentMode = selectedMode,
            onModeSelected = { selectedMode = it },
            onDismiss = { promptViewModel.settle(selectedMode) },
        )
    }

    // 自适应：宽度非 Compact（横屏手机/平板/折叠屏展开）时用左侧 NavigationRail，
    // 竖屏手机维持底部 NavigationBar
    val windowSizeClass = calculateWindowSizeClass(LocalContext.current as Activity)
    val useNavigationRail = windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact
    // tab 内容（bottomPadding：底部导航占用的高度；Rail 模式下为 0）。
    // 直接切换当前页面，避免 AnimatedContent 在首帧同时建立动画测量层。
    val tabContent: @Composable (Modifier) -> Unit = { contentModifier ->
        when (selectedTab) {
            MainTab.CHAT ->
                ChatListScreen(
                    onChatClick = onChatClick,
                    onNewChatClick = onNewChatClick,
                    modifier = contentModifier,
                )
            MainTab.CHARACTERS ->
                CharacterListScreen(
                    onCharacterClick = { character ->
                        scope.launch {
                            val latestSession =
                                withContext(Dispatchers.IO) {
                                    (app as TavernApplication).database.sessionDao()
                                        .getLatestByCharacter("local-user", character.id)
                                }

                            if (latestSession != null) {
                                android.util.Log.d(
                                    "MainScreen",
                                    "Opening latest session: ${latestSession.id} for character: ${character.id}",
                                )
                                onChatClick(latestSession.id, character.id)
                            } else {
                                android.util.Log.d("MainScreen", "No session, navigating to chat setup for character: ${character.id}")
                                onNavigateToChatSetup(character.id)
                            }
                        }
                    },
                    onEditCharacter = onEditCharacter,
                    onNewCharacter = onNewCharacter,
                    showTopBarBackButton = false,
                    modifier = contentModifier,
                )
            MainTab.WORLD_BOOK -> {
                // 世界书 Tab：顶栏齿轮唤起「世界书全局参数」底部弹层（复用主导航层的 SettingsViewModel）
                WorldBookListScreen(
                    showBackButton = false,
                    onBookClick = { bookId -> onNavigateToWorldBookDetail(bookId) },
                    settingsViewModel = settingsViewModel,
                    modifier = contentModifier,
                )
            }
            MainTab.REGEX ->
                RegexHubScreen(
                    onOpenGlobalRegex = onOpenGlobalRegex,
                    onOpenCharacterRegex = onOpenCharacterRegex,
                    modifier = contentModifier,
                )
            MainTab.SETTINGS ->
                ModernSettingsScreen(
                    onBack = null,
                    onNavigateToThemeManager = onNavigateToThemeManager,
                    onNavigateToPromptManagement = onNavigateToPromptManagement,
                    onNavigateToRulePacks = onNavigateToRulePacks,
                    onNavigateToExtensionPacks = onNavigateToExtensionPacks,
                    onNavigateToQuickReplies = onNavigateToQuickReplies,
                    onNavigateToModelParams = onNavigateToModelParams,
                    onNavigateToVersionChangelog = onNavigateToVersionChangelog,
                    onNavigateToAbout = onNavigateToAbout,
                    viewModel = settingsViewModel,
                    modifier = contentModifier,
                )
        }
    }

    if (useNavigationRail) {
        // 横屏导航栏只避让真正位于起始边的前摄安全区，避免内容被遮挡。
        // 内容区单独消费末端安全区，避免把导航栏宽度重复算进空白。
        Row(
            modifier = Modifier.fillMaxSize(),
        ) {
            NavigationRail(
                modifier =
                    Modifier
                        // Keep the rail outside a left-side camera cutout, but keep the
                        // rail itself compact so the cutout and rail width do not create
                        // an unnecessarily large blank column.
                        .windowInsetsPadding(
                            WindowInsets.displayCutout.only(WindowInsetsSides.Start),
                        )
                        .width(72.dp)
                        .padding(vertical = 8.dp),
                // The rail owns vertical system-bar handling; the explicit horizontal
                // cutout padding above is applied only when a left-side cutout exists.
                windowInsets = NavigationRailDefaults.windowInsets.only(WindowInsetsSides.Vertical),
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp, androidx.compose.ui.Alignment.Top),
                ) {
                    visibleTabs.forEach { tab ->
                        NavigationRailItem(
                            modifier = Modifier.width(64.dp).height(68.dp),
                            selected = tab == selectedTab,
                            onClick = { selectedTabId = tab.id },
                            icon = { Icon(tab.icon(), contentDescription = null) },
                            label = { Text(tab.label()) },
                            colors =
                                NavigationRailItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                        )
                    }
                }
            }
            tabContent(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .windowInsetsPadding(
                        WindowInsets.displayCutout.only(WindowInsetsSides.End),
                    ),
            )
        }
    } else {
        Scaffold(
            bottomBar = {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.background,
                    tonalElevation = 0.dp,
                ) {
                    visibleTabs.forEach { tab ->
                        NavigationBarItem(
                            selected = tab == selectedTab,
                            onClick = { selectedTabId = tab.id },
                            icon = { Icon(tab.icon(), contentDescription = null) },
                            label = { Text(tab.label()) },
                            colors =
                                NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                        )
                    }
                }
            },
        ) { paddingValues ->
            tabContent(
                Modifier
                    .fillMaxSize()
                    .padding(bottom = paddingValues.calculateBottomPadding()),
            )
        }
    }
}
