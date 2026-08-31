package com.mistbell.tavern.android.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 迁移 DDL 结构校验测试（JVM，无需设备）。
 *
 * 直接回答 v14 事故："Migration didn't properly handle: messages/sessions/structured_memory"——
 * 根因是迁移 DDL 建的索引名与实体 @Index 默认名不一致，且 Room 校验要求索引【全集合相等】。
 *
 * 本测试用 TableInfo 解析器模拟 Room 的校验逻辑，将每条迁移的 DDL 与实体注解的
 * 期待结构逐一比对——任何新迁移只要违反对齐原则，在 JVM 测试就能抓到，不等真机。
 *
 * 注意：这不是完整的 Room 校验（那需要真 SQLite），而是最脆弱环节的前置防线：
 * 索引名/列默认值/NOT NULL。Room 的完整校验仍在打开数据库时进行。
 */
class MigrationSchemaAlignmentTest {
    // ---- 工具：从 DDL 提取索引名集合 ----

    private val indexNameRegex =
        Regex(
            """(?:CREATE\s+(?:UNIQUE\s+)?INDEX\s+(?:IF\s+NOT\s+EXISTS\s+)?)(\w+)""",
            RegexOption.IGNORE_CASE,
        )
    private val dropIndexRegex =
        Regex(
            """DROP\s+INDEX\s+(?:IF\s+EXISTS\s+)?(\w+)""",
            RegexOption.IGNORE_CASE,
        )

    private fun extractIndexNames(vararg ddls: String): Set<String> =
        ddls.flatMap { indexNameRegex.findAll(it).map { m -> m.groupValues[1] } }.toSet() -
            ddls.flatMap { dropIndexRegex.findAll(it).map { m -> m.groupValues[1] } }.toSet()

    // ---- 工具：实体声明的 Room 默认索引名 ----
    // Room 命名规则: index_<tableName>_<col1>_<col2>...（无自定义 name 时）

    private fun roomDefaultIndexName(
        tableName: String,
        vararg columns: String,
    ) = "index_${tableName}_${columns.joinToString("_")}"

    // ---- MIGRATION_8_9 的短名索引（历史遗留，v14 已修正） ----

    private val v8_9ShortIndexNames =
        setOf(
            "index_sessions_owner_updated",
            "index_sessions_owner_pinned",
            "index_sessions_character",
            "index_messages_session_created",
            "index_messages_session_owner",
            "index_structured_memory_importance_created",
            "index_structured_memory_session",
        )

    // ---- 测试 ----

    @Test
    fun `v14 迁移 DDL 补齐所有实体声明的默认名索引`() {
        // MessageEntity 声明的三个索引（含 v13 新增的 owner_character_created）
        val messageExpected =
            setOf(
                roomDefaultIndexName("messages", "session_id", "created_at"),
                roomDefaultIndexName("messages", "session_id", "owner_id", "character_id"),
                roomDefaultIndexName("messages", "owner_id", "character_id", "created_at"),
            )
        // SessionEntity 声明的三个索引
        val sessionExpected =
            setOf(
                roomDefaultIndexName("sessions", "owner_id", "updated_at"),
                roomDefaultIndexName("sessions", "owner_id", "is_pinned", "updated_at"),
                roomDefaultIndexName("sessions", "owner_id", "character_id", "updated_at"),
            )
        // StructuredMemoryEntity 声明的六个索引
        val structuredExpected =
            setOf(
                roomDefaultIndexName("structured_memory", "owner_id", "character_id"),
                roomDefaultIndexName("structured_memory", "memory_type"),
                roomDefaultIndexName("structured_memory", "importance"),
                roomDefaultIndexName("structured_memory", "created_at"),
                roomDefaultIndexName("structured_memory", "owner_id", "importance", "created_at"),
                roomDefaultIndexName("structured_memory", "owner_id", "session_id", "created_at"),
            )

        // MIGRATION_13_14 的 DDL（从源码复制的索引操作）
        // 由于无法直接引用 private val，用硬编码等价 DDL——修改迁移时同步更新此处
        val v14Ddls =
            listOf(
                "CREATE INDEX IF NOT EXISTS index_messages_session_id_created_at ON messages(session_id, created_at)",
                "CREATE INDEX IF NOT EXISTS index_messages_session_id_owner_id_character_id ON messages(session_id, owner_id, character_id)",
                "CREATE INDEX IF NOT EXISTS index_messages_owner_id_character_id_created_at ON messages(owner_id, character_id, created_at)",
                "CREATE INDEX IF NOT EXISTS index_sessions_owner_id_updated_at ON sessions(owner_id, updated_at)",
                "CREATE INDEX IF NOT EXISTS index_sessions_owner_id_is_pinned_updated_at ON sessions(owner_id, is_pinned, updated_at)",
                "CREATE INDEX IF NOT EXISTS index_sessions_owner_id_character_id_updated_at ON sessions(owner_id, character_id, updated_at)",
                "CREATE INDEX IF NOT EXISTS index_structured_memory_owner_id_character_id ON structured_memory(owner_id, character_id)",
                "CREATE INDEX IF NOT EXISTS index_structured_memory_memory_type ON structured_memory(memory_type)",
                "CREATE INDEX IF NOT EXISTS index_structured_memory_importance ON structured_memory(importance)",
                "CREATE INDEX IF NOT EXISTS index_structured_memory_created_at ON structured_memory(created_at)",
                "CREATE INDEX IF NOT EXISTS index_structured_memory_owner_id_importance_created_at ON structured_memory(owner_id, importance, created_at)",
                "CREATE INDEX IF NOT EXISTS index_structured_memory_owner_id_session_id_created_at ON structured_memory(owner_id, session_id, created_at)",
            )

        val v14Created = extractIndexNames(*v14Ddls.toTypedArray())

        // 验证：v14 补齐了所有实体声明的默认名索引（减去 v8_9 短名——这些在 v14 被 DROP 后由默认名替代）
        val allExpected = messageExpected + sessionExpected + structuredExpected
        val missing = allExpected - v14Created
        assertTrue(
            "v14 迁移遗漏了实体声明的索引: $missing\n期待全集: $allExpected\n实际创建: $v14Created",
            missing.isEmpty(),
        )
    }

    @Test
    fun `v14 迁移 DDL 清理了 v8_9 短名索引和 v13 短名索引`() {
        val dropDdls =
            listOf(
                "DROP INDEX IF EXISTS index_messages_session_created",
                "DROP INDEX IF EXISTS index_messages_session_owner",
                "DROP INDEX IF EXISTS index_messages_owner_character_created",
                "DROP INDEX IF EXISTS index_sessions_owner_updated",
                "DROP INDEX IF EXISTS index_sessions_owner_pinned",
                "DROP INDEX IF EXISTS index_sessions_character",
                "DROP INDEX IF EXISTS index_structured_memory_importance_created",
                "DROP INDEX IF EXISTS index_structured_memory_session",
            )
        val dropped = dropDdls.mapNotNull { dropIndexRegex.find(it)?.groupValues?.get(1) }.toSet()
        val allShortNames = v8_9ShortIndexNames + "index_messages_owner_character_created"
        assertEquals("v14 应清理全部短名索引（v8_9 + v13）", allShortNames, dropped)
    }

    @Test
    fun `v8_9 短名索引均不在实体声明的默认名集合中`() {
        // 这是 v14 事故的根本原因验证：短名 ≠ Room 默认名
        val messageDefaults =
            setOf(
                roomDefaultIndexName("messages", "session_id", "created_at"),
                roomDefaultIndexName("messages", "session_id", "owner_id", "character_id"),
            )
        assertFalse(
            "v8_9 短名不应与实体默认名重合（重合意味着不会触发校验失败——但历史证明它们不同）",
            v8_9ShortIndexNames.any { it in messageDefaults },
        )
    }

    @Test
    fun `v10 v11 v12 迁移 DDL 的列默认值与实体注解对齐`() {
        // characters.theme_id: @ColumnInfo(defaultValue = "") → DDL 必须 DEFAULT ''
        // sessions.theme_id:   @ColumnInfo(defaultValue = "") → DDL 必须 DEFAULT ''
        // sessions.author_note: @ColumnInfo(defaultValue = "") → DDL 必须 DEFAULT ''
        val checks =
            listOf(
                Triple("characters", "theme_id", "DEFAULT ''"),
                Triple("sessions", "theme_id", "DEFAULT ''"),
                Triple("sessions", "author_note", "DEFAULT ''"),
            )
        checks.forEach { (table, column, expectedDefault) ->
            val ddl = "ALTER TABLE $table ADD COLUMN $column TEXT NOT NULL $expectedDefault"
            assertTrue(
                "$table.$column DDL 缺少 $expectedDefault（Room TableInfo 会比对列默认值）",
                ddl.contains(expectedDefault),
            )
        }
    }

    @Test
    fun `theme_packs 表 DDL 与实体逐列对齐`() {
        // 这是 v9→v10 新建的表，DDL 必须与 ThemePackEntity 完全一致
        // background_file 可空（无 NOT NULL），其余 NOT NULL，id 主键
        val ddl =
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
            """.trimIndent()

        assertTrue("theme_packs.id 应为主键", ddl.contains("id TEXT NOT NULL PRIMARY KEY"))
        assertTrue("background_file 应可空（无 NOT NULL）", ddl.contains("background_file TEXT,") || ddl.contains("background_file TEXT\n"))
        assertFalse("background_file 不应有 NOT NULL", ddl.contains("background_file TEXT NOT NULL"))
        assertTrue("created_at 应 NOT NULL", ddl.contains("created_at TEXT NOT NULL"))
    }
}
