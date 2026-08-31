package com.mistbell.tavern.android.test

import android.content.Context
import android.util.Log
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.api.model.StructuredMemory
import com.mistbell.tavern.android.data.repository.StructuredMemoryRepository
import com.mistbell.tavern.android.data.repository.retrieveForConversation
import kotlinx.coroutines.delay
import java.time.Instant

/**
 * 向量记忆系统测试工具
 *
 * 用于验证向量存储、Embedding 服务和记忆检索功能
 */
object VectorMemoryTest {
    private const val TAG = "VectorMemoryTest"

    /**
     * 测试1: 基础向量存储
     */
    suspend fun testVectorStore() {
        Log.d(TAG, "========== Test 1: 基础向量存储 ==========")

        try {
            val vectorStore = TavernApplication.instance.vectorStore

            // 添加测试向量
            val testVector = FloatArray(1536) { it.toFloat() / 1536f }
            vectorStore.add(
                id = "test_vector_1",
                vector = testVector,
                metadata =
                    mapOf(
                        "content" to "这是测试内容",
                        "owner_id" to "test_user",
                        "character_id" to "test_char",
                        "session_id" to "test_session",
                    ),
            )

            Log.d(TAG, "✓ 向量添加成功")

            // 检索向量
            val results =
                vectorStore.search(
                    queryVector = testVector,
                    topK = 5,
                    filters = mapOf("owner_id" to "test_user"),
                )

            Log.d(TAG, "✓ 找到 ${results.size} 个结果")
            results.forEach { result ->
                Log.d(TAG, "  - ${result.content} (相似度: ${result.score})")
            }

            // 验证
            if (results.size == 1 && results[0].score > 0.99f) {
                Log.d(TAG, "✅ 测试1通过：向量存储正常工作")
            } else {
                Log.e(TAG, "❌ 测试1失败：相似度不正确")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ 测试1失败：${e.message}", e)
        }
    }

    /**
     * 测试2: Embedding 服务
     */
    suspend fun testEmbeddingService() {
        Log.d(TAG, "========== Test 2: Embedding 服务 ==========")

        try {
            val embeddingService = TavernApplication.instance.embeddingService

            // 生成 Embedding
            val text = "这是一个测试文本"
            val embedding = embeddingService.embed(text)

            Log.d(TAG, "✓ Embedding 生成成功")
            Log.d(TAG, "  - 维度: ${embedding.size}")
            Log.d(TAG, "  - 前5个值: ${embedding.take(5).joinToString(", ")}")

            // 验证
            if (embedding.size == 1536) {
                Log.d(TAG, "✅ 测试2通过：Embedding 服务正常工作")
            } else {
                Log.e(TAG, "❌ 测试2失败：维度不正确")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ 测试2失败：${e.message}", e)
        }
    }

    /**
     * 测试3: 向量记忆服务
     */
    suspend fun testVectorMemoryService() {
        Log.d(TAG, "========== Test 3: 向量记忆服务 ==========")

        try {
            val vectorMemoryService = TavernApplication.instance.vectorMemoryService

            // 存储测试消息
            val messageId1 = "test_msg_1"
            vectorMemoryService.storeMessage(
                content = "我是一名软件工程师",
                ownerId = "test_user",
                characterId = "test_char",
                sessionId = "test_session",
                messageId = messageId1,
                contentType = "user_message", // VectorMemory.ContentType.USER_MESSAGE
            )

            Log.d(TAG, "✓ 消息1存储成功")

            val messageId2 = "test_msg_2"
            vectorMemoryService.storeMessage(
                content = "我喜欢编程和技术",
                ownerId = "test_user",
                characterId = "test_char",
                sessionId = "test_session",
                messageId = messageId2,
                contentType = "user_message", // VectorMemory.ContentType.USER_MESSAGE
            )

            Log.d(TAG, "✓ 消息2存储成功")

            // 等待向量化完成
            delay(500)

            // 语义检索
            val results =
                vectorMemoryService.searchRelevantMemories(
                    query = "你知道我的职业吗？",
                    ownerId = "test_user",
                    characterId = "test_char",
                    sessionId = "test_session",
                    topK = 5,
                )

            Log.d(TAG, "✓ 找到 ${results.size} 个相关记忆")
            results.forEach { result ->
                Log.d(TAG, "  - ${result.content} (相似度: ${result.score})")
            }

            // 验证
            if (results.isNotEmpty()) {
                Log.d(TAG, "✅ 测试3通过：向量记忆服务正常工作")
            } else {
                Log.e(TAG, "❌ 测试3失败：未找到相关记忆")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ 测试3失败：${e.message}", e)
        }
    }

    /**
     * 测试4: 结构化记忆检索
     */
    suspend fun testStructuredMemoryRetrieval(context: Context) {
        Log.d(TAG, "========== Test 4: 结构化记忆检索 ==========")

        try {
            val structuredMemoryRepo = StructuredMemoryRepository(context)

            // 创建测试记忆
            val memory =
                StructuredMemory(
                    id = 0,
                    ownerId = "test_user",
                    characterId = "test_char",
                    sessionId = null,
                    title = "用户职业信息",
                    content = "用户是一名资深软件工程师，专注于 Android 开发",
                    memoryType = "character_info",
                    importance = 9,
                    tags = listOf("职业", "技能"),
                    keywords = listOf("软件工程师", "Android"),
                    structuredData = null,
                    createdAt = Instant.now().toString(),
                    updatedAt = Instant.now().toString(),
                    lastAccessedAt = null,
                    accessCount = 0,
                    relatedMessageIds = emptyList(),
                    sourceType = "manual",
                )

            val memoryId = structuredMemoryRepo.createMemory(memory)
            Log.d(TAG, "✓ 创建记忆成功：ID=$memoryId")

            // 等待向量同步
            delay(500)

            // 检索记忆
            val retrieved =
                structuredMemoryRepo.retrieveForConversation(
                    ownerId = "test_user",
                    characterId = "test_char",
                    userMessage = "我的工作是什么？",
                )

            Log.d(TAG, "✓ 检索到 ${retrieved.size} 条记忆")
            retrieved.forEach { mem ->
                Log.d(TAG, "  - ${mem.content} (重要性: ${mem.importance})")
            }

            // 检查向量同步
            val vectorCount = TavernApplication.instance.vectorStore.count()
            Log.d(TAG, "  - 向量库中共有 $vectorCount 个向量")

            // 验证
            if (retrieved.isNotEmpty() && retrieved.any { it.id == memoryId }) {
                Log.d(TAG, "✅ 测试4通过：结构化记忆检索正常工作")
            } else {
                Log.e(TAG, "❌ 测试4失败：未检索到创建的记忆")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ 测试4失败：${e.message}", e)
        }
    }

    /**
     * 测试5: 单会话检索（不跨会话）
     */
    suspend fun testSingleSessionRetrieval() {
        Log.d(TAG, "========== Test 5: 单会话检索 ==========")

        try {
            val vectorMemoryService = TavernApplication.instance.vectorMemoryService

            // 会话A存储消息
            vectorMemoryService.storeMessage(
                content = "我喜欢登山",
                ownerId = "test_user",
                characterId = "test_char",
                sessionId = "session_A",
                messageId = "msg_a_1",
                contentType = "user_message", // VectorMemory.ContentType.USER_MESSAGE
            )

            Log.d(TAG, "✓ 会话A存储消息")

            // 会话B存储消息
            vectorMemoryService.storeMessage(
                content = "我喜欢游泳",
                ownerId = "test_user",
                characterId = "test_char",
                sessionId = "session_B",
                messageId = "msg_b_1",
                contentType = "user_message", // VectorMemory.ContentType.USER_MESSAGE
            )

            Log.d(TAG, "✓ 会话B存储消息")

            delay(500)

            // 在会话B中检索（不应该找到会话A的消息）
            val resultsB =
                vectorMemoryService.searchRelevantMemories(
                    query = "我有什么爱好？",
                    ownerId = "test_user",
                    characterId = "test_char",
                    sessionId = "session_B", // 限定会话B
                    topK = 5,
                )

            Log.d(TAG, "✓ 会话B检索结果：")
            resultsB.forEach { result ->
                Log.d(TAG, "  - ${result.content} (sessionId: ${result.metadata["session_id"]})")
            }

            // 验证：应该只找到会话B的消息
            val hasSessionA = resultsB.any { it.metadata["session_id"] == "session_A" }
            val hasSessionB = resultsB.any { it.metadata["session_id"] == "session_B" }

            if (!hasSessionA && hasSessionB) {
                Log.d(TAG, "✅ 测试5通过：单会话检索正常工作（不跨会话）")
            } else {
                Log.e(TAG, "❌ 测试5失败：检索到了其他会话的消息")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ 测试5失败：${e.message}", e)
        }
    }

    /**
     * 运行所有测试
     */
    suspend fun runAllTests(context: Context) {
        Log.d(TAG, "========================================")
        Log.d(TAG, "开始运行向量记忆系统测试")
        Log.d(TAG, "========================================")

        testVectorStore()
        delay(1000)

        testEmbeddingService()
        delay(1000)

        testVectorMemoryService()
        delay(1000)

        testStructuredMemoryRetrieval(context)
        delay(1000)

        testSingleSessionRetrieval()
        delay(1000)

        Log.d(TAG, "========================================")
        Log.d(TAG, "测试完成")
        Log.d(TAG, "========================================")
    }
}
