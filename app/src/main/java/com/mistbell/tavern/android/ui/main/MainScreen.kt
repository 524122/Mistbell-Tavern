package com.mistbell.tavern.android.ui.main

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.ui.character.CharacterListScreen
import com.mistbell.tavern.android.ui.chatlist.ChatListScreen
import com.mistbell.tavern.android.ui.settings.SettingsScreen
import com.mistbell.tavern.android.ui.settings.SettingsViewModel
import com.mistbell.tavern.android.ui.worldbook.WorldBookListScreen
import kotlinx.coroutines.launch

@Composable
fun MainScreen(
    onChatClick: (sessionId: String, characterId: String) -> Unit,
    onNewChatClick: () -> Unit,
    onEditCharacter: (String) -> Unit,
    onNewCharacter: () -> Unit,
    onNavigateToProviderList: () -> Unit,
    onNavigateToWorldBookList: () -> Unit,
    onNavigateToWorldBookDetail: (String) -> Unit,
    onNavigateToMemoryList: () -> Unit,
    onNavigateToPromptPreview: () -> Unit,
    onNavigateToChatSetup: (String) -> Unit,
    onNavigateToVersionChangelog: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onNavigateToThemeManager: () -> Unit = {},
) {
    var selectedTab by rememberSaveable { mutableStateOf(0) }

    val app = LocalContext.current.applicationContext as android.app.Application
    val factory = ViewModelProvider.AndroidViewModelFactory.getInstance(app)
    val scope = rememberCoroutineScope()
    val database = remember { (app as TavernApplication).database }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = {
                        Icon(Icons.Default.Chat, contentDescription = null)
                    },
                    label = {
                        Text("聊天")
                    },
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = {
                        Icon(Icons.Default.Person, contentDescription = null)
                    },
                    label = {
                        Text("角色")
                    },
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = {
                        Icon(Icons.Default.Book, contentDescription = null)
                    },
                    label = {
                        Text("世界书")
                    },
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = {
                        Icon(Icons.Default.Settings, contentDescription = null)
                    },
                    label = {
                        Text("设置")
                    },
                )
            }
        },
    ) { paddingValues ->
        androidx.compose.animation.AnimatedContent(
            targetState = selectedTab,
            transitionSpec = {
                val enter =
                    if (targetState > initialState) {
                        // 向右滑动：新页面从右边进入
                        androidx.compose.animation.slideInHorizontally { width -> width } +
                            androidx.compose.animation.fadeIn()
                    } else {
                        // 向左滑动：新页面从左边进入
                        androidx.compose.animation.slideInHorizontally { width -> -width } +
                            androidx.compose.animation.fadeIn()
                    }

                val exit =
                    if (targetState > initialState) {
                        androidx.compose.animation.slideOutHorizontally { width -> -width } +
                            androidx.compose.animation.fadeOut()
                    } else {
                        androidx.compose.animation.slideOutHorizontally { width -> width } +
                            androidx.compose.animation.fadeOut()
                    }

                androidx.compose.animation.ContentTransform(enter, exit)
            },
            label = "tab_transition",
        ) { tab ->
            when (tab) {
                0 ->
                    ChatListScreen(
                        onChatClick = onChatClick,
                        onNewChatClick = onNewChatClick,
                        showBottomBar = false,
                        showTopBar = false,
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(bottom = paddingValues.calculateBottomPadding()),
                    )
                1 ->
                    CharacterListScreen(
                        onCharacterClick = { character ->
                            scope.launch {
                                val latestSession =
                                    database.sessionDao()
                                        .getLatestByCharacter("local-user", character.id)

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
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(bottom = paddingValues.calculateBottomPadding()),
                    )
                2 ->
                    WorldBookListScreen(
                        showBackButton = false,
                        onBookClick = { bookId -> onNavigateToWorldBookDetail(bookId) },
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(bottom = paddingValues.calculateBottomPadding()),
                    )
                3 -> {
                    val settingsViewModel: SettingsViewModel = viewModel(factory = factory)
                    SettingsScreen(
                        viewModel = settingsViewModel,
                        onNavigateToProviderList = onNavigateToProviderList,
                        onNavigateToWorldBookList = { selectedTab = 2 }, // 切换到世界书标签页
                        onNavigateToMemoryList = onNavigateToMemoryList,
                        onNavigateToPromptPreview = onNavigateToPromptPreview,
                        onNavigateToVersionChangelog = onNavigateToVersionChangelog,
                        onNavigateToAbout = onNavigateToAbout,
                        onNavigateToThemeManager = onNavigateToThemeManager,
                        showBackButton = false,
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(bottom = paddingValues.calculateBottomPadding()),
                    )
                }
            }
        }
    }
}
