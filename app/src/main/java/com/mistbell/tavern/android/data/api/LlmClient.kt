package com.mistbell.tavern.android.data.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

@Serializable
data class ChatMessage(
    val role: String,
    val content: String
)

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.8,
    @SerialName("max_tokens") val maxTokens: Int = 1024,
    val stream: Boolean = false
)

@Serializable
data class ChatCompletionResponse(
    val choices: List<Choice> = emptyList()
) {
    @Serializable
    data class Choice(
        val message: ChatChoiceMessage? = null,
        val delta: ChatChoiceMessage? = null
    )

    @Serializable
    data class ChatChoiceMessage(
        val role: String = "",
        val content: String = ""
    )
}

// F1: SSE 流式 chunk 模型
@Serializable
data class ChatCompletionChunk(
    val id: String? = null,
    val choices: List<ChunkChoice> = emptyList()
)

@Serializable
data class ChunkChoice(
    val delta: Delta? = null,
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class Delta(
    val role: String? = null,
    val content: String? = null
)

/**
 * SSE data 行解析器（纯函数，供单测）。
 * 规则: "[DONE]"→null; 坏 JSON→null; choices 空→null; delta.content 空白→null; 否则返回 content。
 */
object SseParser {
    fun contentDelta(dataLine: String): String? {
        val trimmed = dataLine.trim()
        if (trimmed == "[DONE]") return null
        val chunk = try {
            json.decodeFromString(ChatCompletionChunk.serializer(), trimmed)
        } catch (_: Exception) {
            return null // 坏 JSON
        }
        val choice = chunk.choices.firstOrNull() ?: return null
        val content = choice.delta?.content ?: return null
        if (content.isBlank()) return null
        return content
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
}

object LlmClient {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private const val MAX_RETRIES = 3
    private const val INITIAL_RETRY_DELAY_MS = 1000L

    suspend fun chat(config: LlmConfig, messages: List<ChatMessage>): String {
        return withContext(Dispatchers.IO) {
            var lastException: Exception? = null

            repeat(MAX_RETRIES) { attempt ->
                try {
                    return@withContext executeChatRequest(config, messages)
                } catch (e: SocketTimeoutException) {
                    lastException = e
                    if (attempt < MAX_RETRIES - 1) {
                        val delayMs = INITIAL_RETRY_DELAY_MS * (1 shl attempt) // 指数退避：1s, 2s, 4s
                        android.util.Log.w("LlmClient", "Request timeout, retry ${attempt + 1}/$MAX_RETRIES after ${delayMs}ms")
                        delay(delayMs)
                    }
                } catch (e: IOException) {
                    lastException = e
                    // 网络错误可重试
                    if (attempt < MAX_RETRIES - 1) {
                        val delayMs = INITIAL_RETRY_DELAY_MS * (1 shl attempt)
                        android.util.Log.w("LlmClient", "Network error, retry ${attempt + 1}/$MAX_RETRIES after ${delayMs}ms: ${e.message}")
                        delay(delayMs)
                    }
                } catch (e: Exception) {
                    // 其他错误（如 4xx 客户端错误）不重试
                    if (e.message?.contains("429") == true) {
                        // 速率限制，可以重试
                        lastException = e
                        if (attempt < MAX_RETRIES - 1) {
                            val delayMs = INITIAL_RETRY_DELAY_MS * (1 shl attempt) * 2 // 速率限制时延迟更久
                            android.util.Log.w("LlmClient", "Rate limit hit, retry ${attempt + 1}/$MAX_RETRIES after ${delayMs}ms")
                            delay(delayMs)
                        }
                    } else {
                        // 其他错误直接抛出
                        throw e
                    }
                }
            }

            // 所有重试都失败
            throw lastException ?: Exception("LLM request failed after $MAX_RETRIES retries")
        }
    }

    private fun buildChatRequest(config: LlmConfig, messages: List<ChatMessage>, stream: Boolean): Request {
        val requestBody = ChatCompletionRequest(
            model = config.model,
            messages = messages,
            temperature = config.temperature,
            maxTokens = config.maxTokens,
            stream = stream
        )

        val bodyJson = json.encodeToString(ChatCompletionRequest.serializer(), requestBody)

        val url = "${config.baseUrl.trimEnd('/')}/chat/completions"
        return Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()
    }

    private fun executeChatRequest(config: LlmConfig, messages: List<ChatMessage>): String {
        val request = buildChatRequest(config, messages, stream = false)

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "No error details"
            throw Exception("LLM API error: ${response.code} ${response.message} - $errorBody")
        }

        val responseBody = response.body?.string() ?: throw Exception("Empty response")
        val completion = json.decodeFromString(ChatCompletionResponse.serializer(), responseBody)
        return completion.choices.firstOrNull()?.message?.content ?: ""
    }

    /**
     * F1: SSE 真流式入口。冷流，每次发射一个 content 增量。
     * 首 token 前失败按退避策略重试（≤2 次: 1s/2s，429 翻倍）；已发出增量后不再重试。
     */
    fun chatStream(config: LlmConfig, messages: List<ChatMessage>): Flow<String> = flow {
        val maxAttempts = 2
        for (attempt in 0 until maxAttempts) {
            var emittedAny = false
            try {
                chatStreamOnce(config, messages).collect { delta ->
                    emittedAny = true
                    emit(delta)
                }
                return@flow
            } catch (e: CancellationException) {
                throw e // 取消不重试
            } catch (e: Exception) {
                // 已发出增量后不再重试
                if (emittedAny) throw e
                val retryable = e is IOException || e.message?.contains("429") == true
                if (attempt < maxAttempts - 1 && retryable) {
                    val delayMs = 1000L * (1 shl attempt) * (if (e.message?.contains("429") == true) 2 else 1)
                    android.util.Log.w("LlmClient", "Stream failed before first token, retry ${attempt + 1}/$maxAttempts after ${delayMs}ms: ${e.message}")
                    delay(delayMs)
                } else {
                    throw e
                }
            }
        }
    }

    /** 单次流式连接（callbackFlow + EventSources 桥接，awaitClose 取消双保险）。 */
    private fun chatStreamOnce(config: LlmConfig, messages: List<ChatMessage>): Flow<String> = callbackFlow {
        val request = buildChatRequest(config, messages, stream = true)
        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                val delta = SseParser.contentDelta(data)
                if (delta != null) trySend(delta)
            }

            override fun onClosed(eventSource: EventSource) {
                close() // 服务端正常结束
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
                val message = t?.message ?: ""
                // 某些兼容网关流式响应 Content-Type 非 text/event-stream 会被 okhttp-sse 拒收
                if (message.contains("Content-Type", ignoreCase = true) || message.contains("content type", ignoreCase = true)) {
                    close(IllegalStateException("流式响应 Content-Type 不受支持（$message）：该网关可能不兼容 SSE 流式，请在模型设置中关闭流式输出（降级为普通请求）。"))
                    return
                }
                if (response != null) {
                    val summary = try {
                        response.body?.string()?.take(300) ?: "No error details"
                    } catch (_: Exception) {
                        "No error details"
                    }
                    close(IllegalStateException("LLM API error: ${response.code} ${response.message} - $summary"))
                } else {
                    close(IOException("LLM stream failed: ${t?.message}", t))
                }
            }
        }
        val es = EventSources.createFactory(client).newEventSource(request, listener)
        // 取消双保险: 收集方取消时同时断开 SSE（eventSource.cancel() 内部会取消底层 call）
        awaitClose { es.cancel() }
    }.buffer(Channel.UNLIMITED)

    suspend fun testConnection(config: LlmConfig): Boolean {
        return try {
            val testMessages = listOf(ChatMessage(role = "user", content = "Hi"))
            chat(config.copy(maxTokens = 5), testMessages)
            true
        } catch (_: Exception) {
            false
        }
    }
}
