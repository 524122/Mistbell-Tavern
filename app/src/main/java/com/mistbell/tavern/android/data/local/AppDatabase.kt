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
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun characterDao(): CharacterDao

    abstract fun sessionDao(): SessionDao

    abstract fun messageDao(): MessageDao

    abstract fun worldBookDao(): WorldBookDao

    abstract fun settingsDao(): SettingsDao

    // M2 清创：MemoryDao 仅保留 deleteBySession/deleteAll（会话删除时的数据清理）；
    // PendingSyncDao 已随 SyncManager 死代码删除（表暂留 schema，待 v15 DROP）

    abstract fun memoryDao(): MemoryDao

    abstract fun structuredMemoryDao(): StructuredMemoryDao

    abstract fun vectorMemoryDao(): VectorMemoryDao

    abstract fun themePackDao(): ThemePackDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // 添加 enable_long_term_memory 列。
                    // 注意：DEFAULT 必须与 SessionEntity 的 @ColumnInfo(defaultValue = "0") 一致——
                    // Room 迁移后的表结构校验（TableInfo）会比对列默认值，不一致会抛
                    // "Migration didn't properly handle sessions" 导致升级用户崩溃。
                    db.execSQL("ALTER TABLE sessions ADD COLUMN enable_long_term_memory INTEGER NOT NULL DEFAULT 0")
                }
            }

        private val MIGRATION_4_5 =
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // 为 memories 增加 session_id 列。
                    // 不能用 ALTER TABLE ... ADD COLUMN session_id TEXT NOT NULL DEFAULT ''：
                    // MemoryEntity 未声明 defaultValue，带 SQL DEFAULT 的列与 Room 迁移后的表结构校验
                    // （TableInfo 比对列默认值）不一致，v4→v5 升级会抛
                    // "Migration didn't properly handle memories"。而 ADD COLUMN NOT NULL 又必须带 DEFAULT，
                    // 因此按实体最终结构整表重建：session_id 不带 SQL 默认值，旧行以 '' 回填。
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

        private val MIGRATION_5_6 =
            object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE sessions ADD COLUMN context_token_limit INTEGER NOT NULL DEFAULT 4096")
                }
            }

        private val MIGRATION_6_7 =
            object : Migration(6, 7) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE sessions ADD COLUMN participant_character_ids_json TEXT NOT NULL DEFAULT ''")
                }
            }

        private val MIGRATION_7_8 =
            object : Migration(7, 8) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // 向量记忆系统使用独立的文件存储，不修改数据库 schema
                    // 此迁移为空，仅用于版本升级
                }
            }

        private val MIGRATION_8_9 =
            object : Migration(8, 9) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // 添加关键索引以提升查询性能
                    // 注意：SQLite 索引不支持 ASC/DESC 关键字，只在 ORDER BY 中使用

                    // sessions 表索引：优化会话列表查询
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_owner_updated ON sessions(owner_id, updated_at)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_owner_pinned ON sessions(owner_id, is_pinned, updated_at)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_character ON sessions(owner_id, character_id, updated_at)")

                    // messages 表索引：优化消息查询
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_session_created ON messages(session_id, created_at)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_session_owner ON messages(session_id, owner_id, character_id)")

                    // structured_memory 表索引：优化记忆查询（补充现有索引）
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_structured_memory_importance_created ON structured_memory(owner_id, importance, created_at)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_structured_memory_session ON structured_memory(owner_id, session_id, created_at)",
                    )
                }
            }

        private val MIGRATION_9_10 =
            object : Migration(9, 10) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // T1 主题包：新建 theme_packs 表，并为 characters 添加 theme_id 列。
                    // 注意：DDL 必须与实体注解逐列一致（含 defaultValue）——
                    // Room 迁移后的表结构校验（TableInfo）会比对列默认值，不一致会抛
                    // "Migration didn't properly handle ..." 导致升级用户崩溃（同 MIGRATION_3_4 教训）。
                    // theme_packs: background_file 与 @ColumnInfo 可空一致（无 NOT NULL/DEFAULT）；
                    // characters.theme_id 与 @ColumnInfo(defaultValue = "") 一致，必须带 DEFAULT ''。
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

        private val MIGRATION_10_11 =
            object : Migration(10, 11) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // 会话级主题包：为 sessions 添加 theme_id 列。
                    // 注意：DEFAULT '' 必须与 SessionEntity 的 @ColumnInfo(defaultValue = "") 一致——
                    // Room 迁移后的表结构校验（TableInfo）会比对列默认值，不一致会抛
                    // "Migration didn't properly handle sessions" 导致升级用户崩溃（同 MIGRATION_3_4 教训）。
                    db.execSQL("ALTER TABLE sessions ADD COLUMN theme_id TEXT NOT NULL DEFAULT ''")
                }
            }

        private val MIGRATION_11_12 =
            object : Migration(11, 12) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // 会话附加指令：为 sessions 添加 author_note 列。
                    // 注意：DEFAULT '' 必须与 SessionEntity 的 @ColumnInfo(defaultValue = "") 一致——
                    // Room 迁移后的表结构校验（TableInfo）会比对列默认值，不一致会抛
                    // "Migration didn't properly handle sessions" 导致升级用户崩溃（同 MIGRATION_3_4 教训）。
                    db.execSQL("ALTER TABLE sessions ADD COLUMN author_note TEXT NOT NULL DEFAULT ''")
                }
            }

        private val MIGRATION_12_13 =
            object : Migration(12, 13) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // F3-FTS：为词法召回补充独立性能索引（owner_id, character_id, created_at）。
                    // 与实体注解无关（Room 不会自动生成该组合），仅加速 latestIdsBySession /
                    // searchByContentTerms 这类"按角色过滤 + 按时间排序"的查询；幂等，可安全重复执行。
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_messages_owner_character_created ON messages(owner_id, character_id, created_at)",
                    )
                }
            }

        // v13→v14：修复索引名不匹配导致的升级崩溃（真机实证 2026-09）。
        // 根因：MIGRATION_8_9 用简写名建索引（如 index_messages_session_created），
        // 而实体 @Index 未声明 name → Room 校验期待默认全列名（index_messages_session_id_created_at）。
        // 凡走过 8_9 迁移的存量设备，升级到 ≥13 后校验必抛 "Migration didn't properly handle: messages/sessions/structured_memory"。
        // 修法：按实体声明逐一补建默认名索引（IF NOT EXISTS 幂等），并清理短名旧索引（避免重复索引的写放大）。
        private val MIGRATION_13_14 =
            object : Migration(13, 14) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    fun idx(sql: String) = db.execSQL(sql)

                    // messages（实体声明：session_id+created_at、session_id+owner_id+character_id）
                    idx("CREATE INDEX IF NOT EXISTS index_messages_session_id_created_at ON messages(session_id, created_at)")
                    idx(
                        "CREATE INDEX IF NOT EXISTS index_messages_session_id_owner_id_character_id" +
                            " ON messages(session_id, owner_id, character_id)",
                    )
                    idx("DROP INDEX IF EXISTS index_messages_session_created")
                    idx("DROP INDEX IF EXISTS index_messages_session_owner")

                    // sessions（实体声明：owner_id+updated_at、owner_id+is_pinned+updated_at、owner_id+character_id+updated_at）
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

                    // structured_memory（实体声明六个索引的默认全列名）
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
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
