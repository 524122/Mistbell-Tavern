package com.mistbell.tavern.android.ui.components

import androidx.compose.material3.*
import androidx.compose.runtime.Composable

@Composable
fun ConfirmDeleteDialog(
    title: String = "确认删除",
    message: String = "此操作不可撤销，确定要删除吗？",
    confirmText: String = "删除",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
