package com.mistbell.tavern.android.util

import android.content.Context
import android.net.Uri
import com.mistbell.tavern.android.data.api.model.Message
import com.mistbell.tavern.android.data.api.model.SessionSummary
import kotlinx.serialization.json.*
import java.io.InputStream

object SessionImporter {
    /**
     * 从 JSON 文件导入会话
     * @param context Android Context
     * @param uri 文件 URI
     * @return SessionExportData 或 null（如果解析失败）
     */
    fun importFromJson(context: Context, uri: Uri): SessionExportData? {
        return try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            val jsonString = inputStream?.bufferedReader()?.use { it.readText() } ?: return null

            val json = Json { ignoreUnknownKeys = true }
            json.decodeFromString<SessionExportData>(jsonString)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 解析会话和消息（兼容旧格式）
     */
    fun parseSessionData(jsonString: String): Pair<SessionSummary, List<Message>>? {
        return try {
            val json = Json { ignoreUnknownKeys = true }
            val jsonElement = json.parseToJsonElement(jsonString)
            val jsonObject = jsonElement.jsonObject

            // 解析会话信息
            val sessionObj = jsonObject["session"]?.jsonObject ?: return null
            val session = SessionSummary(
                id = sessionObj["id"]?.jsonPrimitive?.content ?: "",
                title = sessionObj["title"]?.jsonPrimitive?.content ?: "未命名对话",
                createdAt = sessionObj["createdAt"]?.jsonPrimitive?.content ?: "",
                updatedAt = sessionObj["updatedAt"]?.jsonPrimitive?.content ?: "",
                messageCount = sessionObj["messageCount"]?.jsonPrimitive?.intOrNull ?: 0,
                characterId = sessionObj["characterId"]?.jsonPrimitive?.content,
                characterName = sessionObj["characterName"]?.jsonPrimitive?.content
            )

            // 解析消息列表
            val messagesArray = jsonObject["messages"]?.jsonArray ?: JsonArray(emptyList())
            val messages = messagesArray.mapNotNull { msgElement ->
                val msgObj = msgElement.jsonObject
                Message(
                    id = msgObj["id"]?.jsonPrimitive?.content ?: "",
                    role = msgObj["role"]?.jsonPrimitive?.content ?: "user",
                    content = msgObj["content"]?.jsonPrimitive?.content ?: "",
                    thinking = msgObj["thinking"]?.jsonPrimitive?.content,
                    createdAt = msgObj["createdAt"]?.jsonPrimitive?.content ?: "",
                    memoryIds = msgObj["memoryIds"]?.jsonArray?.map { it.jsonPrimitive.content },
                    swipes = msgObj["swipes"]?.jsonArray?.map { it.jsonPrimitive.content },
                    swipeIndex = msgObj["swipeIndex"]?.jsonPrimitive?.intOrNull ?: 0
                )
            }

            Pair(session, messages)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
