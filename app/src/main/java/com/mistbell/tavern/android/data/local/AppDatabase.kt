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
        ThemePackEntity::class
    ],
    version = 10,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun characterDao(): CharacterDao
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun memoryDao(): MemoryDao
    abstract fun worldBookDao(): WorldBookDao
    abstract fun settingsDao(): SettingsDao
    abstract fun pendingSyncDao(): PendingSyncDao
    abstract fun structuredMemoryDao(): StructuredMemoryDao
    abstract fun vectorMemoryDao(): VectorMemoryDao
    abstract fun themePackDao(): ThemePackDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 添加 enable_long_term_memory 列。
                // 注意：DEFAULT 必须与 SessionEntity 的 @ColumnInfo(defaultValue = "0") 一致——
                // Room 迁移后的表结构校验（TableInfo）会比对列默认值，不一致会抛
                // "Migration didn't properly handle sessions" 导致升级用户崩溃。
                db.execSQL("ALTER TABLE sessions ADD COLUMN enable_long_term_memory INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 为 memories 增加 session_id 列。
                // 不能用 ALTER TABLE ... ADD COLUMN session_id TEXT NOT NULL DEFAULT ''：
                // MemoryEntity 未声明 defaultValue，带 SQL DEFAULT 的列与 Room 迁移后的表结构校验
                // （TableInfo 比对列默认值）不一致，v4→v5 升级会抛
                // "Migration didn't properly handle memories"。而 ADD COLUMN NOT NULL 又必须带 DEFAULT，
                // 因此按实体最终结构整表重建：session_id 不带 SQL 默认值，旧行以 '' 回填。
                db.execSQL("""
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
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO memories_new (
                        id, owner_id, character_id, session_id, layer, type, subject, relation,
                        `object`, content, importance, stability, status, access_count, tags, aliases
                    )
                    SELECT
                        id, owner_id, character_id, '', layer, type, subject, relation,
                        `object`, content, importance, stability, status, access_count, tags, aliases
                    FROM memories
                """.trimIndent())
                db.execSQL("DROP TABLE memories")
                db.execSQL("ALTER TABLE memories_new RENAME TO memories")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN context_token_limit INTEGER NOT NULL DEFAULT 4096")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN participant_character_ids_json TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 向量记忆系统使用独立的文件存储，不修改数据库 schema
                // 此迁移为空，仅用于版本升级
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
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
                db.execSQL("CREATE INDEX IF NOT EXISTS index_structured_memory_importance_created ON structured_memory(owner_id, importance, created_at)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_structured_memory_session ON structured_memory(owner_id, session_id, created_at)")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // T1 主题包：新建 theme_packs 表，并为 characters 添加 theme_id 列。
                // 注意：DDL 必须与实体注解逐列一致（含 defaultValue）——
                // Room 迁移后的表结构校验（TableInfo）会比对列默认值，不一致会抛
                // "Migration didn't properly handle ..." 导致升级用户崩溃（同 MIGRATION_3_4 教训）。
                // theme_packs: background_file 与 @ColumnInfo 可空一致（无 NOT NULL/DEFAULT）；
                // characters.theme_id 与 @ColumnInfo(defaultValue = "") 一致，必须带 DEFAULT ''。
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS theme_packs (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        author TEXT NOT NULL,
                        version TEXT NOT NULL,
                        tokens_json TEXT NOT NULL,
                        background_file TEXT,
                        created_at TEXT NOT NULL
                    )
                """.trimIndent())
                db.execSQL("ALTER TABLE characters ADD COLUMN theme_id TEXT NOT NULL DEFAULT ''")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "tavern.db"
                )
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
