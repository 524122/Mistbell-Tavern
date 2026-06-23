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
        VectorMemoryEntity::class
    ],
    version = 9,
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

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 添加 enable_long_term_memory 列，默认值为 1 (true)
                db.execSQL("ALTER TABLE sessions ADD COLUMN enable_long_term_memory INTEGER NOT NULL DEFAULT 1")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memories ADD COLUMN session_id TEXT NOT NULL DEFAULT ''")
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

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "tavern.db"
                )
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
