package com.mistbell.tavern.android.ui.drawer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mistbell.tavern.android.data.api.model.Character
import com.mistbell.tavern.android.data.api.model.SessionSummary
import com.mistbell.tavern.android.ui.theme.*

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DrawerContent(
    characters: List<Character>,
    recentSessions: List<SessionSummary>,
    currentCharacter: Character?,
    onCharacterSelected: (Character) -> Unit,
    onCharacterEdit: (Character) -> Unit = {},
    onCharacterDelete: (Character) -> Unit = {},
    onCharacterExport: (Character) -> Unit = {},
    onNewCharacter: () -> Unit = {},
    onNewChat: () -> Unit,
    onSessionSelected: (String) -> Unit,
    onSessionDeleted: (String) -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }

    val filteredCharacters =
        if (searchQuery.isBlank()) {
            characters
        } else {
            characters.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                    it.description.contains(searchQuery, ignoreCase = true)
            }
        }

    Column(
        modifier =
            Modifier
                .fillMaxHeight()
                .width(300.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .statusBarsPadding(),
    ) {
        // Header
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(AccentBlue),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "🔔", fontSize = 14.sp)
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "暮铃",
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
                letterSpacing = (-0.02).sp,
            )
        }

        // Character section header with + button
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "角色",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 0.06.sp,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onNewCharacter, modifier = Modifier.size(24.dp)) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "新建角色",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Search bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = {
                Text(
                    "搜索角色...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            },
            leadingIcon = {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            },
            trailingIcon = {
                if (searchQuery.isNotBlank()) {
                    IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(20.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "清除", modifier = Modifier.size(14.dp))
                    }
                }
            },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .padding(start = 12.dp, end = 12.dp)
                    .clip(RoundedCornerShape(8.dp)),
            colors =
                OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    unfocusedContainerColor = LightInput,
                    focusedBorderColor = AccentBlue,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                ),
            textStyle = MaterialTheme.typography.bodySmall,
            singleLine = true,
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Character list
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
            items(filteredCharacters) { character ->
                CharacterItem(
                    character = character,
                    isSelected = character.id == currentCharacter?.id,
                    onClick = { onCharacterSelected(character) },
                    onLongClick = { onCharacterEdit(character) },
                    onDelete = { onCharacterDelete(character) },
                    onExport = { onCharacterExport(character) },
                )
            }
        }

        // Recent sessions
        if (recentSessions.isNotEmpty()) {
            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            )

            Text(
                text = "最近聊天",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 0.06.sp,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 6.dp),
            )

            LazyColumn(
                modifier = Modifier.heightIn(max = 200.dp),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                items(recentSessions) { session ->
                    var showDeleteConfirm by remember { mutableStateOf(false) }
                    SessionItem(
                        session = session,
                        isActive = false,
                        onClick = { onSessionSelected(session.id) },
                        onDelete = { showDeleteConfirm = true },
                    )
                    if (showDeleteConfirm) {
                        AlertDialog(
                            onDismissRequest = { showDeleteConfirm = false },
                            title = { Text("删除会话") },
                            text = { Text("确定删除「${session.title.ifBlank { "新会话" }}」？") },
                            confirmButton = {
                                TextButton(onClick = {
                                    showDeleteConfirm = false
                                    onSessionDeleted(session.id)
                                }) {
                                    Text("删除", color = MaterialTheme.colorScheme.error)
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
                            },
                        )
                    }
                }
            }
        }

        // New chat button
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)

        OutlinedButton(
            onClick = onNewChat,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
                    .height(38.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface),
            border = ButtonDefaults.outlinedButtonBorder,
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("新对话", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CharacterItem(
    character: Character,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onDelete: () -> Unit = {},
    onExport: () -> Unit = {},
) {
    val charColor =
        try {
            Color(android.graphics.Color.parseColor(character.color))
        } catch (_: Exception) {
            AccentBlue
        }

    var showMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (isSelected) LightActive else Color.Transparent)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { showMenu = true },
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Avatar
        Box {
            if (character.avatarData.isNotBlank()) {
                AsyncImage(
                    model = character.avatarData,
                    contentDescription = character.name,
                    modifier =
                        Modifier
                            .size(38.dp)
                            .shadow(2.dp, CircleShape)
                            .clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    modifier =
                        Modifier
                            .size(38.dp)
                            .shadow(2.dp, CircleShape)
                            .clip(CircleShape)
                            .background(charColor),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = character.name.take(1),
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        fontSize = 14.sp,
                    )
                }
            }
            Box(
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(AccentGreen)
                        .border(2.dp, MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = character.name,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (character.description.isNotBlank()) {
                Text(
                    text = character.description,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Show session count hint
            Text(
                text = "长按编辑",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }

        // Long-press menu
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
        ) {
            DropdownMenuItem(
                text = { Text("编辑") },
                onClick = {
                    showMenu = false
                    onLongClick()
                },
                leadingIcon = { Icon(Icons.Default.Edit, null, modifier = Modifier.size(18.dp)) },
            )
            DropdownMenuItem(
                text = { Text("导出") },
                onClick = {
                    showMenu = false
                    onExport()
                },
                leadingIcon = { Icon(Icons.Default.FileUpload, null, modifier = Modifier.size(18.dp)) },
            )
            DropdownMenuItem(
                text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                onClick = {
                    showMenu = false
                    showDeleteConfirm = true
                },
                leadingIcon = { Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error) },
            )
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除角色") },
            text = { Text("确定删除「${character.name}」？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDelete()
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionItem(
    session: SessionSummary,
    isActive: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (isActive) AccentBlueLight else Color.Transparent)
                .then(
                    if (isActive) {
                        Modifier.border(1.dp, AccentBlue, RoundedCornerShape(8.dp))
                    } else {
                        Modifier
                    },
                )
                .combinedClickable(onClick = onClick, onLongClick = {})
                .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = session.title.ifBlank { "新会话" },
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                color = if (isActive) AccentBlue else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${session.messageCount} 条消息",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        IconButton(onClick = onDelete, modifier = Modifier.size(22.dp)) {
            Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
        }
    }
}
