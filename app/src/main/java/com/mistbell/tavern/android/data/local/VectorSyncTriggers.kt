package com.mistbell.tavern.android.data.local

internal val VECTOR_SYNC_TRIGGER_SQL: List<String> =
    listOf(
        """
        CREATE TRIGGER IF NOT EXISTS structured_memory_vector_insert
        AFTER INSERT ON structured_memory
        BEGIN
            UPDATE structured_memory SET vector_revision = lower(hex(randomblob(16))) WHERE id = NEW.id;
            INSERT INTO vector_sync_outbox (
                source_type, owner_id, session_id, source_id, vector_message_id, operation,
                source_revision, content_type, attempt_count, next_attempt_at, last_error, created_at
            )
            SELECT
                'summary', NEW.owner_id, COALESCE(NEW.session_id, ''), CAST(NEW.id AS TEXT),
                'structured_memory_' || NEW.id,
                CASE WHEN NEW.importance >= 7 THEN 'UPSERT' ELSE 'DELETE' END,
                vector_revision, 'summary', 0, 0, NULL, CAST(strftime('%s', 'now') AS INTEGER) * 1000
            FROM structured_memory WHERE id = NEW.id;
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS structured_memory_vector_update
        AFTER UPDATE OF owner_id, character_id, session_id, memory_type, title, content,
            structured_data, importance, tags, keywords, created_at, updated_at,
            related_message_ids, source_type ON structured_memory
        BEGIN
            UPDATE structured_memory SET vector_revision = lower(hex(randomblob(16))) WHERE id = NEW.id;
            INSERT INTO vector_sync_outbox (
                source_type, owner_id, session_id, source_id, vector_message_id, operation,
                source_revision, content_type, attempt_count, next_attempt_at, last_error, created_at
            )
            SELECT
                'summary', NEW.owner_id, COALESCE(NEW.session_id, ''), CAST(NEW.id AS TEXT),
                'structured_memory_' || NEW.id,
                CASE WHEN NEW.importance >= 7 THEN 'UPSERT' ELSE 'DELETE' END,
                vector_revision, 'summary', 0, 0, NULL, CAST(strftime('%s', 'now') AS INTEGER) * 1000
            FROM structured_memory WHERE id = NEW.id;
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS structured_memory_vector_delete
        AFTER DELETE ON structured_memory
        BEGIN
            INSERT INTO vector_sync_outbox (
                source_type, owner_id, session_id, source_id, vector_message_id, operation,
                source_revision, content_type, attempt_count, next_attempt_at, last_error, created_at
            ) VALUES (
                'summary', OLD.owner_id, COALESCE(OLD.session_id, ''), CAST(OLD.id AS TEXT),
                'structured_memory_' || OLD.id, 'DELETE', OLD.vector_revision, 'summary',
                0, 0, NULL, CAST(strftime('%s', 'now') AS INTEGER) * 1000
            );
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS messages_vector_insert
        AFTER INSERT ON messages
        BEGIN
            UPDATE messages SET vector_revision = lower(hex(randomblob(16))) WHERE id = NEW.id;
            INSERT INTO vector_sync_outbox (
                source_type, owner_id, session_id, source_id, vector_message_id, operation,
                source_revision, content_type, attempt_count, next_attempt_at, last_error, created_at
            )
            SELECT
                'message', NEW.owner_id, NEW.session_id, NEW.id, NEW.id, 'UPSERT', vector_revision,
                CASE WHEN NEW.role = 'user' THEN 'user_message' ELSE 'ai_message' END,
                0, 0, NULL, CAST(strftime('%s', 'now') AS INTEGER) * 1000
            FROM messages WHERE id = NEW.id;
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS messages_vector_update
        AFTER UPDATE OF session_id, owner_id, character_id, role, content, thinking, created_at ON messages
        BEGIN
            UPDATE messages SET vector_revision = lower(hex(randomblob(16))) WHERE id = NEW.id;
            INSERT INTO vector_sync_outbox (
                source_type, owner_id, session_id, source_id, vector_message_id, operation,
                source_revision, content_type, attempt_count, next_attempt_at, last_error, created_at
            )
            SELECT
                'message', NEW.owner_id, NEW.session_id, NEW.id, NEW.id, 'UPSERT', vector_revision,
                CASE WHEN NEW.role = 'user' THEN 'user_message' ELSE 'ai_message' END,
                0, 0, NULL, CAST(strftime('%s', 'now') AS INTEGER) * 1000
            FROM messages WHERE id = NEW.id;
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS messages_vector_delete
        AFTER DELETE ON messages
        BEGIN
            INSERT INTO vector_sync_outbox (
                source_type, owner_id, session_id, source_id, vector_message_id, operation,
                source_revision, content_type, attempt_count, next_attempt_at, last_error, created_at
            ) VALUES (
                'message', OLD.owner_id, OLD.session_id, OLD.id, OLD.id, 'DELETE', OLD.vector_revision,
                CASE WHEN OLD.role = 'user' THEN 'user_message' ELSE 'ai_message' END,
                0, 0, NULL, CAST(strftime('%s', 'now') AS INTEGER) * 1000
            );
        END
        """.trimIndent(),
    )
