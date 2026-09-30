# 核心聊天功能交互体验优化方案

## 当前交互问题诊断

### 1. **消息操作反馈不明确**

**问题**：
- 长按消息弹出菜单，但没有视觉反馈
- 操作成功/失败没有明确提示
- 撤销、重新生成等操作没有确认，误操作风险高

**优化方案**：
```kotlin
// 1. 添加触觉反馈
LocalView.current.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

// 2. 操作确认对话框（危险操作）
fun showConfirmDialog(title: String, message: String, onConfirm: () -> Unit)

// 3. 操作后的 Snackbar 提示
snackbarHostState.showSnackbar("消息已撤销", actionLabel = "恢复")
```

---

### 2. **输入框体验不佳**

**当前问题**：
- 没有字符计数提示
- 没有输入历史记录
- 发送按钮状态不明确
- 不支持多行输入预览

**优化方案**：

#### A. 增强型输入框
```kotlin
@Composable
fun EnhancedMessageInput(
    onSend: (String) -> Unit,
    enabled: Boolean,
    isGenerating: Boolean,
    onStop: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var isFocused by remember { mutableStateOf(false) }
    
    // 输入历史（最近5条）
    val inputHistory = remember { mutableStateListOf<String>() }
    var historyIndex by remember { mutableStateOf(-1) }
    
    Column {
        // 字符计数和建议（超长时提示）
        if (isFocused && text.length > 500) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    text = "${text.length} 字符",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (text.length > 2000) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(28.dp)
                )
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            // 快捷功能按钮
            IconButton(
                onClick = { /* 打开表情/快捷短语 */ },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(Icons.Default.EmojiEmotions, contentDescription = "表情")
            }
            
            // 多行输入框
            OutlinedTextField(
                value = text,
                onValueChange = { 
                    text = it
                    historyIndex = -1 // 重置历史导航
                },
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { isFocused = it.isFocused }
                    .onKeyEvent { keyEvent ->
                        // ↑ 键：上一条历史
                        if (keyEvent.key == Key.DirectionUp && 
                            keyEvent.type == KeyEventType.KeyDown &&
                            historyIndex < inputHistory.size - 1) {
                            historyIndex++
                            text = inputHistory[historyIndex]
                            true
                        }
                        // ↓ 键：下一条历史
                        else if (keyEvent.key == Key.DirectionDown && 
                                 keyEvent.type == KeyEventType.KeyDown &&
                                 historyIndex > 0) {
                            historyIndex--
                            text = inputHistory[historyIndex]
                            true
                        } else false
                    },
                placeholder = { Text("输入消息...") },
                maxLines = 5,
                minLines = 1,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                ),
            )
            
            // 发送/停止按钮（动画切换）
            AnimatedContent(
                targetState = isGenerating,
                transitionSpec = {
                    fadeIn() + scaleIn() with fadeOut() + scaleOut()
                }
            ) { generating ->
                if (generating) {
                    // 停止生成按钮
                    FilledIconButton(
                        onClick = onStop,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = "停止")
                    }
                } else {
                    // 发送按钮（文本为空时禁用）
                    FilledIconButton(
                        onClick = {
                            if (text.isNotBlank()) {
                                // 保存到历史
                                if (inputHistory.isEmpty() || inputHistory[0] != text) {
                                    inputHistory.add(0, text)
                                    if (inputHistory.size > 5) {
                                        inputHistory.removeAt(5)
                                    }
                                }
                                onSend(text)
                                text = ""
                                historyIndex = -1
                            }
                        },
                        enabled = enabled && text.isNotBlank(),
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "发送")
                    }
                }
            }
        }
    }
}
```

---

### 3. **消息气泡交互不足**

**当前问题**：
- 只能长按弹菜单，短按无反应
- 没有快捷操作（快速复制、快速重新生成）
- 多个 swipe 的切换不直观

**优化方案**：

#### A. 滑动手势操作
```kotlin
// 左滑：快速复制
// 右滑：快速重新生成（AI消息）
@Composable
fun SwipeableMessageBubble(
    message: Message,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
) {
    var offsetX by remember { mutableStateOf(0f) }
    
    Box(
        modifier = Modifier
            .pointerInput(message.id) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (abs(offsetX) > 100.dp.toPx()) {
                            if (offsetX > 0) {
                                // 右滑：重新生成
                                if (!message.isUser) onRegenerate()
                            } else {
                                // 左滑：复制
                                onCopy()
                            }
                        }
                        offsetX = 0f
                    },
                    onHorizontalDrag = { _, dragAmount ->
                        offsetX += dragAmount
                        offsetX = offsetX.coerceIn(-150.dp.toPx(), 150.dp.toPx())
                    }
                )
            }
            .offset { IntOffset(offsetX.roundToInt(), 0) }
    ) {
        // 背景提示图标
        if (offsetX > 50.dp.toPx()) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                modifier = Modifier.align(Alignment.CenterStart)
            )
        } else if (offsetX < -50.dp.toPx()) {
            Icon(
                Icons.Default.ContentCopy,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
        
        // 原消息气泡
        MessageBubble(message = message, ...)
    }
}
```

#### B. Swipe 指示器
```kotlin
// 有多个 swipe 时显示导航点
if (message.swipes != null && message.swipes.size > 1) {
    Row(
        modifier = Modifier.padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        message.swipes.indices.forEach { index ->
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(
                        if (index == message.swipeIndex) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        }
                    )
                    .clickable { onSwipeTo(index) }
            )
        }
    }
}
```

---

### 4. **流式输出体验问题**

**当前问题**：
- 没有"正在输入"动画
- 流式文本滚动不跟随
- 无法暂停查看

**优化方案**：

#### A. 输入中动画
```kotlin
@Composable
fun TypingIndicator() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(16.dp)
    ) {
        repeat(3) { index ->
            val infiniteTransition = rememberInfiniteTransition()
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(index * 200)
                )
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = alpha)
                    )
            )
        }
    }
}
```

#### B. 暂停/继续流式输出
```kotlin
var isPaused by remember { mutableStateOf(false) }

// 暂停按钮（悬浮在流式消息上）
if (isStreaming && !isPaused) {
    FloatingActionButton(
        onClick = { isPaused = true },
        modifier = Modifier.align(Alignment.BottomEnd)
    ) {
        Icon(Icons.Default.Pause, contentDescription = "暂停")
    }
}
```

---

### 5. **错误处理不友好**

**当前问题**：
- 错误只在 Snackbar 闪一下就消失
- 没有重试机制
- 网络错误提示不清晰

**优化方案**：

#### A. 持久化错误提示
```kotlin
// 发送失败的消息显示重试按钮
@Composable
fun FailedMessageBubble(
    message: Message,
    error: String,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    Column {
        MessageBubble(message = message, ...)
        
        // 错误提示卡片
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "发送失败",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
                    )
                }
                Row {
                    TextButton(onClick = onRetry) {
                        Text("重试")
                    }
                    TextButton(onClick = onDelete) {
                        Text("删除")
                    }
                }
            }
        }
    }
}
```

---

### 6. **消息编辑功能缺失**

**优化方案**：

#### A. 编辑用户消息
```kotlin
// 长按菜单添加"编辑"选项（仅用户消息）
if (isUser) {
    DropdownMenuItem(
        text = { Text("编辑") },
        onClick = {
            showMenu = false
            onEdit(message.id)
        },
        leadingIcon = { Icon(Icons.Default.Edit, null) }
    )
}

// 编辑对话框
@Composable
fun EditMessageDialog(
    message: Message,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var editedText by remember { mutableStateOf(message.content) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑消息") },
        text = {
            OutlinedTextField(
                value = editedText,
                onValueChange = { editedText = it },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 10
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(editedText) },
                enabled = editedText.isNotBlank()
            ) {
                Text("保存并重新生成")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
```

---

### 7. **快捷短语/模板功能**

**优化方案**：

```kotlin
// 输入框上方的快捷短语栏
@Composable
fun QuickPhrasesBar(
    phrases: List<String>,
    onSelect: (String) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp)
    ) {
        items(phrases) { phrase ->
            AssistChip(
                onClick = { onSelect(phrase) },
                label = { Text(phrase, maxLines = 1) }
            )
        }
    }
}

// 常用短语示例
val commonPhrases = listOf(
    "继续",
    "详细说明",
    "换个方式",
    "举个例子",
    "总结一下",
)
```

---

### 8. **消息搜索和跳转**

**优化方案**：

```kotlin
// 顶栏添加搜索按钮
IconButton(onClick = { showSearchBar = true }) {
    Icon(Icons.Default.Search, contentDescription = "搜索")
}

// 搜索栏（可折叠）
AnimatedVisibility(visible = showSearchBar) {
    SearchBar(
        query = searchQuery,
        onQueryChange = { searchQuery = it },
        onSearch = { query ->
            // 高亮匹配的消息
            highlightedMessageIds = messages
                .filter { it.content.contains(query, ignoreCase = true) }
                .map { it.id }
        },
        active = true,
        onActiveChange = { showSearchBar = it }
    ) {
        // 搜索结果列表
        LazyColumn {
            items(searchResults) { result ->
                ListItem(
                    headlineContent = { Text(result.preview) },
                    supportingContent = { Text(result.timestamp) },
                    modifier = Modifier.clickable {
                        // 跳转到该消息
                        coroutineScope.launch {
                            listState.animateScrollToItem(result.index)
                        }
                    }
                )
            }
        }
    }
}
```

---

## 实施优先级

### P0（立即实施）：
1. ✅ **增强输入框**：字符计数、发送按钮状态
2. ✅ **操作反馈**：触觉反馈、Snackbar 提示
3. ✅ **流式输出指示器**：输入中动画

### P1（本周）：
4. **消息编辑**：编辑用户消息并重新生成
5. **错误重试**：失败消息显示重试按钮
6. **Swipe 指示器**：多个回复时显示导航点

### P2（下周）：
7. **滑动手势**：快速复制/重新生成
8. **快捷短语**：常用输入模板
9. **消息搜索**：全文搜索和跳转

---

## 微交互细节清单

- [ ] 按钮按下时的涟漪动画
- [ ] 长按触发触觉反馈
- [ ] 消息发送时的飞入动画
- [ ] 输入框获得焦点时平滑扩展
- [ ] 发送/停止按钮的旋转切换动画
- [ ] 菜单弹出的弹簧动画
- [ ] 消息加载时的骨架屏
- [ ] 滚动到底部时的回弹效果
- [ ] 错误提示的抖动动画
- [ ] Swipe 切换的滑动过渡

---

## 可访问性优化

- [ ] 所有交互元素添加 contentDescription
- [ ] 支持 TalkBack 屏幕阅读器
- [ ] 增大最小触摸目标到 48dp
- [ ] 高对比度模式支持
- [ ] 字体缩放支持（最大 200%）
- [ ] 颜色盲模式（不依赖颜色传达信息）
