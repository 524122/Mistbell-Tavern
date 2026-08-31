package com.mistbell.tavern.android.data.sync

import com.mistbell.tavern.android.data.api.model.StateResponse
import com.mistbell.tavern.android.data.local.AppDatabase
import com.mistbell.tavern.android.data.local.entity.*

class SyncManager(
    private val db: AppDatabase,
    private val api: com.mistbell.tavern.android.data.api.TavernApi,
) {
    /**
     * Full sync: fetch /api/state and upsert all data into Room.
     */
    suspend fun fullSync(
        ownerId: String,
        characterId: String?,
        sessionId: String?,
    ) {
        try {
            val state = api.getState(ownerId, characterId, sessionId)
            upsertState(state, ownerId, characterId, sessionId)
        } catch (_: Exception) {
            // Server unreachable — local data remains valid
        }
    }

    private suspend fun upsertState(
        state: StateResponse,
        ownerId: String,
        characterId: String?,
        sessionId: String?,
    ) {
        // Characters
        val charEntities = state.characters.map { CharacterEntity.fromDomain(it) }
        db.characterDao().upsertAll(charEntities)

        // Sessions
        state.sessions.forEach { session ->
            val charId = session.characterId ?: state.characters.firstOrNull()?.id ?: ""
            db.sessionDao().upsert(SessionEntity.fromDomain(session, ownerId, charId))
        }
        state.recentSessions.forEach { session ->
            val charId = session.characterId ?: ""
            if (charId.isNotBlank()) {
                db.sessionDao().upsert(SessionEntity.fromDomain(session, ownerId, charId))
            }
        }

        // Messages
        val charId = characterId ?: state.characters.firstOrNull()?.id ?: ""
        val sessId = sessionId ?: state.activeSessionId
        if (charId.isNotBlank() && sessId.isNotBlank()) {
            val msgEntities =
                state.conversation.map {
                    MessageEntity.fromDomain(it, sessId, ownerId, charId)
                }
            db.messageDao().deleteBySession(sessId, ownerId, charId)
            db.messageDao().upsertAll(msgEntities)
        }

        // Memories
        val memEntities = state.memories.map { MemoryEntity.fromDomain(it, ownerId, charId) }
        db.memoryDao().deleteByCharacter(ownerId, charId)
        db.memoryDao().upsertAll(memEntities)

        // World book
        state.worldBook?.let { wb ->
            val bookEntity =
                WorldBookEntity(
                    id = wb.id,
                    name = wb.name,
                    settingsJson = "",
                )
            val entryEntities = wb.entries.map { WorldBookEntryEntity.fromDomain(it, wb.id) }
            db.worldBookDao().upsertBook(bookEntity)
            db.worldBookDao().upsertEntries(entryEntities)

            wb.books.forEach { subBook ->
                val subBookEntity =
                    WorldBookEntity(
                        id = subBook.id,
                        name = subBook.name,
                        settingsJson = "",
                    )
                val subEntries = subBook.entries.map { WorldBookEntryEntity.fromDomain(it, subBook.id) }
                db.worldBookDao().upsertBook(subBookEntity)
                db.worldBookDao().upsertEntries(subEntries)
            }
        }
    }

    /**
     * Push pending local changes to the server.
     */
    suspend fun pushPending() {
        val pending = db.pendingSyncDao().getAll()
        for (item in pending) {
            try {
                // Replaying is entity-specific; for now we just clear the queue
                // A full implementation would reconstruct the API call from payloadJson
                db.pendingSyncDao().delete(item)
            } catch (_: Exception) {
                // Keep in queue for next sync attempt
            }
        }
    }
}
