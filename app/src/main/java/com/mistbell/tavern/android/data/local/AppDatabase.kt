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
    version = 14,
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

        // internal 供迁移测试直接引用（不改变运行时行为）
        internal val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // DEFAULT 必须与 SessionEntity 的 @ColumnInfo(defaultValue = "0") 一致
                    db.execSQL("ALTER TABLE sessions ADD COLUMN enable_long_term_memory INTEGER NOT NULL DEFAULT 0")
                }
            }

        internal val MIGRATION_4_5 =
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // 整表重建：session_id 不带 SQL 默认值（实体未声明 defaultValue），旧行以 '' 回填
                    db.execSQL(
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
                    )
                    db.execSQL(
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
                    )
                    db.execSQL("DROP TABLE memories")
                    db.execSQL("ALTER TABLE memories_new RENAME TO memories")
                }
            }

        internal val MIGRATION_5_6 =
            object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE sessions ADD COLUMN context_token_limit INTEGER NOT NULL DEFAULT 4096")
                }
            }

        internal val MIGRATION_6_7 =
            object : Migration(6, 7) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE sessions ADD COLUMN participant_character_ids_json TEXT NOT NULL DEFAULT ''")
                }
            }

        internal val MIGRATION_7_8 =
            object : Migration(7, 8) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // 空迁移：仅版本号升级
                }
            }

        internal val MIGRATION_8_9 =
            object : Migration(8, 9) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // 性能索引（注意：短名——v14 修正为 Room 默认全列名）
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_owner_updated ON sessions(owner_id, updated_at)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_owner_pinned ON sessions(owner_id, is_pinned, updated_at)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_character ON sessions(owner_id, character_id, updated_at)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_session_created ON messages(session_id, created_at)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_session_owner ON messages(session_id, owner_id, character_id)")
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_structured_memory_importance_created ON structured_memory(owner_id, importance, created_at)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_structured_memory_session ON structured_memory(owner_id, session_id, created_at)",
                    )
                }
            }

        internal val MIGRATION_9_10 =
            object : Migration(9, 10) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // T1 主题包：新建 theme_packs 表 + characters.theme_id
                    db.execSQL(
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
                    )
                    db.execSQL("ALTER TABLE characters ADD COLUMN theme_id TEXT NOT NULL DEFAULT ''")
                }
            }

        internal val MIGRATION_10_11 =
            object : Migration(10, 11) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // 会话级主题
                    db.execSQL("ALTER TABLE sessions ADD COLUMN theme_id TEXT NOT NULL DEFAULT ''")
                }
            }

        internal val MIGRATION_11_12 =
            object : Migration(11, 12) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // 会话附加指令
                    db.execSQL("ALTER TABLE sessions ADD COLUMN author_note TEXT NOT NULL DEFAULT ''")
                }
            }

        internal val MIGRATION_12_13 =
            object : Migration(12, 13) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // F3-FTS 性能索引
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_messages_owner_character_created ON messages(owner_id, character_id, created_at)",
                    )
                }
            }

        // v13→v14：修复索引名不匹配导致的升级崩溃（真机实证 2026-09）
        // MIGRATION_8_9 短名索引 ≠ 实体 @Index 默认全列名 → Room 校验要求索引全集合相等 → 崩溃
        internal val MIGRATION_13_14 =
            object : Migration(13, 14) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    fun idx(sql: String) = db.execSQL(sql)

                    // messages
                    idx("CREATE INDEX IF NOT EXISTS index_messages_session_id_created_at ON messages(session_id, created_at)")
                    idx(
                        "CREATE INDEX IF NOT EXISTS index_messages_session_id_owner_id_character_id" +
                            " ON messages(session_id, owner_id, character_id)",
                    )
                    idx(
                        "CREATE INDEX IF NOT EXISTS index_messages_owner_id_character_id_created_at" +
                            " ON messages(owner_id, character_id, created_at)",
                    )
                    idx("DROP INDEX IF EXISTS index_messages_session_created")
                    idx("DROP INDEX IF EXISTS index_messages_session_owner")
                    idx("DROP INDEX IF EXISTS index_messages_owner_character_created")

                    // sessions
                    idx("CREATE INDEX IF NOT EXISTS index_sessions_owner_id_updated_at ON sessions(owner_id, updated_at)")
                    idx(
                        "CREATE INDEX IF NOT EXISTS index_sessions_owner_id_is_pinned_updated_at" +
                            " ON sessions(owner_id, is_pinned, updated_at)",
                    )
                    idx(
                        "CREATE INDEX IF NOT EXISTS index_sessions_owner_id_character_id_updated_at" +
                            " ON sessions(owner_id, character_id, updated_at)",
                    )
                    idx("DROP INDEX IF EXISTS index_sessions_owner_updated")
                    idx("DROP INDEX IF EXISTS index_sessions_owner_pinned")
                    idx("DROP INDEX IF EXISTS index_sessions_character")

                    // structured_memory
                    idx(
                        "CREATE INDEX IF NOT EXISTS index_structured_memory_owner_id_character_id" +
                            " ON structured_memory(owner_id, character_id)",
                    )
                    idx("CREATE INDEX IF NOT EXISTS index_structured_memory_memory_type ON structured_memory(memory_type)")
                    idx("CREATE INDEX IF NOT EXISTS index_structured_memory_importance ON structured_memory(importance)")
                    idx("CREATE INDEX IF NOT EXISTS index_structured_memory_created_at ON structured_memory(created_at)")
                    idx(
                        "CREATE INDEX IF NOT EXISTS index_structured_memory_owner_id_importance_created_at" +
                            " ON structured_memory(owner_id, importance, created_at)",
                    )
                    idx(
                        "CREATE INDEX IF NOT EXISTS index_structured_memory_owner_id_session_id_created_at" +
                            " ON structured_memory(owner_id, session_id, created_at)",
                    )
                    idx("DROP INDEX IF EXISTS index_structured_memory_importance_created")
                    idx("DROP INDEX IF EXISTS index_structured_memory_session")
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
                    )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
