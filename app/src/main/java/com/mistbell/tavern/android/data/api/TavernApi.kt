package com.mistbell.tavern.android.data.api

import com.mistbell.tavern.android.data.api.model.*
import kotlinx.serialization.json.JsonElement
import retrofit2.http.*

interface TavernApi {

    @GET("/api/state")
    suspend fun getState(
        @Query("ownerId") ownerId: String? = null,
        @Query("characterId") characterId: String? = null,
        @Query("sessionId") sessionId: String? = null
    ): StateResponse

    @POST("/api/chat")
    suspend fun chat(@Body body: JsonElement): JsonElement

    @GET("/api/characters")
    suspend fun getCharacters(): JsonElement

    @POST("/api/characters")
    suspend fun createCharacter(@Body body: JsonElement): JsonElement

    @PATCH("/api/characters/{id}")
    suspend fun updateCharacter(@Path("id") id: String, @Body body: JsonElement): JsonElement

    @HTTP(method = "DELETE", path = "/api/characters/{id}", hasBody = true)
    suspend fun deleteCharacter(@Path("id") id: String, @Body body: JsonElement): JsonElement

    @GET("/api/conversation/sessions")
    suspend fun getSessions(
        @Query("ownerId") ownerId: String = "local-user",
        @Query("characterId") characterId: String = "mira"
    ): JsonElement

    @POST("/api/conversation/sessions")
    suspend fun createSession(@Body body: JsonElement): JsonElement

    @HTTP(method = "DELETE", path = "/api/conversation/sessions/{sessionId}", hasBody = true)
    suspend fun deleteSession(
        @Path("sessionId") sessionId: String,
        @Body body: JsonElement
    ): JsonElement

    @POST("/api/conversation/clear")
    suspend fun clearConversation(@Body body: JsonElement): JsonElement

    @POST("/api/conversation/messages/undo")
    suspend fun undoMessage(@Body body: JsonElement): JsonElement

    @POST("/api/conversation/messages/backtrack")
    suspend fun backtrackMessage(@Body body: JsonElement): JsonElement

    @POST("/api/conversation/messages/regenerate")
    suspend fun regenerateMessage(@Body body: JsonElement): JsonElement

    @POST("/api/conversation/messages/continue")
    suspend fun continueMessage(@Body body: JsonElement): JsonElement

    @POST("/api/conversation/messages/swipe")
    suspend fun swipeMessage(@Body body: JsonElement): JsonElement

    @PATCH("/api/settings")
    suspend fun updateSettings(@Body body: JsonElement): JsonElement

    @GET("/api/settings")
    suspend fun getSettings(): JsonElement

    @POST("/api/providers/models")
    suspend fun fetchProviderModels(@Body body: JsonElement): JsonElement

    @POST("/api/providers/test")
    suspend fun testProvider(@Body body: JsonElement): JsonElement

    // --- Character import/export ---

    @POST("/api/characters/import")
    suspend fun importCharacter(@Body body: JsonElement): JsonElement

    @GET("/api/characters/{characterId}/export")
    suspend fun exportCharacter(@Path("characterId") characterId: String): JsonElement

    // --- World Book ---

    @GET("/api/worldbook")
    suspend fun getWorldBook(): JsonElement

    @POST("/api/worldbook/books")
    suspend fun createWorldBook(@Body body: JsonElement): JsonElement

    @HTTP(method = "DELETE", path = "/api/worldbook/books/{bookId}", hasBody = true)
    suspend fun deleteWorldBook(
        @Path("bookId") bookId: String,
        @Body body: JsonElement = kotlinx.serialization.json.Json.parseToJsonElement("{}")
    ): JsonElement

    @POST("/api/worldbook/entries")
    suspend fun createWorldEntry(@Body body: JsonElement): JsonElement

    @PATCH("/api/worldbook/entries/{entryId}")
    suspend fun updateWorldEntry(
        @Path("entryId") entryId: String,
        @Body body: JsonElement
    ): JsonElement

    @HTTP(method = "DELETE", path = "/api/worldbook/entries/{entryId}", hasBody = true)
    suspend fun deleteWorldEntry(
        @Path("entryId") entryId: String,
        @Body body: JsonElement = kotlinx.serialization.json.Json.parseToJsonElement("{}")
    ): JsonElement

    // --- Memories ---

    @GET("/api/memories")
    suspend fun getMemories(
        @Query("ownerId") ownerId: String = "local-user",
        @Query("characterId") characterId: String
    ): JsonElement

    @POST("/api/memories")
    suspend fun createMemory(@Body body: JsonElement): JsonElement

    @PATCH("/api/memories/{memoryId}")
    suspend fun updateMemory(
        @Path("memoryId") memoryId: String,
        @Body body: JsonElement
    ): JsonElement

    @HTTP(method = "DELETE", path = "/api/memories/{memoryId}", hasBody = true)
    suspend fun deleteMemory(
        @Path("memoryId") memoryId: String,
        @Body body: JsonElement = kotlinx.serialization.json.Json.parseToJsonElement("{}")
    ): JsonElement

    @POST("/api/memories/backfill")
    suspend fun backfillMemories(@Body body: JsonElement): JsonElement

    // --- Prompt Preview ---

    @POST("/api/prompt/preview")
    suspend fun promptPreview(@Body body: JsonElement): JsonElement

    // --- Export ---

    @GET("/api/export/characters")
    suspend fun exportCharacters(): JsonElement

    @GET("/api/export/worldbook")
    suspend fun exportWorldBook(): JsonElement

    @GET("/api/export/memories")
    suspend fun exportMemories(
        @Query("ownerId") ownerId: String = "local-user",
        @Query("characterId") characterId: String? = null
    ): JsonElement

    @GET("/api/export/conversations")
    suspend fun exportConversations(
        @Query("ownerId") ownerId: String = "local-user",
        @Query("characterId") characterId: String? = null,
        @Query("sessionId") sessionId: String? = null
    ): JsonElement

    // --- Session settings ---

    @PATCH("/api/conversation/sessions/{sessionId}")
    suspend fun updateSession(
        @Path("sessionId") sessionId: String,
        @Body body: JsonElement
    ): JsonElement

    // --- Changelog ---

    @GET("/api/changelog")
    suspend fun getChangelog(): com.mistbell.tavern.android.data.model.ChangelogResponse
}
