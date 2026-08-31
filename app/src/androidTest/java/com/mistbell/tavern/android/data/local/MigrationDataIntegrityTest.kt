package com.mistbell.tavern.android.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room 迁移数据保全测试（instrumented，需要真机/模拟器）。
 *
 * 模拟真实用户升级路径：手工创建旧版数据库 + 写入测试数据 → Room 打开并执行迁移 → 验证数据完整性。
 * 这是 v14 事故（索引名不匹配导致升级崩溃）的端到端防线。
 *
 * 运行: `gradlew :app:connectedDebugAndroidTest`（需连接设备）
 */
@RunWith(AndroidJUnit4::class)
class MigrationDataIntegrityTest {
    private lateinit var db: AppDatabase
    private val dbName = "migration-test.db"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    /** 手工创建 v11 版数据库（有 theme_id 但没有 author_note，索引是 v8_9 短名——v14 崩溃的确切现场） */
    private fun createV11Database() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val raw = context.openOrCreateDatabase(dbName, android.content.Context.MODE_PRIVATE, null)

        // 设置版本号（Room 以 PRAGMA user_version 判断）
        raw.execSQL("PRAGMA user_version = 11")

        // v11 时点的完整 schema（手工 DDL——与 v11 实体一致但用 v8_9 短名索引，复现升级现场）
        raw.execSQL(
            """
            CREATE TABLE IF NOT EXISTS characters (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                role TEXT NOT NULL,
                description TEXT NOT NULL,
                personality TEXT NOT NULL,
                scenario TEXT NOT NULL,
                first_mes TEXT NOT NULL,
                mes_example TEXT NOT NULL,
                color TEXT NOT NULL,
                avatar_data TEXT NOT NULL,
                world_book_id TEXT NOT NULL,
                theme_id TEXT NOT NULL DEFAULT '',
                data_json TEXT NOT NULL
            )
            """.trimIndent(),
        )

        raw.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sessions (
                id TEXT NOT NULL PRIMARY KEY,
                owner_id TEXT NOT NULL,
                character_id TEXT NOT NULL,
                title TEXT NOT NULL,
                provider_id TEXT NOT NULL,
                model_id TEXT NOT NULL,
                world_book_id TEXT NOT NULL,
                summary_json TEXT NOT NULL,
                unread_count INTEGER NOT NULL DEFAULT 0,
                is_pinned INTEGER NOT NULL DEFAULT 0,
                pinned_at TEXT,
                is_muted INTEGER NOT NULL DEFAULT 0,
                enable_long_term_memory INTEGER NOT NULL DEFAULT 0,
                context_token_limit INTEGER NOT NULL DEFAULT 4096,
                participant_character_ids_json TEXT NOT NULL DEFAULT '',
                theme_id TEXT NOT NULL DEFAULT '',
                message_count INTEGER NOT NULL DEFAULT 0,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """.trimIndent(),
        )

        raw.execSQL(
            """
            CREATE TABLE IF NOT EXISTS messages (
                id TEXT NOT NULL PRIMARY KEY,
                session_id TEXT NOT NULL,
                owner_id TEXT NOT NULL,
                character_id TEXT NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                thinking TEXT,
                created_at TEXT NOT NULL,
                memory_ids_json TEXT NOT NULL,
                swipes_json TEXT NOT NULL,
                swipe_index INTEGER NOT NULL,
                thinking_swipes_json TEXT NOT NULL,
                is_read INTEGER NOT NULL DEFAULT 1
            )
            """.trimIndent(),
        )

        // v8_9 短名索引（v14 要修复的）
        raw.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_owner_updated ON sessions(owner_id, updated_at)")
        raw.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_owner_pinned ON sessions(owner_id, is_pinned, updated_at)")
        raw.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_character ON sessions(owner_id, character_id, updated_at)")
        raw.execSQL("CREATE INDEX IF NOT EXISTS index_messages_session_created ON messages(session_id, created_at)")
        raw.execSQL("CREATE INDEX IF NOT EXISTS index_messages_session_owner ON messages(session_id, owner_id, character_id)")

        // settings（v11 已有）
        raw.execSQL(
            "CREATE TABLE IF NOT EXISTS settings (`key` TEXT NOT NULL PRIMARY KEY, `value` TEXT NOT NULL)",
        )
        raw.execSQL("INSERT INTO settings (`key`, `value`) VALUES ('dark_mode', 'system')")

        // structured_memory（v11 已有，仅建表结构——索引完整版见实体声明）
        raw.execSQL(
            """
            CREATE TABLE IF NOT EXISTS structured_memory (
                id TEXT NOT NULL PRIMARY KEY,
                owner_id TEXT NOT NULL,
                character_id TEXT NOT NULL,
                session_id TEXT NOT NULL DEFAULT '',
                memory_type TEXT NOT NULL,
                content TEXT NOT NULL,
                importance REAL NOT NULL,
                access_count INTEGER NOT NULL DEFAULT 0,
                source_message_ids TEXT NOT NULL DEFAULT '[]',
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """.trimIndent(),
        )

        // ---- 写入测试数据 ----
        raw.execSQL(
            """
            INSERT INTO characters (id, name, role, description, personality, scenario, first_mes, mes_example, color, avatar_data, world_book_id, theme_id, data_json)
            VALUES ('char-1', '测试角色', 'assistant', '描述', '性格', '场景', '你好！', '示例', '#007AFF', '', '', '', '{}')
            """.trimIndent(),
        )
        raw.execSQL(
            """
            INSERT INTO sessions (id, owner_id, character_id, title, provider_id, model_id, world_book_id, summary_json, unread_count, is_pinned, enable_long_term_memory, context_token_limit, participant_character_ids_json, theme_id, message_count, created_at, updated_at)
            VALUES ('sess-1', 'local-user', 'char-1', '测试会话', '', '', '', '', 0, 0, 1, 4096, '', 'theme-1', 3, '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z')
            """.trimIndent(),
        )
        raw.execSQL(
            "INSERT INTO messages (id, session_id, owner_id, character_id, role, content, thinking, created_at, memory_ids_json, swipes_json, swipe_index, thinking_swipes_json, is_read) VALUES ('msg-1', 'sess-1', 'local-user', 'char-1', 'user', '你好', NULL, '2026-01-01T00:01:00Z', '', '', 0, '', 1)",
        )
        raw.execSQL(
            "INSERT INTO messages (id, session_id, owner_id, character_id, role, content, thinking, created_at, memory_ids_json, swipes_json, swipe_index, thinking_swipes_json, is_read) VALUES ('msg-2', 'sess-1', 'local-user', 'char-1', 'assistant', '你好！我是测试角色。', NULL, '2026-01-01T00:02:00Z', '', '', 0, '', 1)",
        )
        raw.execSQL(
            "INSERT INTO messages (id, session_id, owner_id, character_id, role, content, thinking, created_at, memory_ids_json, swipes_json, swipe_index, thinking_swipes_json, is_read) VALUES ('msg-3', 'sess-1', 'local-user', 'char-1', 'user', '再见', NULL, '2026-01-01T00:03:00Z', '', '', 0, '', 1)",
        )

        raw.close()
    }

    /** 打开数据库并执行迁移（用生产同款迁移链——AppDatabase 伴生对象的 internal 成员） */
    private fun openWithRoom(): AppDatabase {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(
                AppDatabase.Companion.MIGRATION_3_4,
                AppDatabase.Companion.MIGRATION_4_5,
                AppDatabase.Companion.MIGRATION_5_6,
                AppDatabase.Companion.MIGRATION_6_7,
                AppDatabase.Companion.MIGRATION_7_8,
                AppDatabase.Companion.MIGRATION_8_9,
                AppDatabase.Companion.MIGRATION_9_10,
                AppDatabase.Companion.MIGRATION_10_11,
                AppDatabase.Companion.MIGRATION_11_12,
                AppDatabase.Companion.MIGRATION_12_13,
                AppDatabase.Companion.MIGRATION_13_14,
            )
            .allowMainThreadQueries()
            .build()
    }

    @Test
    fun migrateV11ToV14_preservesAllData() =
        runBlocking {
            createV11Database()
            db = openWithRoom()

            // 验证角色
            val characters = db.characterDao().getAll().first()
            assertEquals("角色应保留", 1, characters.size)
            assertEquals("测试角色", characters[0].name)

            // 验证会话（theme_id 应保留）
            val sessions = db.sessionDao().getByCharacter("local-user", "char-1").first()
            assertTrue("会话应保留", sessions.isNotEmpty())
            assertEquals("theme-1", sessions[0].themeId)

            // 验证消息
            val messages =
                db.messageDao()
                    .getBySession("sess-1", "local-user", "char-1")
                    .first()
            assertEquals("消息应全部保留", 3, messages.size)
            assertEquals("你好", messages[0].content)
            assertEquals("再见", messages[2].content)

            // 验证设置
            val darkMode = db.settingsDao().getValue("dark_mode")
            assertEquals("system", darkMode)
        }

    @Test
    fun migrateV11ToV14_canWriteAfterMigration() =
        runBlocking {
            createV11Database()
            db = openWithRoom()

            // 迁移后写入新数据（验证 schema 可用）
            db.settingsDao().upsert(
                com.mistbell.tavern.android.data.local.entity.SettingsEntity("post_migration_test", "ok"),
            )
            assertEquals("ok", db.settingsDao().getValue("post_migration_test"))

            // 写入新消息
            db.messageDao().upsert(
                com.mistbell.tavern.android.data.local.entity.MessageEntity(
                    id = "msg-new",
                    sessionId = "sess-1",
                    ownerId = "local-user",
                    characterId = "char-1",
                    role = "user",
                    content = "迁移后新消息",
                    thinking = null,
                    createdAt = "2026-09-01T00:00:00Z",
                    memoryIdsJson = "",
                    swipesJson = "",
                    swipeIndex = 0,
                    thinkingSwipesJson = "",
                ),
            )
            val messages =
                db.messageDao()
                    .getBySession("sess-1", "local-user", "char-1")
                    .first()
            assertEquals("迁移后写入的新消息应在查询中", 4, messages.size)
        }

    @Test
    fun migrateV11ToV14_authorNoteColumnAdded() =
        runBlocking {
            createV11Database()
            db = openWithRoom()

            // v12 添加了 author_note 列——应可写入和读取
            db.sessionDao().updateAuthorNote("sess-1", "local-user", "char-1", "这是一条附加指令")
            val session = db.sessionDao().get("sess-1", "local-user", "char-1")
            assertNotNull("会话应存在", session)
            assertEquals("这是一条附加指令", session?.authorNote)
        }
}
