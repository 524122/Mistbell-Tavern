# 性能优化方案

## 卡顿诊断清单

### 1. Compose 渲染层卡顿（最可能）

#### 问题点：
- **Markdown 渲染开销大**：每条消息都要渲染 Markdown，长消息尤其卡
- **不稳定的 Composable**：参数变化导致不必要的重组
- **过度重组**：State 订阅范围过大
- **列表滚动卡顿**：LazyColumn item 重组频繁

#### 验证方法：
```bash
# 启用 Compose 性能追踪
adb shell setprop debug.compose.trace true

# 查看 Systrace
python $ANDROID_HOME/platform-tools/systrace/systrace.py --time=10 -o trace.html sched freq idle am wm gfx view binder_driver hal dalvik camera input res

# 或使用 Android Studio Profiler
# Run → Profile 'app' → CPU Profiler
```

### 2. 数据库查询卡顿

#### 问题点：
- 消息查询可能在主线程
- Flow 发射频率过高
- 缺少必要的索引

#### 验证方法：
```bash
# 查看数据库操作日志
adb logcat -s "RoomDatabase" "SQLiteDatabase"

# 查看慢查询
adb shell "run-as com.mistbell.tavern.android sqlite3 /data/data/com.mistbell.tavern.android/databases/tavern.db 'EXPLAIN QUERY PLAN SELECT * FROM messages WHERE session_id = ? AND owner_id = ? ORDER BY created_at DESC LIMIT 50'"
```

### 3. 内存/GC 卡顿

#### 问题点：
- 频繁创建大对象
- Bitmap 未及时释放
- 内存泄漏

#### 验证方法：
```bash
# 查看 GC 日志
adb logcat -s "dalvikvm" "art"

# 内存分析
# Android Studio → Profiler → Memory → Record allocations
```

---

## 快速优化方案（无需大改）

### 优化 1：Markdown 渲染懒加载

**问题**：所有消息的 Markdown 都立即渲染，长会话卡顿明显

**方案**：只渲染可见区域的消息，不可见区域用纯文本占位

```kotlin
// MessageBubble.kt 修改
@Composable
fun MessageBubble(
    message: Message,
    // ... 其他参数
    isVisible: Boolean = true, // 新增参数
) {
    // ...
    Box {
        if (isUser) {
            Text(message.content, ...)
        } else {
            // 只有可见时才渲染 Markdown
            if (isVisible) {
                MarkdownRenderer(...)
            } else {
                // 占位文本（保持高度）
                Text(
                    message.content.take(100) + "...",
                    modifier = Modifier.alpha(0f)
                )
            }
        }
    }
}

// ChatScreen.kt 传递可见性
items(messages, key = { it.id }) { message ->
    val isVisible = remember {
        derivedStateOf {
            listState.layoutInfo.visibleItemsInfo.any { it.key == message.id }
        }
    }.value
    
    MessageBubble(
        message = message,
        isVisible = isVisible,
        // ...
    )
}
```

### 优化 2：添加 Compose Stability 注解

**问题**：参数类型不稳定导致过度重组

```kotlin
// Message.kt 添加稳定性注解
@Immutable
data class Message(
    val id: String,
    val content: String,
    val role: String,
    // ...
)

// MessageBubble.kt 标记为稳定
@Stable
@Composable
fun MessageBubble(
    message: Message,
    // ...
) { ... }
```

### 优化 3：减少 State 订阅范围

**问题**：顶层 collectAsState 导致整屏重组

```kotlin
// 不好的做法（当前）
val messages by viewModel.messages.collectAsState()
// messages 变化 → 整个 ChatScreen 重组

// 优化后
@Composable
fun ChatScreen(...) {
    // 只订阅必要的状态
    val messageCount by remember {
        derivedStateOf { viewModel.messages.value.size }
    }
    
    // 消息列表独立组件，隔离重组
    MessageList(
        messagesFlow = viewModel.messages,
        // ...
    )
}

@Composable
fun MessageList(
    messagesFlow: StateFlow<List<Message>>,
    // ...
) {
    val messages by messagesFlow.collectAsState()
    LazyColumn { ... }
}
```

### 优化 4：Markdown 渲染结果缓存

**问题**：同一消息滚动进出视野时重复渲染

```kotlin
// 全局 LRU 缓存
object MarkdownCache {
    private val cache = LruCache<String, AnnotatedString>(100)
    
    fun get(content: String, isDark: Boolean): AnnotatedString? {
        return cache.get("$content:$isDark")
    }
    
    fun put(content: String, isDark: Boolean, result: AnnotatedString) {
        cache.put("$content:$isDark", result)
    }
}

// MarkdownRenderer 使用缓存
@Composable
fun MarkdownRenderer(content: String, dark: Boolean) {
    val cached = remember(content, dark) {
        MarkdownCache.get(content, dark)
    }
    
    if (cached != null) {
        Text(cached)
    } else {
        val rendered = remember(content, dark) {
            // 渲染逻辑
            val result = parseMarkdown(content, dark)
            MarkdownCache.put(content, dark, result)
            result
        }
        Text(rendered)
    }
}
```

### 优化 5：分页窗口大小调优

**问题**：窗口过大导致渲染卡顿

```kotlin
// ChatViewModel.kt 当前实现
private const val WINDOW_SIZE = 50 // 可能过大

// 优化建议
private const val INITIAL_WINDOW_SIZE = 20 // 首屏只加载 20 条
private const val PAGE_SIZE = 15 // 每次 prepend 15 条

// 根据设备性能动态调整
private val WINDOW_SIZE = if (isLowEndDevice()) 20 else 50

private fun isLowEndDevice(): Boolean {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    return am.isLowRamDevice || 
           Runtime.getRuntime().maxMemory() < 256 * 1024 * 1024
}
```

---

## 深度优化方案（需要重构）

### 优化 6：消息列表虚拟化（RecyclerView 回归）

**收益**：解决超长会话卡顿（1000+ 消息）

**方案**：LazyColumn → Compose + RecyclerView 混合

```kotlin
// 为什么考虑 RecyclerView：
// 1. RecyclerView 的 ViewHolder 复用机制更成熟
// 2. DiffUtil 增量更新比 LazyColumn 的 key 更高效
// 3. 可以精确控制 item 的创建/回收时机

// 实现思路
class MessageAdapter : ListAdapter<Message, MessageViewHolder>(MessageDiffCallback()) {
    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        // ViewHolder 内嵌 ComposeView 渲染 MessageBubble
        holder.composeView.setContent {
            MessageBubble(message = getItem(position), ...)
        }
    }
}
```

### 优化 7：流式输出节流增强

**当前实现**：
```kotlin
private var lastStreamEmitAt = 0L
private const val STREAM_EMIT_INTERVAL_NS = 16_666_666L // 约 60fps
```

**优化**：
```kotlin
// 自适应节流：根据内容长度调整
private fun getStreamInterval(contentLength: Int): Long {
    return when {
        contentLength < 100 -> 16_666_666L  // 60fps
        contentLength < 500 -> 33_333_333L  // 30fps
        else -> 50_000_000L                 // 20fps
    }
}

// 批量累积：不是每次都发射，而是累积到一定量
private val streamBuffer = StringBuilder()
private var lastEmitLength = 0

fun onStreamPartial(delta: String) {
    streamBuffer.append(delta)
    val now = System.nanoTime()
    val shouldEmit = streamBuffer.length - lastEmitLength >= 50 || // 累积 50 字符
                     now - lastStreamEmitAt > STREAM_EMIT_INTERVAL_NS
    
    if (shouldEmit) {
        _streamingText.value = streamBuffer.toString()
        lastEmitLength = streamBuffer.length
        lastStreamEmitAt = now
    }
}
```

### 优化 8：数据库索引优化

```sql
-- 检查当前索引
SELECT name, sql FROM sqlite_master WHERE type='index' AND tbl_name='messages';

-- 建议添加的复合索引
CREATE INDEX IF NOT EXISTS idx_messages_session_created 
ON messages(session_id, owner_id, created_at DESC);

-- 覆盖索引（包含常用列，避免回表）
CREATE INDEX IF NOT EXISTS idx_messages_list_query
ON messages(session_id, owner_id, created_at DESC)
INCLUDE (id, role, content, character_id);
```

---

## 性能基准测试

### 测试场景

1. **冷启动**：从点击图标到首屏可交互
2. **长会话加载**：500 条消息的会话进入时间
3. **流式输出**：1000 字生成过程的帧率
4. **滚动流畅度**：fling 滚动的平均帧率
5. **切换会话**：从会话 A 切换到会话 B 的响应时间

### 测试工具

```bash
# 1. 启动时间
adb shell am start -W com.mistbell.tavern.android/.MainActivity

# 2. 帧率监控
adb shell dumpsys gfxinfo com.mistbell.tavern.android

# 3. CPU 占用
adb shell top -m 10 -d 1 | grep tavern

# 4. 内存占用
adb shell dumpsys meminfo com.mistbell.tavern.android
```

---

## 优先级建议

### P0（立即修复）：
1. ✅ **Markdown 渲染懒加载** - 收益最大，改动最小
2. ✅ **添加 Stability 注解** - 低成本高收益
3. ✅ **流式节流优化** - 解决输入卡顿

### P1（本周完成）：
4. **减少 State 订阅范围** - 隔离重组
5. **分页窗口调优** - 平衡内存和性能
6. **Markdown 缓存** - 减少重复计算

### P2（长期优化）：
7. **数据库索引** - 优化查询性能
8. **RecyclerView 回归** - 解决超长会话

---

## 快速诊断命令

```bash
# 一键性能诊断脚本
cat > perf_diag.sh << 'EOF'
#!/bin/bash
echo "=== 帧率检测 ==="
adb shell dumpsys gfxinfo com.mistbell.tavern.android | grep -A 20 "Frame stats"

echo -e "\n=== CPU 占用 ==="
adb shell top -m 5 -n 1 | grep tavern

echo -e "\n=== 内存占用 ==="
adb shell dumpsys meminfo com.mistbell.tavern.android | grep -A 10 "App Summary"

echo -e "\n=== GC 频率 ==="
adb logcat -d -s "dalvikvm:I" "art:I" | grep -i "gc" | tail -20
EOF

chmod +x perf_diag.sh
./perf_diag.sh
```

---

## 预期改善

| 优化项 | 预期提升 | 实施难度 |
|--------|---------|---------|
| Markdown 懒加载 | 滚动帧率 +40% | 低 |
| Stability 注解 | 重组次数 -60% | 低 |
| 流式节流 | 输入响应 +50% | 低 |
| 缓存优化 | 滚动卡顿 -70% | 中 |
| 窗口调优 | 内存占用 -30% | 低 |
| RecyclerView | 超长会话流畅度 +200% | 高 |
