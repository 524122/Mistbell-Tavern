package com.mistbell.tavern.android.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mistbell.tavern.android.ui.chat.ChatScreen
import com.mistbell.tavern.android.ui.chat.ChatViewModel
import com.mistbell.tavern.android.ui.settings.SettingsScreen
import com.mistbell.tavern.android.ui.settings.SettingsViewModel
import com.mistbell.tavern.android.ui.themepack.ThemeManagerScreen
import com.mistbell.tavern.android.ui.themepack.ThemeManagerViewModel
import kotlinx.coroutines.flow.first

object Routes {
    const val MAIN = "main"
    const val CHAT = "chat/{sessionId}/{characterId}"
    const val CHAT_SETUP = "chat_setup?characterId={characterId}"
    const val CHAT_SETTINGS = "chat_settings/{sessionId}"
    const val SETTINGS = "settings"
    const val CHARACTER_EDITOR = "character_editor/{characterId}"
    const val PROVIDER_LIST = "provider_list"
    const val PROVIDER_EDITOR = "provider_editor/{providerId}"
    const val WORLD_BOOK_LIST = "world_book_list"
    const val WORLD_BOOK_DETAIL = "world_book_detail/{bookId}"
    const val MEMORY_LIST = "memory_list?sessionId={sessionId}&characterId={characterId}"
    const val PROMPT_PREVIEW = "prompt_preview"
    const val VERSION_CHANGELOG = "version_changelog"
    const val THEME_MANAGER = "theme_manager"
    const val ABOUT = "about"

    fun chat(sessionId: String, characterId: String) = "chat/$sessionId/$characterId"

    fun chatSettings(sessionId: String) = "chat_settings/$sessionId"

    fun chatSetup(characterId: String? = null) =
        if (characterId != null) "chat_setup?characterId=$characterId" else "chat_setup"

    fun characterEditor(characterId: String? = null) =
        if (characterId != null) "character_editor/$characterId" else "character_editor/new"

    fun providerEditor(providerId: String? = null) =
        if (providerId != null) "provider_editor/$providerId" else "provider_editor/new"

    fun worldBookDetail(bookId: String) = "world_book_detail/$bookId"

    fun themeManager() = "theme_manager"

    fun memoryList(sessionId: String? = null, characterId: String? = null): String {
        val params = buildList {
            if (!sessionId.isNullOrBlank()) add("sessionId=$sessionId")
            if (!characterId.isNullOrBlank()) add("characterId=$characterId")
        }
        return if (params.isEmpty()) "memory_list" else "memory_list?${params.joinToString("&")}"
    }
}

// 默认动画配置
private const val ANIMATION_DURATION = 450

private fun defaultEnterTransition(): EnterTransition {
    return slideInHorizontally(
        initialOffsetX = { it },
        animationSpec = tween(
            durationMillis = ANIMATION_DURATION,
            easing = androidx.compose.animation.core.FastOutSlowInEasing
        )
    ) + fadeIn(
        animationSpec = tween(
            durationMillis = ANIMATION_DURATION,
            easing = androidx.compose.animation.core.LinearEasing
        )
    )
}

private fun defaultExitTransition(): ExitTransition {
    return fadeOut(
        animationSpec = tween(
            durationMillis = ANIMATION_DURATION,
            easing = androidx.compose.animation.core.LinearEasing
        )
    )
}

private fun defaultPopEnterTransition(): EnterTransition {
    return fadeIn(
        animationSpec = tween(
            durationMillis = ANIMATION_DURATION,
            easing = androidx.compose.animation.core.LinearEasing
        )
    )
}

private fun defaultPopExitTransition(): ExitTransition {
    return slideOutHorizontally(
        targetOffsetX = { it },
        animationSpec = tween(
            durationMillis = ANIMATION_DURATION,
            easing = androidx.compose.animation.core.FastOutSlowInEasing
        )
    ) + fadeOut(
        animationSpec = tween(
            durationMillis = ANIMATION_DURATION,
            easing = androidx.compose.animation.core.LinearEasing
        )
    )
}

@Composable
fun AppNavigation(chatViewModel: ChatViewModel? = null) {
    val navController = rememberNavController()
    val app = LocalContext.current.applicationContext as android.app.Application
    val factory = ViewModelProvider.AndroidViewModelFactory.getInstance(app)

    // Observe navigation events from ChatViewModel (for drawer -> navigation)
    if (chatViewModel != null) {
        val navEvent by chatViewModel.navigationEvent.collectAsState()
        LaunchedEffect(navEvent) {
            navEvent?.let { route ->
                navController.navigate(route)
                chatViewModel.clearNavigationEvent()
            }
        }
    }

    androidx.compose.material3.Surface(
        modifier = androidx.compose.ui.Modifier.fillMaxSize(),
        color = androidx.compose.material3.MaterialTheme.colorScheme.background
    ) {
        NavHost(navController = navController, startDestination = Routes.MAIN) {
        composable(
            route = Routes.MAIN,
            enterTransition = { defaultPopEnterTransition() },
            exitTransition = { defaultExitTransition() },
            popEnterTransition = { defaultPopEnterTransition() },
            popExitTransition = { defaultPopExitTransition() }
        ) {
            com.mistbell.tavern.android.ui.main.MainScreen(
                onChatClick = { sessionId, characterId ->
                    navController.navigate(Routes.chat(sessionId, characterId))
                },
                onNewChatClick = {
                    navController.navigate(Routes.chatSetup())
                },
                onEditCharacter = { characterId ->
                    navController.navigate(Routes.characterEditor(characterId))
                },
                onNewCharacter = {
                    navController.navigate(Routes.characterEditor())
                },
                onNavigateToProviderList = {
                    navController.navigate(Routes.PROVIDER_LIST)
                },
                onNavigateToWorldBookList = {
                    navController.navigate(Routes.WORLD_BOOK_LIST)
                },
                onNavigateToWorldBookDetail = { bookId ->
                    navController.navigate(Routes.worldBookDetail(bookId))
                },
                onNavigateToMemoryList = {
                    navController.navigate(Routes.memoryList())
                },
                onNavigateToPromptPreview = {
                    navController.navigate(Routes.PROMPT_PREVIEW)
                },
                onNavigateToChatSetup = { characterId ->
                    navController.navigate(Routes.chatSetup(characterId))
                },
                onNavigateToVersionChangelog = {
                    android.util.Log.d("AppNavigation", "MainScreen -> 导航到版本更新日志")
                    navController.navigate(Routes.VERSION_CHANGELOG)
                },
                onNavigateToAbout = {
                    navController.navigate(Routes.ABOUT)
                },
                onNavigateToThemeManager = {
                    navController.navigate(Routes.themeManager())
                }
            )
        }

        composable(
            route = Routes.CHAT,
            arguments = listOf(
                navArgument("sessionId") { type = NavType.StringType },
                navArgument("characterId") { type = NavType.StringType }
            ),
            enterTransition = { defaultEnterTransition() },
            exitTransition = { defaultExitTransition() },
            popEnterTransition = { defaultPopEnterTransition() },
            popExitTransition = { defaultPopExitTransition() }
        ) { backStackEntry ->
            val chatVm = chatViewModel ?: viewModel(factory = factory)
            val sessionId = backStackEntry.arguments?.getString("sessionId")
            val characterId = backStackEntry.arguments?.getString("characterId")

            // 初始化 ViewModel 加载会话数据
            LaunchedEffect(sessionId, characterId) {
                if (sessionId == "auto" && characterId != null) {
                    // 自动查找或创建会话
                    val db = com.mistbell.tavern.android.TavernApplication.instance.database
                    val existingSessionsList = db.sessionDao().getByCharacter("local-user", characterId)
                        .first()

                    val actualSessionId = if (existingSessionsList.isNotEmpty()) {
                        existingSessionsList[0].id
                    } else {
                        // 创建新会话 - 使用默认或第一个可用的 provider
                        val providerRepo = com.mistbell.tavern.android.data.repository.ProviderRepository(app)
                        val providers = providerRepo.observeProviders().first()
                        val defaultProvider = providers.firstOrNull()

                        val newSessionId = java.util.UUID.randomUUID().toString()
                        val now = java.time.Instant.now().toString()

                        val session = com.mistbell.tavern.android.data.local.entity.SessionEntity(
                            id = newSessionId,
                            ownerId = "local-user",
                            characterId = characterId,
                            title = "新对话",
                            createdAt = now,
                            updatedAt = now,
                            messageCount = 0,
                            providerId = defaultProvider?.id ?: "",
                            modelId = defaultProvider?.selectedModel ?: "",
                            worldBookId = "",
                            summaryJson = "",
                            unreadCount = 0,
                            isPinned = false,
                            pinnedAt = null,
                            isMuted = false,
                            enableLongTermMemory = false  // 默认关闭长期记忆
                        )
                        db.sessionDao().upsert(session)

                        // 插入开场白
                        val characterEntity = db.characterDao().getById(characterId)
                        if (characterEntity != null && characterEntity.firstMes.isNotBlank()) {
                            val firstMessage = com.mistbell.tavern.android.data.local.entity.MessageEntity(
                                id = java.util.UUID.randomUUID().toString(),
                                sessionId = newSessionId,
                                ownerId = "local-user",
                                characterId = characterId,
                                role = "assistant",
                                content = characterEntity.firstMes,
                                thinking = null,
                                createdAt = now,
                                memoryIdsJson = "[]",
                                swipesJson = "[]",
                                swipeIndex = 0,
                                thinkingSwipesJson = "[]",
                                isRead = true
                            )
                            db.messageDao().upsert(firstMessage)
                            db.sessionDao().upsert(session.copy(messageCount = 1, updatedAt = now))
                        }

                        newSessionId
                    }

                    chatVm.loadSession(actualSessionId, characterId)
                } else if (sessionId != null && characterId != null && sessionId != "new") {
                    chatVm.loadSession(sessionId, characterId)
                }
            }

            ChatScreen(
                viewModel = chatVm,
                onMenuClick = { navController.popBackStack() },
                onSettingsClick = {
                    val targetSessionId = chatVm.activeSessionId.value
                    if (targetSessionId.isNotBlank()) {
                        navController.navigate(Routes.chatSettings(targetSessionId))
                    }
                }
            )
        }

        composable(
            route = Routes.CHAT_SETUP,
            arguments = listOf(navArgument("characterId") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            }),
            enterTransition = { defaultEnterTransition() },
            exitTransition = { defaultExitTransition() },
            popEnterTransition = { defaultPopEnterTransition() },
            popExitTransition = { defaultPopExitTransition() }
        ) { backStackEntry ->
            val characterId = backStackEntry.arguments?.getString("characterId")
            com.mistbell.tavern.android.ui.chat.ChatSetupScreen(
                initialCharacterId = characterId,
                onBack = { navController.popBackStack() },
                onStartChat = { sessionId, characterIds ->
                    // 使用第一个角色 ID 作为主角色
                    val mainCharacterId = characterIds.firstOrNull() ?: "default"
                    android.util.Log.d("Navigation", "Starting chat: sessionId=$sessionId, characterId=$mainCharacterId")
                    navController.navigate(Routes.chat(sessionId, mainCharacterId)) {
                        popUpTo(Routes.MAIN) { inclusive = false }
                    }
                }
            )
        }

        composable(
            route = Routes.CHAT_SETTINGS,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
            enterTransition = { defaultEnterTransition() },
            exitTransition = { defaultExitTransition() },
            popEnterTransition = { defaultPopEnterTransition() },
            popExitTransition = { defaultPopExitTransition() }
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId")
            com.mistbell.tavern.android.ui.chat.ChatSettingsScreen(
                onBack = { navController.popBackStack() },
                onNavigateToMemoryManagement = {
                    navController.navigate(Routes.memoryList(sessionId = sessionId))
                },
                sessionId = sessionId
            )
        }

        composable(
            route = Routes.SETTINGS,
            enterTransition = { defaultEnterTransition() },
            exitTransition = { defaultExitTransition() },
            popEnterTransition = { defaultPopEnterTransition() },
            popExitTransition = { defaultPopExitTransition() }
        ) {
            val settingsViewModel: SettingsViewModel = viewModel(factory = factory)
            SettingsScreen(
                viewModel = settingsViewModel,
                onBack = { navController.popBackStack() },
                onNavigateToProviderList = {
                    navController.navigate(Routes.PROVIDER_LIST)
                },
                onNavigateToWorldBookList = {
                    navController.navigate(Routes.WORLD_BOOK_LIST)
                },
                onNavigateToMemoryList = {
                    navController.navigate(Routes.memoryList())
                },
                onNavigateToPromptPreview = {
                    navController.navigate(Routes.PROMPT_PREVIEW)
                },
                onNavigateToVersionChangelog = {
                    android.util.Log.d("AppNavigation", "导航到版本更新日志页面")
                    navController.navigate(Routes.VERSION_CHANGELOG)
                },
                onNavigateToAbout = {
                    navController.navigate(Routes.ABOUT)
                },
                onNavigateToThemeManager = {
                    navController.navigate(Routes.themeManager())
                }
            )
        }

        composable(
            route = Routes.CHARACTER_EDITOR,
            arguments = listOf(navArgument("characterId") { type = NavType.StringType }),
            enterTransition = { defaultEnterTransition() },
            exitTransition = { defaultExitTransition() },
            popEnterTransition = { defaultPopEnterTransition() },
            popExitTransition = { defaultPopExitTransition() }
        ) { backStackEntry ->
            val characterId = backStackEntry.arguments?.getString("characterId")
            com.mistbell.tavern.android.ui.character.CharacterEditorScreen(
                characterId = characterId,
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.PROVIDER_LIST,
            enterTransition = { defaultEnterTransition() },
            exitTransition = { defaultExitTransition() },
            popEnterTransition = { defaultPopEnterTransition() },
            popExitTransition = { defaultPopExitTransition() }
        ) {
            com.mistbell.tavern.android.ui.provider.ProviderListScreen(
                onBack = { navController.popBackStack() },
                onEditProvider = { id -> navController.navigate(Routes.providerEditor(id)) },
                onNewProvider = { navController.navigate(Routes.providerEditor()) }
            )
        }

        composable(
            route = Routes.PROVIDER_EDITOR,
            arguments = listOf(navArgument("providerId") { type = NavType.StringType }),
            enterTransition = { defaultEnterTransition() },
            exitTransition = { defaultExitTransition() },
            popEnterTransition = { defaultPopEnterTransition() },
            popExitTransition = { defaultPopExitTransition() }
        ) { backStackEntry ->
            val providerId = backStackEntry.arguments?.getString("providerId")
            com.mistbell.tavern.android.ui.provider.ProviderEditorScreen(
                providerId = providerId,
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.WORLD_BOOK_LIST,
            enterTransition = { defaultEnterTransition() },
            exitTransition = { defaultExitTransition() },
            popEnterTransition = { defaultPopEnterTransition() },
            popExitTransition = { defaultPopExitTransition() }
        ) {
            com.mistbell.tavern.android.ui.worldbook.WorldBookListScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.WORLD_BOOK_DETAIL,
            arguments = listOf(navArgument("bookId") { type = NavType.StringType }),
            enterTransition = { defaultEnterTransition() },
            exitTransition = { defaultExitTransition() },
            popEnterTransition = { defaultPopEnterTransition() },
            popExitTransition = { defaultPopExitTransition() }
        ) { backStackEntry ->
            val bookId = backStackEntry.arguments?.getString("bookId")
            com.mistbell.tavern.android.ui.worldbook.WorldBookDetailScreen(
                bookId = bookId ?: "",
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.MEMORY_LIST,
            arguments = listOf(
                navArgument("sessionId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("characterId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            ),
            enterTransition = { defaultEnterTransition() },
            exitTransition = { defaultExitTransition() },
            popEnterTransition = { defaultPopEnterTransition() },
            popExitTransition = { defaultPopExitTransition() }
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId")
            val characterId = backStackEntry.arguments?.getString("characterId")
            com.mistbell.tavern.android.ui.memory.StructuredMemoryScreen(
                ownerId = "local-user",
                characterId = characterId,
                sessionId = sessionId,
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.PROMPT_PREVIEW,
            enterTransition = { defaultEnterTransition() },
            exitTransition = { defaultExitTransition() },
            popEnterTransition = { defaultPopEnterTransition() },
            popExitTransition = { defaultPopExitTransition() }
        ) {
            com.mistbell.tavern.android.ui.prompt.PromptPreviewScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.VERSION_CHANGELOG,
            enterTransition = { defaultEnterTransition() },
            exitTransition = { defaultExitTransition() },
            popEnterTransition = { defaultPopEnterTransition() },
            popExitTransition = { defaultPopExitTransition() }
        ) {
            val settingsViewModel: SettingsViewModel = viewModel(factory = factory)
            com.mistbell.tavern.android.ui.settings.VersionChangelogScreen(
                onBack = { navController.popBackStack() },
                viewModel = settingsViewModel
            )
        }

        composable(
            route = Routes.THEME_MANAGER,
            enterTransition = { defaultEnterTransition() },
            exitTransition = { defaultExitTransition() },
            popEnterTransition = { defaultPopEnterTransition() },
            popExitTransition = { defaultPopExitTransition() }
        ) {
            ThemeManagerScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.ABOUT,
            enterTransition = { defaultEnterTransition() },
            exitTransition = { defaultExitTransition() },
            popEnterTransition = { defaultPopEnterTransition() },
            popExitTransition = { defaultPopExitTransition() }
        ) {
            com.mistbell.tavern.android.ui.settings.AboutScreen(
                onBack = { navController.popBackStack() },
                onNavigateToVersionChangelog = {
                    navController.navigate(Routes.VERSION_CHANGELOG)
                }
            )
        }
    }
    }
}
