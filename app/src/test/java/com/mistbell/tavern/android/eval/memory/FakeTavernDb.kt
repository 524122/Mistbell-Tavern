package com.mistbell.tavern.android.eval.memory

import android.content.ContextWrapper
import androidx.room.InvalidationTracker
import androidx.room.RoomDatabase
import com.mistbell.tavern.android.data.local.AppDatabase
import com.mistbell.tavern.android.data.local.dao.ApiConfigDao
import com.mistbell.tavern.android.data.local.dao.CharacterDao
import com.mistbell.tavern.android.data.local.dao.CustomPromptDao
import com.mistbell.tavern.android.data.local.dao.ExtensionPackDao
import com.mistbell.tavern.android.data.local.dao.GlobalRegexDao
import com.mistbell.tavern.android.data.local.dao.MemoryDao
import com.mistbell.tavern.android.data.local.dao.MessageDao
import com.mistbell.tavern.android.data.local.dao.QuickReplyDao
import com.mistbell.tavern.android.data.local.dao.RulePackDao
import com.mistbell.tavern.android.data.local.dao.SessionDao
import com.mistbell.tavern.android.data.local.dao.SettingsDao
import com.mistbell.tavern.android.data.local.dao.StructuredMemoryDao
import com.mistbell.tavern.android.data.local.dao.ThemePackDao
import com.mistbell.tavern.android.data.local.dao.VectorMemoryDao
import com.mistbell.tavern.android.data.local.dao.VectorSyncOutboxDao
import com.mistbell.tavern.android.data.local.dao.WorldBookDao
import com.mistbell.tavern.android.data.local.entity.CharacterEntity
import com.mistbell.tavern.android.data.local.entity.GlobalRegexRuleEntity
import com.mistbell.tavern.android.data.local.entity.MessageEntity
import com.mistbell.tavern.android.data.local.entity.SessionEntity
import com.mistbell.tavern.android.data.local.entity.SettingsEntity
import com.mistbell.tavern.android.data.local.entity.StructuredMemoryEntity
import com.mistbell.tavern.android.data.local.entity.VectorSyncOutboxEntity
import com.mistbell.tavern.android.data.local.entity.WorldBookEntity
import com.mistbell.tavern.android.data.local.entity.WorldBookEntryEntity
import java.lang.reflect.Proxy
import java.security.SecureRandom
import java.util.concurrent.Executor

/**
 * 记忆评测用的内存数据库替身（JVM 专有）。
 *
 * 沿用项目既有写法（ChatRepositoryRoomWriteContractTest / SessionMemoryResetTest）：
 * 匿名子类 `object : AppDatabase()` + `java.lang.reflect.Proxy` 按方法名分发；每个被代理的
 * 方法名都注册了显式实现其真实语义的处理器（backed by 内存集合），没注册的方法直接抛——
 * 评测链路触及的生产代码路径变了会立刻失败，而不是静默返回默认值。
 *
 * 与既有替身的一处差异：为让生产的 `db.withTransaction`（createMemoryLocked 内）能在替身
 * 上运行，这里覆写 `beginTransaction/setTransactionSuccessful/endTransaction` 为无操作，并
 * 反射把 Room 的内部执行器字段设为直通执行器。事务原子性在内存集合上退化为顺序执行——
 * Room 的事务语义本身由真库在设备侧证据里覆盖。
 */
class FakeTavernDb {
    private val lock = Any()
    private val settings = LinkedHashMap<String, String>()
    private val sessions = LinkedHashMap<String, SessionEntity>()
    private val characters = LinkedHashMap<String, CharacterEntity>()
    private val messages = LinkedHashMap<String, MessageEntity>()
    private val memories = LinkedHashMap<Long, StructuredMemoryEntity>()
    private val worldBookEntries = LinkedHashMap<String, MutableList<WorldBookEntryEntity>>()
    private val globalRegexRules = mutableListOf<GlobalRegexRuleEntity>()
    private val outbox = LinkedHashMap<Long, VectorSyncOutboxEntity>()
    private var memoryIdCounter = 0L
    private var outboxIdCounter = 0L
    private val random = SecureRandom()

    // ---- 小工具 ----

    private fun newVectorRevision(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun <T> flowOf(provider: () -> List<T>): kotlinx.coroutines.flow.Flow<List<T>> =
        kotlinx.coroutines.flow.flow { emit(synchronized(lock) { provider() }) }

    private fun <T> nullableFlowOf(provider: () -> T?): kotlinx.coroutines.flow.Flow<T?> =
        kotlinx.coroutines.flow.flow { emit(synchronized(lock) { provider() }) }

    private fun owned(
        entity: SessionEntity,
        ownerId: Any?,
        characterId: Any?,
    ): Boolean = entity.ownerId == ownerId && entity.characterId == characterId

    /** SQL LIKE（%term%）语义：去包装后不区分大小写包含；`~~nomatch~~%` 不匹配真实内容 */
    private fun likeMatches(
        content: String,
        pattern: String,
    ): Boolean {
        if (!pattern.startsWith("%") || !pattern.endsWith("%")) return false
        val term = pattern.removePrefix("%").removeSuffix("%")
        return content.contains(term, ignoreCase = true)
    }

    /**
     * 与生产 DAO 的 ORDER BY importance DESC, created_at DESC 同序。时间戳并列时按 id **降序**定序，
     * 方向与「新的在前」一致：记忆的 createdAt 由生产代码取 Instant.now()，同一批里先后保存的两条
     * 只隔几微秒，而这台机器上 Instant.now() 约十几微秒才变一次，两条偶尔会拿到完全相同的时间戳。
     * 并列时若不定序（或反向定序），排在入选边界上的那一条会在两次运行之间翻转，评测数字不可复现。
     */
    private fun importanceOrder() =
        compareByDescending<StructuredMemoryEntity> { it.importance }
            .thenByDescending { it.createdAt }
            .thenByDescending { it.id }

    private fun messagesFor(
        sessionId: String,
        ownerId: String,
    ): List<MessageEntity> =
        messages.values
            .filter { it.sessionId == sessionId && it.ownerId == ownerId }
            .sortedBy { it.createdAt }

    private fun matchingSource(
        sourceType: String,
        ownerId: String,
        sessionId: String,
        sourceId: String,
    ): List<VectorSyncOutboxEntity> =
        outbox.values.filter {
            it.sourceType == sourceType && it.ownerId == ownerId && it.sessionId == sessionId && it.sourceId == sourceId
        }

    // ---- 替身库 ----

    /** 未注册的方法直接失败（代码路径变了要显式更新替身，不静默返回默认值） */
    private fun <T : Any> daoProxy(
        iface: Class<T>,
        handlers: Map<String, (Array<Any?>) -> Any?>,
    ): T =
        Proxy.newProxyInstance(
            iface.classLoader,
            arrayOf(iface),
        ) { _, method, args ->
            val handler =
                handlers[method.name]
                    ?: throw UnsupportedOperationException("${iface.simpleName}.${method.name} 未在评测链路注册")
            synchronized(lock) { handler(args ?: emptyArray()) }
        } as T

    /** 替身库（匿名子类只覆写 DAO 访问器与事务方法；未覆写的 Room 内部一概不触） */
    fun db(): AppDatabase {
        val database =
            object : AppDatabase() {
                override fun characterDao(): CharacterDao = daoProxy(CharacterDao::class.java, characterHandlers())

                override fun sessionDao(): SessionDao = daoProxy(SessionDao::class.java, sessionHandlers())

                override fun messageDao(): MessageDao = daoProxy(MessageDao::class.java, messageHandlers())

                override fun worldBookDao(): WorldBookDao = daoProxy(WorldBookDao::class.java, worldBookHandlers())

                override fun settingsDao(): SettingsDao = daoProxy(SettingsDao::class.java, settingsHandlers())

                override fun memoryDao(): MemoryDao = unsupportedDao("memoryDao")

                override fun structuredMemoryDao(): StructuredMemoryDao =
                    daoProxy(StructuredMemoryDao::class.java, structuredMemoryHandlers())

                override fun vectorMemoryDao(): VectorMemoryDao = unsupportedDao("vectorMemoryDao")

                override fun vectorSyncOutboxDao(): VectorSyncOutboxDao = outboxDaoProxy()

                override fun themePackDao(): ThemePackDao = unsupportedDao("themePackDao")

                override fun apiConfigDao(): ApiConfigDao = apiConfigDaoProxy()

                override fun customPromptDao(): CustomPromptDao = unsupportedDao("customPromptDao")

                override fun rulePackDao(): RulePackDao = unsupportedDao("rulePackDao")

                override fun quickReplyDao(): QuickReplyDao = unsupportedDao("quickReplyDao")

                override fun globalRegexDao(): GlobalRegexDao = globalRegexDaoProxy()

                override fun extensionPackDao(): ExtensionPackDao = unsupportedDao("extensionPackDao")

                override fun createInvalidationTracker(): InvalidationTracker = unsupportedDao("InvalidationTracker")

                override fun clearAllTables() = throw UnsupportedOperationException("clearAllTables 未在评测链路使用")

                // 事务桥：生产 createMemoryLocked 走 db.withTransaction；替身上退化为顺序执行
                override fun beginTransaction() = Unit

                override fun setTransactionSuccessful() = Unit

                override fun endTransaction() = Unit
            }
        installDirectExecutors(database)
        return database
    }

    private fun unsupportedDao(name: String): Nothing = throw unsupportedDaoError(name)

    private fun unsupportedDaoError(name: String) = UnsupportedOperationException("$name 未在评测链路使用")

    private fun outboxDaoProxy() = daoProxy(VectorSyncOutboxDao::class.java, outboxHandlers())

    /**
     * api_configs 替身：恒返回 null（空表）。这是生产 SettingsRepository 的回退语义——
     * 没有默认配置时 LLM 三元组改读 llm_* 平铺键，聊天链路测试据此用 settings 直配假 LLM 地址。
     */
    private fun apiConfigDaoProxy() =
        daoProxy(
            ApiConfigDao::class.java,
            mapOf(
                "getDefault" to { _ -> null },
                "getById" to { _ -> null },
            ),
        )

    private fun globalRegexDaoProxy() = daoProxy(GlobalRegexDao::class.java, globalRegexHandlers())

    /**
     * Room 2.8 的 `withTransaction` 无嵌套事务上下文时会经 `transactionExecutor` 起事务协程；
     * 替身库从不执行 `RoomDatabase.init`，该字段未初始化——反射置为直通执行器，使事务块
     * 直接在当前线程组运行（配合上面的 begin/end 无操作覆写）。
     */
    private fun installDirectExecutors(database: AppDatabase) {
        val direct = Executor { it.run() }
        listOf("internalQueryExecutor", "internalTransactionExecutor").forEach { name ->
            val field = RoomDatabase::class.java.declaredFields.firstOrNull { it.name == name } ?: return@forEach
            field.isAccessible = true
            field.set(database, direct)
        }
    }

    // ---- 各 DAO 的处理器表（方法名 → 真实语义的内存实现） ----

    private fun settingsHandlers(): Map<String, (Array<Any?>) -> Any?> =
        mapOf(
            "getValue" to { args -> settings[args[0] as String] },
            "getAll" to { _ -> flowOf { settings.map { (k, v) -> SettingsEntity(k, v) } } },
            "observeValue" to { args -> nullableFlowOf { settings[args[0] as String] } },
            "upsert" to { args ->
                val entity = args[0] as SettingsEntity
                settings[entity.key] = entity.value
                null
            },
            "deleteByKey" to { args ->
                settings.remove(args[0] as String)
                null
            },
            "deleteAll" to { _ ->
                settings.clear()
                null
            },
        )

    private fun sessionHandlers() = sessionQueryHandlers() + sessionMutationHandlers()

    private fun sessionQueryHandlers(): Map<String, (Array<Any?>) -> Any?> =
        mapOf(
            "get" to { args -> sessions[args[0] as String]?.takeIf { owned(it, args[1], args[2]) } },
            "getById" to { args -> sessions[args[0] as String] },
            "getByCharacter" to { args ->
                flowOf {
                    sessions.values
                        .filter { owned(it, args[0], args[1]) }
                        .sortedByDescending { it.updatedAt }
                }
            },
            "getLatestByCharacter" to { args ->
                sessions.values
                    .filter { owned(it, args[0], args[1]) }
                    .maxByOrNull { it.updatedAt }
            },
            "getRecent" to { args ->
                flowOf {
                    sessions.values
                        .filter { it.ownerId == args[0] }
                        .sortedByDescending { if (it.isPinned) it.pinnedAt ?: it.updatedAt else it.updatedAt }
                }
            },
            "observeById" to { args -> nullableFlowOf { sessions[args[0] as String] } },
            "getAllOnce" to { _ -> sessions.values.toList() },
            "countAll" to { _ -> sessions.size },
            "allSessionIds" to { _ -> sessions.keys.toList() },
            "sessionIdsByOwner" to { args -> sessions.values.filter { it.ownerId == args[0] }.map { it.id } },
            "sessionIdsByCharacter" to { args ->
                sessions.values.filter { owned(it, args[0], args[1]) }.map { it.id }
            },
        )

    private fun sessionMutationHandlers(): Map<String, (Array<Any?>) -> Any?> =
        mapOf(
            "upsert" to { args ->
                val session = args[0] as SessionEntity
                sessions[session.id] = session
                null
            },
            "updateWiState" to { args ->
                sessions[args[0] as String]
                    ?.takeIf { owned(it, args[1], args[2]) }
                    ?.let { entity -> sessions[entity.id] = entity.copy(wiStateJson = args[3] as String) }
                null
            },
            "updateTitle" to { args ->
                sessions[args[0] as String]?.let { entity ->
                    sessions[entity.id] = entity.copy(title = args[3] as String, updatedAt = args[4] as String)
                }
                null
            },
            "updateAuthorNote" to { args ->
                sessions[args[0] as String]?.let { entity ->
                    sessions[entity.id] = entity.copy(authorNote = args[3] as String)
                }
                null
            },
            "updateLtmEnabled" to { args ->
                sessions[args[0] as String]?.let { entity ->
                    sessions[entity.id] = entity.copy(enableLongTermMemory = args[3] as Boolean?)
                }
                null
            },
            "updateContextTokenLimit" to { args ->
                sessions[args[0] as String]?.let { entity ->
                    sessions[entity.id] = entity.copy(contextTokenLimit = args[3] as Int?)
                }
                null
            },
            "delete" to { args ->
                sessions[args[0] as String]?.takeIf { owned(it, args[1], args[2]) }?.let { sessions.remove(it.id) }
                null
            },
        )

    private fun messageHandlers() = messageQueryHandlers() + messageMutationHandlers()

    private fun messageQueryHandlers(): Map<String, (Array<Any?>) -> Any?> =
        mapOf(
            "getBySession" to { args -> flowOf { messagesFor(args[0] as String, args[1] as String) } },
            "getLatestBySession" to { args ->
                flowOf {
                    messagesFor(args[0] as String, args[1] as String)
                        .sortedWith(compareBy<MessageEntity> { it.createdAt }.thenBy { it.id })
                        .let { all -> all.takeLast(args[2] as Int) } // DESC+LIMIT 的 ASC 展示序
                }
            },
            "getById" to { args ->
                messages[args[0] as String]?.takeIf { it.sessionId == args[1] && it.ownerId == args[2] }
            },
            "getAnyById" to { args -> messages[args[0] as String] },
            "latestIdsBySession" to { args ->
                messagesFor(args[0] as String, args[1] as String)
                    .sortedByDescending { it.createdAt }
                    .take(args[2] as Int)
                    .map { it.id }
            },
            "searchByContentTermsInSessions" to { args -> searchMessages(args) },
            "idsBySession" to { args -> messagesFor(args[0] as String, args[1] as String).map { it.id } },
            "existingIds" to { args ->
                @Suppress("UNCHECKED_CAST")
                (args[0] as List<String>).filter { messages.containsKey(it) }
            },
            "countAll" to { _ -> messages.size },
            "getAllOnce" to { _ -> messages.values.toList() },
            "getRecentByOwner" to { args ->
                messages.values
                    .filter { it.ownerId == args[0] }
                    .sortedByDescending { it.createdAt }
                    .take(args[1] as Int)
            },
            "getUnreadCount" to { args -> messagesFor(args[0] as String, args[1] as String).count { !it.isRead } },
        )

    private fun messageMutationHandlers(): Map<String, (Array<Any?>) -> Any?> =
        mapOf(
            "upsert" to { args ->
                val message = args[0] as MessageEntity
                messages[message.id] = message
                null
            },
            "upsertAll" to { args ->
                @Suppress("UNCHECKED_CAST")
                (args[0] as List<MessageEntity>).forEach { messages[it.id] = it }
                null
            },
            "ensureVectorRevision" to { args ->
                val entity = messages[args[0] as String]
                if (entity != null && entity.vectorRevision.isBlank()) {
                    messages[entity.id] = entity.copy(vectorRevision = newVectorRevision())
                }
                null
            },
            "deleteBySession" to { args ->
                messagesFor(args[0] as String, args[1] as String).forEach { messages.remove(it.id) }
                null
            },
            "deleteById" to { args ->
                messages.remove(args[0] as String)
                null
            },
            "deleteByIds" to { args ->
                @Suppress("UNCHECKED_CAST")
                val ids = args[0] as Collection<String>
                ids.count { messages.remove(it) != null }
            },
            "deleteAfter" to { args -> deleteMessagesAfter(args) },
            "markAsRead" to { args ->
                messagesFor(args[0] as String, args[1] as String)
                    .filter { !it.isRead }
                    .forEach { messages[it.id] = it.copy(isRead = true) }
                null
            },
        )

    /** searchByContentTermsInSessions：LIKE 六词位 OR + 会话/owner/排除集过滤，createdAt DESC LIMIT */
    private fun searchMessages(args: Array<Any?>): List<MessageEntity> {
        val sessionIds = args[0] as List<String>
        val excludeSessionId = args[1] as String?
        val ownerId = args[2] as String
        val characterId = args[3] as String?
        val excludedIds = args[4] as List<String>
        val terms = listOf(args[5], args[6], args[7], args[8], args[9], args[10]).map { it as String }
        val resultLimit = args[11] as Int
        return messages.values
            .filter { it.ownerId == ownerId }
            .filter { it.sessionId in sessionIds }
            .filter { characterId == null || it.characterId == characterId }
            .filter { excludeSessionId == null || it.sessionId != excludeSessionId }
            .filter { it.id !in excludedIds }
            .filter { entity -> terms.any { likeMatches(entity.content, it) } }
            .sortedByDescending { it.createdAt }
            .take(resultLimit)
    }

    /** deleteAfter：与 SQL 同语义——目标行不存在时不删任何消息；同时间戳按插入序（插入序 ≈ rowid） */
    private fun deleteMessagesAfter(args: Array<Any?>): Int =
        deleteMessagesAfterTarget(args[1] as String, args[0] as String, args[2] as String)

    private fun deleteMessagesAfterTarget(
        targetId: String,
        sessionId: String,
        ownerId: String,
    ): Int {
        val target = messages[targetId] ?: return 0
        val entries = messages.entries.toList()
        val boundary = entries.indexOfFirst { it.key == target.id }
        val following =
            entries
                .drop(boundary + 1)
                .filter { it.value.sessionId == sessionId }
                .filter { it.value.ownerId == ownerId }
                .filter { it.value.createdAt > target.createdAt }
        following.forEach { messages.remove(it.key) }
        return following.size
    }

    private fun characterHandlers(): Map<String, (Array<Any?>) -> Any?> =
        mapOf(
            "getById" to { args -> characters[args[0] as String] },
            "getByIds" to { args ->
                @Suppress("UNCHECKED_CAST")
                val ids = args[0] as List<String>
                characters.values.filter { it.id in ids }
            },
            "upsert" to { args ->
                val character = args[0] as CharacterEntity
                characters[character.id] = character
                null
            },
            "upsertAll" to { args ->
                @Suppress("UNCHECKED_CAST")
                (args[0] as List<CharacterEntity>).forEach { characters[it.id] = it }
                null
            },
            "getAll" to { _ -> flowOf { characters.values.toList() } },
            "getAllOnce" to { _ -> characters.values.toList() },
            "deleteById" to { args ->
                characters.remove(args[0] as String)
                null
            },
            "deleteAll" to { _ ->
                characters.clear()
                null
            },
        )

    private fun globalRegexHandlers(): Map<String, (Array<Any?>) -> Any?> =
        mapOf(
            "getAll" to { _ -> globalRegexRules.toList() },
            "observeAll" to { _ -> flowOf { globalRegexRules.toList() } },
            "upsertAll" to { args ->
                @Suppress("UNCHECKED_CAST")
                (args[0] as List<GlobalRegexRuleEntity>).forEach { entity ->
                    globalRegexRules.removeAll { it.id == entity.id }
                    globalRegexRules.add(entity)
                }
                null
            },
            "deleteAll" to { _ ->
                globalRegexRules.clear()
                null
            },
            "replaceAll" to { args ->
                @Suppress("UNCHECKED_CAST")
                globalRegexRules.clear()
                globalRegexRules.addAll(args[0] as List<GlobalRegexRuleEntity>)
                null
            },
        )

    private fun worldBookHandlers(): Map<String, (Array<Any?>) -> Any?> =
        mapOf(
            "getEntriesList" to { args -> worldBookEntries[args[0] as String].orEmpty().toList() },
            "getEntries" to { args ->
                flowOf { worldBookEntries[args[0] as String].orEmpty().toList() }
            },
            "getEntryById" to { args -> worldBookEntries.values.flatten().firstOrNull { it.id == args[0] } },
            "upsertEntries" to { args ->
                @Suppress("UNCHECKED_CAST")
                (args[0] as List<WorldBookEntryEntity>).forEach { entry ->
                    worldBookEntries
                        .getOrPut(entry.bookId) { mutableListOf() }
                        .removeAll { it.id == entry.id }
                    worldBookEntries.getValue(entry.bookId).add(entry)
                }
                null
            },
            "getAll" to { _ -> flowOf { emptyList<WorldBookEntity>() } },
            "getById" to { _ -> null },
            "getAllBooksOnce" to { _ -> emptyList<WorldBookEntity>() },
        )

    private fun structuredMemoryHandlers(): Map<String, (Array<Any?>) -> Any?> =
        memoryReadHandlers() + memoryWriteHandlers() + memoryStatHandlers()

    private fun memoryReadHandlers() = memoryLookupHandlers() + memoryFlowHandlers()

    private fun memoryLookupHandlers(): Map<String, (Array<Any?>) -> Any?> =
        mapOf(
            "getById" to { args -> memories[args[0] as Long] },
            "getByOwnerAndSession" to { args ->
                memories.values.filter { it.ownerId == args[0] && it.sessionId == args[1] }
            },
            "getPromptCandidatesInSessions" to { args ->
                @Suppress("UNCHECKED_CAST")
                val sessionIds = args[1] as List<String>
                memories.values
                    .filter { it.ownerId == args[0] && it.sessionId in sessionIds }
                    .sortedWith(importanceOrder())
                    .take(args[2] as Int)
            },
            "getPromptCandidatesUnassigned" to { args ->
                memories.values
                    .filter { it.ownerId == args[0] && it.sessionId == null }
                    .sortedWith(importanceOrder())
                    .take(args[1] as Int)
            },
        )

    private fun memoryFlowHandlers(): Map<String, (Array<Any?>) -> Any?> =
        mapOf(
            "getVisibleInSession" to { args ->
                flowOf {
                    memories.values
                        .filter { it.ownerId == args[0] && it.sessionId == args[2] }
                        .sortedByDescending { it.createdAt }
                }
            },
            "getAll" to { args ->
                flowOf {
                    memories.values
                        .filter { it.ownerId == args[0] }
                        .sortedByDescending { it.createdAt }
                }
            },
            "getByCharacter" to { args ->
                flowOf {
                    memories.values
                        .filter { it.ownerId == args[0] && it.characterId == args[1] }
                        .sortedByDescending { it.createdAt }
                }
            },
            "getByType" to { args ->
                flowOf {
                    memories.values
                        .filter { it.ownerId == args[0] && it.memoryType == args[1] }
                        .sortedWith(importanceOrder())
                }
            },
            "getByImportance" to { args ->
                flowOf {
                    memories.values
                        .filter { it.ownerId == args[0] && it.importance >= (args[1] as Int) }
                        .sortedWith(importanceOrder())
                }
            },
            "search" to { args ->
                flowOf {
                    memories.values
                        .filter { searchMemoryMatches(it, args[1] as String) }
                        .sortedWith(importanceOrder())
                }
            },
        )

    private fun memoryWriteHandlers(): Map<String, (Array<Any?>) -> Any?> =
        mapOf(
            "insert" to { args -> insertMemory(args[0] as StructuredMemoryEntity) },
            "update" to { args ->
                val memory = args[0] as StructuredMemoryEntity
                if (memories.containsKey(memory.id)) memories[memory.id] = memory
                null
            },
            "ensureVectorRevision" to { args ->
                val id = args[0] as Long
                val entity = memories[id]
                if (entity != null && entity.vectorRevision.isBlank()) {
                    memories[id] = entity.copy(vectorRevision = newVectorRevision())
                }
                null
            },
            "delete" to { args ->
                memories.remove((args[0] as StructuredMemoryEntity).id)
                null
            },
            "deleteById" to { args ->
                memories.remove(args[0] as Long)
                null
            },
            "deleteByCharacter" to { args ->
                memories.values
                    .filter { it.ownerId == args[0] && it.characterId == args[1] }
                    .forEach { memories.remove(it.id) }
                null
            },
            "deleteBySession" to { args ->
                memories.values
                    .filter { it.ownerId == args[0] && it.sessionId == args[1] }
                    .forEach { memories.remove(it.id) }
                null
            },
            "deleteByOwner" to { args ->
                memories.values
                    .filter { it.ownerId == args[0] }
                    .forEach { memories.remove(it.id) }
                null
            },
            "deleteAllStructuredMemory" to { _ ->
                memories.clear()
                null
            },
            "deleteAllVectorSyncOutbox" to { _ ->
                outbox.clear()
                null
            },
            "deleteAll" to { _ ->
                memories.clear()
                outbox.clear()
                null
            },
            "incrementAccessCount" to { args -> incrementAccess(args) },
        )

    private fun memoryStatHandlers(): Map<String, (Array<Any?>) -> Any?> =
        mapOf(
            "getCount" to { args -> memories.values.count { it.ownerId == args[0] } },
            "getCountByCharacter" to { args ->
                memories.values.count { it.ownerId == args[0] && it.characterId == args[1] }
            },
            "getSummariesForReindex" to { args ->
                memories.values
                    .filter { it.ownerId == args[0] && it.importance >= 7 && it.sessionId != null }
                    .sortedByDescending { it.updatedAt }
                    .take(args[1] as Int)
            },
            "getAllOnce" to { _ -> memories.values.toList() },
        )

    private fun insertMemory(memory: StructuredMemoryEntity): Long =
        if (memory.id > 0L) {
            memories[memory.id] = memory
            memory.id
        } else {
            memoryIdCounter += 1
            memories[memoryIdCounter] = memory.copy(id = memoryIdCounter)
            memoryIdCounter
        }

    private fun searchMemoryMatches(
        entity: StructuredMemoryEntity,
        query: String,
    ): Boolean = (entity.title ?: "").contains(query) || entity.content.contains(query)

    /** incrementAccessCount 的单条/批量双签名：第 1 参为 Long 时是单条，List 时是批量 */
    private fun incrementAccess(args: Array<Any?>): Any? {
        val accessedAt = args[1] as String
        val ids =
            if (args[0] is Long) {
                listOf(args[0] as Long)
            } else {
                @Suppress("UNCHECKED_CAST")
                args[0] as List<Long>
            }
        ids.forEach { id ->
            memories[id]?.let { entity ->
                memories[id] = entity.copy(accessCount = entity.accessCount + 1, lastAccessedAt = accessedAt)
            }
        }
        return null
    }

    private fun outboxHandlers(): Map<String, (Array<Any?>) -> Any?> =
        mapOf(
            "insert" to { args ->
                val operation = args[0] as VectorSyncOutboxEntity
                outboxIdCounter += 1
                outbox[outboxIdCounter] = operation.copy(operationId = outboxIdCounter)
                outboxIdCounter
            },
            "getById" to { args -> outbox[args[0] as Long] },
            "due" to { args ->
                outbox.values
                    .filter { it.nextAttemptAt <= (args[0] as Long) }
                    .sortedBy { it.operationId }
                    .take(args[1] as Int)
            },
            "latestForSource" to { args ->
                matchingSource(args[0] as String, args[1] as String, args[2] as String, args[3] as String)
                    .maxByOrNull { it.operationId }
            },
            "dueForSource" to { args ->
                matchingSource(args[0] as String, args[1] as String, args[2] as String, args[3] as String)
                    .filter { it.nextAttemptAt <= (args[4] as Long) }
                    .sortedBy { it.operationId }
            },
            "findEquivalent" to { args ->
                matchingSource(args[0] as String, args[1] as String, args[2] as String, args[3] as String)
                    .filter { it.operation == args[4] && it.sourceRevision == args[5] }
                    .maxByOrNull { it.operationId }
            },
            "markRetry" to { args ->
                outbox[args[0] as Long]?.let { entity ->
                    outbox[entity.operationId] =
                        entity.copy(
                            attemptCount = entity.attemptCount + 1,
                            nextAttemptAt = args[1] as Long,
                            lastError = args[2] as String?,
                        )
                }
                null
            },
            "deleteById" to { args -> if (outbox.remove(args[0] as Long) != null) 1 else 0 },
            "acknowledgeThrough" to { args ->
                val through = args[4] as Long
                matchingSource(args[0] as String, args[1] as String, args[2] as String, args[3] as String)
                    .filter { it.operationId <= through }
                    .count { outbox.remove(it.operationId) != null }
            },
            "deleteAll" to { _ ->
                outbox.clear()
                null
            },
            "count" to { _ -> outbox.size },
            "getAll" to { _ -> outbox.values.sortedBy { it.operationId } },
        )
}

/** JVM 评测用的极小 Context（生产类只需要它的存在性，从不真正调用其方法） */
internal class EvalContext : ContextWrapper(null)
