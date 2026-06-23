package com.mistbell.tavern.android.ui.utils

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager

/**
 * 点击非输入框区域时自动收起键盘
 *
 * 使用方法：在根容器（Scaffold、Column、Box等）的 Modifier 上添加此扩展
 *
 * 示例：
 * ```
 * Scaffold(
 *     modifier = Modifier
 *         .fillMaxSize()
 *         .clearFocusOnTap()
 * ) { ... }
 * ```
 */
@Composable
fun Modifier.clearFocusOnTap(): Modifier {
    val focusManager = LocalFocusManager.current
    return this.pointerInput(Unit) {
        detectTapGestures(onTap = {
            focusManager.clearFocus()
        })
    }
}
