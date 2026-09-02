package com.mistbell.tavern.android.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.mistbell.tavern.android.data.local.dao.*
import com.mistbell.tavern.android.data.local.entity.*

@Database(
    entities = [
        CharacterEntity::class,
        SessionEntity::class,
        MessageEntity::class,
        MemoryEntity::class,
        WorldBookEntity::class,
        WorldBookEntryEntity::class,
        SettingsEntity::class,
        PendingSyncEntity::class,
        StructuredMemoryEntity::class,
        VectorMemoryEntity::class,
        ThemePackEntity::class,
    ],
    version = 17,
    // schema 导出到 app/schemas/，Room 编译期校验 + 迁移测试基线（ROADMAP"防静默清库"）
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun characterDao(): CharacterDao

    abstract fun sessionDao(): SessionDao

    abstract fun messageDao(): MessageDao

    abstract fun worldBookDao(): WorldBookDao

    abstract fun settingsDao(): SettingsDao

    // M2 清创：MemoryDao 仅保留 deleteBySession/deleteAll（会话删除时的数据清理）
    abstract fun memoryDao(): MemoryDao

    abstract fun structuredMemoryDao(): StructuredMemoryDao

    abstract fun vectorMemoryDao(): VectorMemoryDao

    abstract fun themePackDao(): ThemePackDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // 修复5：各迁移实际执行的 SQL 提取为 internal 常量，迁移对象与 JVM 迁移对齐测试
        // （MigrationSchemaAlignmentTest）引用【同一份】常量——此前测试断言的是测试文件里
        // 手工拼接的 DDL 副本，迁移被改坏测试照样绿（自证恒真）。改迁移必须改这里，测试随之校验。

        internal val MIGRATION_3_4_SQL: List<String> =
            listOf(
                // DEFAULT 必须与 SessionEntity 的 @ColumnInfo(defaultValue = "0") 一致
                "ALTER TABLE sessions ADD COLUMN enable_long_term_memory INTEGER NOT NULL DEFAULT 0",
            )

        internal val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    MIGRATION_3_4_SQL.forEach(db::execSQL)
                }
            }

        internal val MIGRATION_4_5_SQL: List<String> =
            listOf(
                // 整表重建：session_id 不带 SQL 默认值（实体未声明 defaultValue），旧行以 '' 回填
                """
                CREATE TABLE IF NOT EXISTS memories_new (
                    id TEXT NOT NULL PRIMARY KEY,
                    owner_id TEXT NOT NULL,
                    character_id TEXT NOT NULL,
                    session_id TEXT NOT NULL,
                    layer TEXT NOT NULL,
                    type TEXT NOT NULL,
                    subject TEXT NOT NULL,
                    relation TEXT NOT NULL,
                    `object` TEXT NOT NULL,
                    content TEXT NOT NULL,
                    importance REAL NOT NULL,
                    stability REAL NOT NULL,
                    status TEXT NOT NULL,
                    access_count INTEGER NOT NULL,
                    tags TEXT NOT NULL,
                    aliases TEXT NOT NULL
                )
                """.trimIndent(),
                """
                INSERT INTO memories_new (
                    id, owner_id, character_id, session_id, layer, type, subject, relation,
                    `object`, content, importance, stability, status, access_count, tags, aliases
                )
                SELECT
                    id, owner_id, character_id, '', layer, type, subject, relation,
                    `object`, content, importance, stability, status, access_count, tags, aliases
                FROM memories
                """.trimIndent(),
                "DROP TABLE memories",
                "ALTER TABLE memories_new RENAME TO memories",
            )

        internal val MIGRATION_4_5 =
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    MIGRATION_4_5_SQL.forEach(db::execSQL)
                }
            }

        internal val MIGRATION_5_6_SQL: List<String> =
            listOf(
                "ALTER TABLE sessions ADD COLUMN context_token_limit INTEGER NOT NULL DEFAULT 4096",
            )

        internal val MIGRATION_5_6 =
            object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    MIGRATION_5_6_SQL.forEach(db::execSQL)
                }
            }

        internal val MIGRATION_6_7_SQL: List<String> =
            listOf(
                "ALTER TABLE sessions ADD COLUMN participant_character_ids_json TEXT NOT NULL DEFAULT ''",
            )

        internal val MIGRATION_6_7 =
            object : Migration(6, 7) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    MIGRATION_6_7_SQL.forEach(db::execSQL)
                }
            }

        internal val MIGRATION_7_8_SQL: List<String> = emptyList()

        internal val MIGRATION_7_8 =
            object : Migration(7, 8) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // 空迁移：仅版本号升级
                }
            }

        internal val MIGRATION_8_9_SQL: List<String> =
            listOf(
                // 性能索引（注意：短名——v14 修正为 Room 默认全列名）
                "CREATE INDEX IF NOT EXISTS index_sessions_owner_updated ON sessions(owner_id, updated_at)",
                "CREATE INDEX IF NOT EXISTS index_sessions_owner_pinned ON sessions(owner_id, is_pinned, updated_at)",
                "CREATE INDEX IF NOT EXISTS index_sessions_character ON sessions(owner_id, character_id, updated_at)",
                "CREATE INDEX IF NOT EXISTS index_messages_session_created ON messages(session_id, created_at)",
                "CREATE INDEX IF NOT EXISTS index_messages_session_owner" +
                    " ON messages(session_id, owner_id, character_id)",
                "CREATE INDEX IF NOT EXISTS index_structured_memory_importance_created" +
                    " ON structured_memory(owner_id, importance, created_at)",
                "CREATE INDEX IF NOT EXISTS index_structured_memory_session" +
                    " ON structured_memory(owner_id, session_id, created_at)",
            )

        internal val MIGRATION_8_9 =
            object : Migration(8, 9) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    MIGRATION_8_9_SQL.forEach(db::execSQL)
                }
            }

        internal val MIGRATION_9_10_SQL: List<String> =
            listOf(
                // T1 主题包：新建 theme_packs 表 + characters.theme_id
                """
                CREATE TABLE IF NOT EXISTS theme_packs (
                    id TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL,
                    author TEXT NOT NULL,
                    version TEXT NOT NULL,
                    tokens_json TEXT NOT NULL,
                    background_file TEXT,
                    created_at TEXT NOT NULL
                )
                """.trimIndent(),
                "ALTER TABLE characters ADD COLUMN theme_id TEXT NOT NULL DEFAULT ''",
            )

        internal val MIGRATION_9_10 =
            object : Migration(9, 10) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    MIGRATION_9_10_SQL.forEach(db::execSQL)
                }
            }

        internal val MIGRATION_10_11_SQL: List<String> =
            listOf(
                // 会话级主题
                "ALTER TABLE sessions ADD COLUMN theme_id TEXT NOT NULL DEFAULT ''",
            )

        internal val MIGRATION_10_11 =
            object : Migration(10, 11) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    MIGRATION_10_11_SQL.forEach(db::execSQL)
                }
            }

        internal val MIGRATION_11_12_SQL: List<String> =
            listOf(
                // 会话附加指令
                "ALTER TABLE sessions ADD COLUMN author_note TEXT NOT NULL DEFAULT ''",
            )

        internal val MIGRATION_11_12 =
            object : Migration(11, 12) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    MIGRATION_11_12_SQL.forEach(db::execSQL)
                }
            }

        internal val MIGRATION_12_13_SQL: List<String> =
            listOf(
                // F3-FTS 性能索引
                "CREATE INDEX IF NOT EXISTS index_messages_owner_character_created" +
                    " ON messages(owner_id, character_id, created_at)",
            )

        internal val MIGRATION_12_13 =
            object : Migration(12, 13) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    MIGRATION_12_13_SQL.forEach(db::execSQL)
                }
            }

        // v13→v14：修复索引名不匹配导致的升级崩溃（真机实证 2026-09）
        // MIGRATION_8_9 短名索引 ≠ 实体 @Index 默认全列名 → Room 校验要求索引全集合相等 → 崩溃
        internal val MIGRATION_13_14_SQL: List<String> =
            listOf(
                // messages
                "CREATE INDEX IF NOT EXISTS index_messages_session_id_created_at ON messages(session_id, created_at)",
                "CREATE INDEX IF NOT EXISTS index_messages_session_id_owner_id_character_id" +
                    " ON messages(session_id, owner_id, character_id)",
                "CREATE INDEX IF NOT EXISTS index_messages_owner_id_character_id_created_at" +
                    " ON messages(owner_id, character_id, created_at)",
                "DROP INDEX IF EXISTS index_messages_session_created",
                "DROP INDEX IF EXISTS index_messages_session_owner",
                "DROP INDEX IF EXISTS index_messages_owner_character_created",
                // sessions
                "CREATE INDEX IF NOT EXISTS index_sessions_owner_id_updated_at ON sessions(owner_id, updated_at)",
                "CREATE INDEX IF NOT EXISTS index_sessions_owner_id_is_pinned_updated_at" +
                    " ON sessions(owner_id, is_pinned, updated_at)",
                "CREATE INDEX IF NOT EXISTS index_sessions_owner_id_character_id_updated_at" +
                    " ON sessions(owner_id, character_id, updated_at)",
                "DROP INDEX IF EXISTS index_sessions_owner_updated",
                "DROP INDEX IF EXISTS index_sessions_owner_pinned",
                "DROP INDEX IF EXISTS index_sessions_character",
                // structured_memory
                "CREATE INDEX IF NOT EXISTS index_structured_memory_owner_id_character_id" +
                    " ON structured_memory(owner_id, character_id)",
                "CREATE INDEX IF NOT EXISTS index_structured_memory_memory_type ON structured_memory(memory_type)",
                "CREATE INDEX IF NOT EXISTS index_structured_memory_importance ON structured_memory(importance)",
                "CREATE INDEX IF NOT EXISTS index_structured_memory_created_at ON structured_memory(created_at)",
                "CREATE INDEX IF NOT EXISTS index_structured_memory_owner_id_importance_created_at" +
                    " ON structured_memory(owner_id, importance, created_at)",
                "CREATE INDEX IF NOT EXISTS index_structured_memory_owner_id_session_id_created_at" +
                    " ON structured_memory(owner_id, session_id, created_at)",
                "DROP INDEX IF EXISTS index_structured_memory_importance_created",
                "DROP INDEX IF EXISTS index_structured_memory_session",
            )

        internal val MIGRATION_13_14 =
            object : Migration(13, 14) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    MIGRATION_13_14_SQL.forEach(db::execSQL)
                }
            }

        // v14→v15：v13 创建的 owner_character_created 短名索引修正为实体默认名
        // （v14 修正了 v8_9 的短名索引但遗漏了 v13 新增的这个）
        internal val MIGRATION_14_15_SQL: List<String> =
            listOf(
                "CREATE INDEX IF NOT EXISTS index_messages_owner_id_character_id_created_at" +
                    " ON messages(owner_id, character_id, created_at)",
                "DROP INDEX IF EXISTS index_messages_owner_character_created",
            )

        internal val MIGRATION_14_15 =
            object : Migration(14, 15) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    MIGRATION_14_15_SQL.forEach(db::execSQL)
                }
            }

        // v15→v16：消息窗口分页（getLatestBySession/getOlderBySession）的性能索引。
        // 索引名必须与 Room 默认命名（index_<表>_<列连缀>）完全一致，列名用反引号包裹——
        // 名字不一致会复现 v14 的 "Migration didn't properly handle" 升级崩溃
        internal val MIGRATION_15_16_SQL: List<String> =
            listOf(
                "CREATE INDEX IF NOT EXISTS index_messages_session_id_owner_id_character_id_created_at" +
                    " ON messages(`session_id`, `owner_id`, `character_id`, `created_at`)",
                "CREATE INDEX IF NOT EXISTS index_messages_owner_id_session_id_created_at" +
                    " ON messages(`owner_id`, `session_id`, `created_at`)",
            )

        internal val MIGRATION_15_16 =
            object : Migration(15, 16) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    MIGRATION_15_16_SQL.forEach(db::execSQL)
                }
            }

        // v16→v17：会话模式骨架（MODES.md 决策：一次表达全部五档，未来加模式零迁移）。
        // 本批取值仅 classic|group（"narrator" 与④⑤档为骨架预留，界面不露出）；
        // DEFAULT 值必须与 SessionEntity 的 @ColumnInfo(defaultValue = "classic" / "") 逐字符一致
        internal val MIGRATION_16_17_SQL: List<String> =
            listOf(
                "ALTER TABLE sessions ADD COLUMN mode TEXT NOT NULL DEFAULT 'classic'",
                "ALTER TABLE sessions ADD COLUMN mode_config_json TEXT NOT NULL DEFAULT ''",
            )

        internal val MIGRATION_16_17 =
            object : Migration(16, 17) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    MIGRATION_16_17_SQL.forEach(db::execSQL)
                }
            }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "tavern.db",
                )
                    .addMigrations(
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                        MIGRATION_11_12,
                        MIGRATION_12_13,
                        MIGRATION_13_14,
                        MIGRATION_14_15,
                        MIGRATION_15_16,
                        MIGRATION_16_17,
                    )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
