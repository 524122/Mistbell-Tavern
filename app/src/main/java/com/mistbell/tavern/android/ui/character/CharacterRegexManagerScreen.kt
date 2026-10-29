package com.mistbell.tavern.android.ui.character

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

@Composable
fun CharacterRegexManagerScreen(
    characterId: String,
    onBack: () -> Unit,
    viewModel: CharacterRegexViewModel = viewModel(key = characterId),
) {
    val uiState by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDiscardDialog by remember { mutableStateOf(false) }

    LaunchedEffect(characterId) { viewModel.loadCharacter(characterId) }
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
