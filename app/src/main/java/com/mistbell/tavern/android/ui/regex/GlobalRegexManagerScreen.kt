package com.mistbell.tavern.android.ui.regex

import androidx.activity.compose.BackHandler
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mistbell.tavern.android.ui.character.RegexDiscardDialog
import com.mistbell.tavern.android.ui.character.RegexManagerContent

/**
 * 全局正则管理页：复用卡级正则的全部编辑组件（列表/编辑器/三模式预览），
 * 仅持久化目标不同（global_regex_rules，对所有会话生效且先于卡级执行）。
 */
@Composable
fun GlobalRegexManagerScreen(
    onBack: () -> Unit,
    viewModel: GlobalRegexViewModel = viewModel(),
) {
    val uiState by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDiscardDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.load() }
    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    BackHandler(enabled = uiState.isDirty) { showDiscardDialog = true }
    val handleBack = { if (uiState.isDirty) showDiscardDialog = true else onBack() }

    RegexManagerContent(
        uiState = uiState,
        viewModel = viewModel,
        onBack = handleBack,
        snackbarHostState = snackbarHostState,
    )

    if (showDiscardDialog) {
        RegexDiscardDialog(
            onDiscard = {
                showDiscardDialog = false
                onBack()
            },
            onDismiss = { showDiscardDialog = false },
        )
    }
}
