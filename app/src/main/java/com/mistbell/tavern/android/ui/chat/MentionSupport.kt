package com.mistbell.tavern.android.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.mistbell.tavern.android.data.api.model.Character

/**
 * 解析消息中的 @提及
 * 返回：提及的角色名称列表
 */
fun parseMentions(text: String): List<String> {
    val mentionPattern = "@([\\p{L}\\p{N}_]+)".toRegex()
    return mentionPattern.findAll(text).map { it.groupValues[1] }.toList()
}

/**
 * 检测当前光标位置是否在输入 @提及
 * 返回：如果正在输入 @，返回 @ 后面的部分；否则返回 null
 */
fun detectMentionInput(
    text: String,
    cursorPosition: Int,
): String? {
    if (cursorPosition <= 0 || cursorPosition > text.length) return null

    // 从光标位置向前查找最近的 @ 符号
    var atPosition = -1
    for (i in cursorPosition - 1 downTo 0) {
        when (text[i]) {
            '@' -> {
                atPosition = i
                break
            }
            ' ', '\n' -> break // 遇到空格或换行，停止查找
        }
    }

    if (atPosition == -1) return null

    // 提取 @ 后面到光标位置的文本
    val mentionText = text.substring(atPosition + 1, cursorPosition)

    // 如果包含空格或换行，说明不是有效的提及
    if (mentionText.contains(' ') || mentionText.contains('\n')) return null

    return mentionText
}

/**
 * 过滤匹配的角色
 */
fun filterCharacters(
    characters: List<Character>,
    query: String,
): List<Character> {
    if (query.isEmpty()) return characters
    val lowerQuery = query.lowercase()
    return characters.filter { it.name.lowercase().contains(lowerQuery) }
}

/**
 * @提及自动补全弹窗
 */
@Composable
fun MentionSuggestionPopup(
    suggestions: List<Character>,
    onSuggestionClick: (Character) -> Unit,
    onDismiss: () -> Unit,
) {
    if (suggestions.isEmpty()) {
        onDismiss()
        return
    }

    Popup(
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = false),
    ) {
        Card(
            modifier =
                Modifier
                    .widthIn(max = 300.dp)
                    .heightIn(max = 200.dp)
                    .padding(horizontal = 16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            shape = RoundedCornerShape(12.dp),
        ) {
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
            ) {
                items(suggestions.take(5)) { character ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onSuggestionClick(character) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(
                            text = character.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (character != suggestions.take(5).last()) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 替换文本中的 @ 提及
 * 将光标前的 @query 替换为 @characterName
 */
fun replaceMention(
    text: String,
    cursorPosition: Int,
    characterName: String,
): Pair<String, Int> {
    // 向前查找 @ 位置
    var atPosition = -1
    for (i in cursorPosition - 1 downTo 0) {
        if (text[i] == '@') {
            atPosition = i
            break
        }
        if (text[i] == ' ' || text[i] == '\n') break
    }

    if (atPosition == -1) return text to cursorPosition

    // 构建新文本：@ 之前的部分 + @角色名 + 空格 + 光标之后的部分
    val before = text.substring(0, atPosition)
    val after = text.substring(cursorPosition)
    val mention = "@$characterName "
    val newText = before + mention + after
    val newCursorPosition = before.length + mention.length

    return newText to newCursorPosition
}
