package com.mistbell.tavern.android.ui.character

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.mistbell.tavern.android.ui.components.*
import com.mistbell.tavern.android.ui.utils.clearFocusOnTap

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterEditorScreen(
    characterId: String?,
    onBack: () -> Unit,
    viewModel: CharacterEditorViewModel = viewModel(),
) {
    val form by viewModel.form.collectAsState()
    val isEditing by viewModel.isEditing.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val saved by viewModel.saved.collectAsState()
    val message by viewModel.message.collectAsState()
    val worldBooks by viewModel.worldBooks.collectAsState()
    val availableThemes by viewModel.availableThemes.collectAsState()

    val context = LocalContext.current
    var showAdvanced by remember { mutableStateOf(false) }
    var showGreetingSheet by remember { mutableStateOf(false) }
    var newGreeting by remember { mutableStateOf("") }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var showAvatarSheet by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }

    // Back handler with unsaved changes check
    BackHandler {
        if (form.name.isNotBlank() || form.description.isNotBlank() || form.personality.isNotBlank()) {
            showDiscardDialog = true
        } else {
            onBack()
        }
    }

    // Load character if editing
    LaunchedEffect(characterId) {
        if (characterId != null && characterId != "new") {
            viewModel.loadCharacter(characterId)
        }
    }

    // Navigate back after save
    LaunchedEffect(saved) {
        if (saved) onBack()
    }

    // Snackbar
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    // Image picker for gallery
    val galleryPicker =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent(),
        ) { uri: Uri? ->
            uri?.let {
                val processedImage =
                    com.mistbell.tavern.android.util.ImageUtils.processImage(
                        context = context,
                        uri = it,
                        maxSize = 1024, // 降低到1024px，确保不超过数据库限制
                        quality = 85, // 适中质量
                    )
                if (processedImage != null) {
                    viewModel.updateForm { copy(avatarData = processedImage) }
                }
            }
        }

    // Image picker for camera
    var tempCameraUri by remember { mutableStateOf<Uri?>(null) }
    val cameraPicker =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.TakePicture(),
        ) { success ->
            val uri = tempCameraUri
            if (success && uri != null) {
                val processedImage =
                    com.mistbell.tavern.android.util.ImageUtils.processImage(
                        context = context,
                        uri = uri,
                        maxSize = 1024, // 降低到1024px，确保不超过数据库限制
                        quality = 85, // 适中质量
                    )
                if (processedImage != null) {
                    viewModel.updateForm { copy(avatarData = processedImage) }
                }
            }
        }

    Scaffold(
        modifier =
            Modifier
                .fillMaxSize()
                .clearFocusOnTap(),
        topBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
            ) {
                Column(modifier = Modifier.statusBarsPadding()) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                                .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", modifier = Modifier.size(22.dp))
                        }
                        Text(
                            text = if (isEditing) "编辑角色" else "新建角色",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = { viewModel.saveCharacter() },
                            enabled = !isSaving && form.name.isNotBlank(),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            if (isSaving) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Text("保存")
                            }
                        }
                    }
                    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .imePadding(),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Avatar, Name and Description in one row
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Name and Description on left
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Column {
                            SectionHeader("名称 *")
                            Spacer(modifier = Modifier.height(8.dp))
                            FormTextField(
                                value = form.name,
                                onValueChange = { viewModel.updateForm { copy(name = it) } },
                                label = "角色名称",
                                placeholder = "输入角色名称",
                            )
                        }

                        Column {
                            SectionHeader("描述")
                            Spacer(modifier = Modifier.height(8.dp))
                            FormTextArea(
                                value = form.description,
                                onValueChange = { viewModel.updateForm { copy(description = it) } },
                                label = "角色描述",
                                placeholder = "描述这个角色的背景...",
                                minLines = 3,
                            )
                        }
                    }

                    // Avatar on right (tall)
                    Column {
                        SectionHeader("头像")
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier =
                                Modifier
                                    .width(120.dp)
                                    .height(200.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .border(
                                        width = 2.dp,
                                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                        shape = RoundedCornerShape(16.dp),
                                    )
                                    .clickable { showAvatarSheet = true },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (form.avatarData.isNotBlank()) {
                                android.util.Log.d(
                                    "CharacterEditor",
                                    "Avatar data length: ${form.avatarData.length}, starts with: ${form.avatarData.take(50)}",
                                )

                                // 从data URI解析并显示图片
                                val bitmap =
                                    remember(form.avatarData) {
                                        com.mistbell.tavern.android.util.ImageUtils.dataUriToBitmap(form.avatarData)
                                    }

                                if (bitmap != null) {
                                    androidx.compose.foundation.Image(
                                        bitmap = bitmap.asImageBitmap(),
                                        contentDescription = "头像",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop,
                                    )
                                } else {
                                    // Fallback to AsyncImage if manual parsing fails
                                    AsyncImage(
                                        model = form.avatarData,
                                        contentDescription = "头像",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop,
                                    )
                                }
                            } else {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = "添加头像",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        "添加头像",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    )
                                }
                            }
                        }

                        // Remove avatar button (only show when avatar exists)
                        if (form.avatarData.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { viewModel.updateForm { copy(avatarData = "") } },
                                modifier = Modifier.width(120.dp),
                                colors =
                                    ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error,
                                    ),
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "删除头像",
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("删除", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

            // Greeting
            item {
                SectionHeader("问候语")
                Spacer(modifier = Modifier.height(8.dp))
                FormTextArea(
                    value = form.firstMes,
                    onValueChange = { viewModel.updateForm { copy(firstMes = it) } },
                    label = "首条消息",
                    placeholder = "角色的第一条消息...",
                    minLines = 4,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showGreetingSheet = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("其它问候语 (${form.customGreetings.size})")
                }
            }

            // Advanced toggle
            item {
                OutlinedButton(
                    onClick = { showAdvanced = !showAdvanced },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(if (showAdvanced) "收起高级定义" else "展开高级定义")
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        if (showAdvanced) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            // Advanced section
            item {
                AnimatedVisibility(visible = showAdvanced) {
                    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        // System prompt override
                        SectionHeader("Prompt 覆盖")
                        Spacer(modifier = Modifier.height(8.dp))
                        FormTextArea(
                            value = form.systemPrompt,
                            onValueChange = { viewModel.updateForm { copy(systemPrompt = it) } },
                            label = "系统提示词",
                            placeholder = "Custom system prompt...",
                        )
                        FormTextArea(
                            value = form.postHistoryInstructions,
                            onValueChange = { viewModel.updateForm { copy(postHistoryInstructions = it) } },
                            label = "历史后指令",
                            placeholder = "插入到历史记录之后的指令...",
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        // Character settings
                        SectionHeader("角色设定")
                        Spacer(modifier = Modifier.height(8.dp))
                        FormTextArea(
                            value = form.personality,
                            onValueChange = { viewModel.updateForm { copy(personality = it) } },
                            label = "人设",
                            placeholder = "角色的性格特征...",
                        )
                        FormTextArea(
                            value = form.scenario,
                            onValueChange = { viewModel.updateForm { copy(scenario = it) } },
                            label = "场景",
                            placeholder = "对话发生的场景...",
                        )
                        FormTextArea(
                            value = form.mesExample,
                            onValueChange = { viewModel.updateForm { copy(mesExample = it) } },
                            label = "示例对话",
                            placeholder = "<START>\n{{user}}: ...\n{{char}}: ...",
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        // Creator info
                        SectionHeader("创建者信息")
                        Spacer(modifier = Modifier.height(8.dp))
                        FormTextField(
                            value = form.creator,
                            onValueChange = { viewModel.updateForm { copy(creator = it) } },
                            label = "创建者",
                            placeholder = "你的名字",
                        )
                        FormTextField(
                            value = form.characterVersion,
                            onValueChange = { viewModel.updateForm { copy(characterVersion = it) } },
                            label = "版本",
                            placeholder = "1.0",
                        )
                        FormTextArea(
                            value = form.creatorNotes,
                            onValueChange = { viewModel.updateForm { copy(creatorNotes = it) } },
                            label = "创建者备注",
                            placeholder = "关于这个角色的备注...",
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        // World Book binding
                        SectionHeader("世界书绑定")
                        Spacer(modifier = Modifier.height(8.dp))
                        var worldBookExpanded by remember { mutableStateOf(false) }
                        val selectedBook = worldBooks.find { it.id == form.worldBookId }

                        ExposedDropdownMenuBox(
                            expanded = worldBookExpanded,
                            onExpandedChange = { worldBookExpanded = it },
                        ) {
                            OutlinedTextField(
                                value = selectedBook?.name ?: "无",
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("世界书") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = worldBookExpanded) },
                                modifier = Modifier.fillMaxWidth().menuAnchor(),
                                shape = RoundedCornerShape(8.dp),
                            )
                            ExposedDropdownMenu(
                                expanded = worldBookExpanded,
                                onDismissRequest = { worldBookExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("无") },
                                    onClick = {
                                        viewModel.updateForm { copy(worldBookId = "") }
                                        worldBookExpanded = false
                                    },
                                )
                                worldBooks.forEach { book ->
                                    DropdownMenuItem(
                                        text = { Text(book.name) },
                                        onClick = {
                                            viewModel.updateForm { copy(worldBookId = book.id) }
                                            worldBookExpanded = false
                                        },
                                    )
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        // 专属主题选择
                        SectionHeader("专属主题")
                        Spacer(modifier = Modifier.height(8.dp))
                        val selectedTheme = availableThemes.find { it.id == form.themeId }
                        OutlinedTextField(
                            value = selectedTheme?.name ?: "跟随全局",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("主题") },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { showThemeDialog = true },
                            shape = RoundedCornerShape(8.dp),
                            enabled = false,
                            colors =
                                OutlinedTextFieldDefaults.colors(
                                    disabledTextColor = MaterialTheme.colorScheme.onSurface,
                                    disabledBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                    disabledContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                    disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                        )
                    }
                }
            }
        }
    }

    // Alternative greetings bottom sheet
    if (showGreetingSheet) {
        ModalBottomSheet(
            onDismissRequest = { showGreetingSheet = false },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("其它问候语", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "添加多个问候语，新对话可以选择其中一个。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                form.customGreetings.forEachIndexed { index, greeting ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "问候语 ${index + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                greeting,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 3,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                if (index > 0) {
                                    IconButton(onClick = {
                                        val list = form.customGreetings.toMutableList()
                                        list.add(index - 1, list.removeAt(index))
                                        viewModel.updateForm { copy(customGreetings = list) }
                                    }) { Icon(Icons.Default.KeyboardArrowUp, "上移", modifier = Modifier.size(18.dp)) }
                                }
                                if (index < form.customGreetings.size - 1) {
                                    IconButton(onClick = {
                                        val list = form.customGreetings.toMutableList()
                                        list.add(index + 1, list.removeAt(index))
                                        viewModel.updateForm { copy(customGreetings = list) }
                                    }) { Icon(Icons.Default.KeyboardArrowDown, "下移", modifier = Modifier.size(18.dp)) }
                                }
                                IconButton(onClick = {
                                    viewModel.updateForm { copy(customGreetings = customGreetings.filterIndexed { i, _ -> i != index }) }
                                }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        "删除",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                // Add new greeting
                FormTextArea(
                    value = newGreeting,
                    onValueChange = { newGreeting = it },
                    label = "新问候语",
                    placeholder = "输入新的问候语...",
                    minLines = 2,
                )
                Button(
                    onClick = {
                        if (newGreeting.isNotBlank()) {
                            viewModel.updateForm { copy(customGreetings = customGreetings + newGreeting) }
                            newGreeting = ""
                        }
                    },
                    enabled = newGreeting.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("添加")
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    // 专属主题选择对话框
    if (showThemeDialog) {
        var tempSelection by remember { mutableStateOf(form.themeId) }
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text("选择专属主题") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // 跟随全局
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { tempSelection = "" }
                                .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = tempSelection.isBlank(),
                            onClick = { tempSelection = "" },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("跟随全局")
                    }
                    availableThemes.forEach { theme ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { tempSelection = theme.id }
                                    .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = tempSelection == theme.id,
                                onClick = { tempSelection = theme.id },
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(theme.name)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateForm { copy(themeId = tempSelection) }
                    showThemeDialog = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showThemeDialog = false }) { Text("取消") }
            },
        )
    }

    // Discard changes confirmation
    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("Discard changes?") },
            text = { Text("你有未保存的更改，确定要离开吗？") },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardDialog = false
                    onBack()
                }) {
                    Text("丢弃", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) { Text("继续编辑") }
            },
        )
    }

    // Avatar picker bottom sheet
    if (showAvatarSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAvatarSheet = false },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 32.dp),
            ) {
                Text(
                    text = "选择头像来源",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )

                // Camera option
                ListItem(
                    headlineContent = { Text("拍照") },
                    leadingContent = {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                        )
                    },
                    modifier =
                        Modifier.clickable {
                            showAvatarSheet = false
                            // Create temporary file for camera
                            val file = java.io.File(context.cacheDir, "avatar_${System.currentTimeMillis()}.jpg")
                            val uri =
                                androidx.core.content.FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file,
                                )
                            tempCameraUri = uri
                            cameraPicker.launch(uri)
                        },
                )

                // Gallery option
                ListItem(
                    headlineContent = { Text("从相册选择") },
                    leadingContent = {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                        )
                    },
                    modifier =
                        Modifier.clickable {
                            showAvatarSheet = false
                            galleryPicker.launch("image/*")
                        },
                )
            }
        }
    }
}
