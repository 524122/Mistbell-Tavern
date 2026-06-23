package com.mistbell.tavern.android.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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

    private fun executeChatRequest(config: LlmConfig, messages: List<ChatMessage>): String {
        val requestBody = ChatCompletionRequest(
            model = config.model,
            messages = messages,
            temperature = config.temperature,
            maxTokens = config.maxTokens
        )

        val bodyJson = json.encodeToString(ChatCompletionRequest.serializer(), requestBody)

        val url = "${config.baseUrl.trimEnd('/')}/chat/completions"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "No error details"
            throw Exception("LLM API error: ${response.code} ${response.message} - $errorBody")
        }

        val responseBody = response.body?.string() ?: throw Exception("Empty response")
        val completion = json.decodeFromString(ChatCompletionResponse.serializer(), responseBody)
        return completion.choices.firstOrNull()?.message?.content ?: ""
    }

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
