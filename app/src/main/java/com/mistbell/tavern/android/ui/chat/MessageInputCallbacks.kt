package com.mistbell.tavern.android.ui.chat

data class MessageInputCallbacks(
    val onSend: (String) -> Unit,
    val onStop: (() -> Unit)? = null,
    val onInsertConsumed: () -> Unit = {},
)
