package com.mistbell.tavern.android.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 迁移 DDL 结构校验测试（JVM，无需设备）。
 *
 * 直接回答 v14 事故："Migration didn't properly handle: messages/sessions/structured_memory"——
 * 根因是迁移 DDL 建的索引名与实体 @Index 默认名不一致，且 Room 校验要求索引【全集合相等】。
 *
 * 修复5：本测试断言的 DDL 全部来自 AppDatabase 中迁移对象实际引用的 internal SQL 常量
 * （MIGRATION_*_SQL）——迁移对象与测试共用同一份 SQL，迁移被改坏这里立刻变红。
 * 此前测试对手工拼接的 DDL 副本断言（自证恒真），防线形同虚设，已全部重写。
 *
 * 注意：这不是完整的 Room 校验（那需要真 SQLite，见 androidTest 的 MigrationDataIntegrityTest），
 * 而是最脆弱环节的前置防线：索引名/列默认值/NOT NULL。
 */
class MigrationSchemaAlignmentTest {
    // ---- 工具：从真实迁移 SQL 提取索引名集合 ----

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

    private fun extractIndexNames(ddls: List<String>): Set<String> =
        ddls.flatMap { indexNameRegex.findAll(it).map { m -> m.groupValues[1] } }.toSet() -
            ddls.flatMap { dropIndexRegex.findAll(it).map { m -> m.groupValues[1] } }.toSet()

    private fun extractDroppedIndexNames(ddls: List<String>): Set<String> =
        ddls.flatMap { dropIndexRegex.findAll(it).map { m -> m.groupValues[1] } }.toSet()

    // ---- 工具：实体声明的 Room 默认索引名 ----
    // Room 命名规则: index_<tableName>_<col1>_<col2>...（无自定义 name 时）

    private fun roomDefaultIndexName(
        tableName: String,
        vararg columns: String,
    ) = "index_${tableName}_${columns.joinToString("_")}"

    // ---- 实体声明的期待索引（来自 @Entity/@Index 注解，与 schema json 同源） ----

    private val messageDefaultIndexes =
        setOf(
            roomDefaultIndexName("messages", "session_id", "created_at"),
            roomDefaultIndexName("messages", "session_id", "owner_id", "character_id"),
            roomDefaultIndexName("messages", "owner_id", "character_id", "created_at"),
        )
    private val sessionDefaultIndexes =
        setOf(
            roomDefaultIndexName("sessions", "owner_id", "updated_at"),
            roomDefaultIndexName("sessions", "owner_id", "is_pinned", "updated_at"),
            roomDefaultIndexName("sessions", "owner_id", "character_id", "updated_at"),
        )
    private val structuredMemoryDefaultIndexes =
        setOf(
            roomDefaultIndexName("structured_memory", "owner_id", "character_id"),
            roomDefaultIndexName("structured_memory", "memory_type"),
            roomDefaultIndexName("structured_memory", "importance"),
            roomDefaultIndexName("structured_memory", "created_at"),
            roomDefaultIndexName("structured_memory", "owner_id", "importance", "created_at"),
            roomDefaultIndexName("structured_memory", "owner_id", "session_id", "created_at"),
        )

    // ---- MIGRATION_8_9 的短名索引（历史遗留，v14 已修正） ----

    private val migrationV8ShortIndexNames =
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
    fun `v14 迁移真实 SQL 补齐所有实体声明的默认名索引`() {
        // 断言对象是迁移对象实际执行的 MIGRATION_13_14_SQL 常量（修复5：不再用测试内拼接副本）
        val v14Created = extractIndexNames(AppDatabase.MIGRATION_13_14_SQL)

        val allExpected = messageDefaultIndexes + sessionDefaultIndexes + structuredMemoryDefaultIndexes
        val missing = allExpected - v14Created
        assertTrue(
            "v14 迁移遗漏了实体声明的索引: $missing\n期待全集: $allExpected\n实际创建: $v14Created",
            missing.isEmpty(),
        )
    }

    @Test
    fun `v16 迁移真实 SQL 创建的索引与实体注解默认名一致`() {
        // v16 新增的两个消息窗口分页索引（MessageEntity @Index 注解声明）：
        // Room 默认命名 index_messages_<列连缀>，与 MIGRATION_15_16_SQL 真实语句逐一比对
        val expected =
            setOf(
                roomDefaultIndexName("messages", "session_id", "owner_id", "character_id", "created_at"),
                roomDefaultIndexName("messages", "owner_id", "session_id", "created_at"),
            )

        val v16Created = extractIndexNames(AppDatabase.MIGRATION_15_16_SQL)
        assertEquals(
            "v16 迁移建出的索引集合应与实体注解默认名完全一致（多/少/改名都会触发 Room 校验失败）",
            expected,
            v16Created,
        )
    }

    @Test
    fun `v16 新增索引不与既有默认名索引冲突重名`() {
        // 防呆：新索引名若与既有索引重名，CREATE INDEX 会直接失败或产生重复索引
        val v16Names =
            setOf(
                roomDefaultIndexName("messages", "session_id", "owner_id", "character_id", "created_at"),
                roomDefaultIndexName("messages", "owner_id", "session_id", "created_at"),
            )
        assertFalse(
            "v16 新增索引不应与既有默认名索引重名",
            v16Names.any { it in messageDefaultIndexes },
        )
    }

    @Test
    fun `v14 迁移真实 SQL 清理了 v8_9 短名索引且 v14_15 收尾 v13 短名索引`() {
        // 全部短名索引（v8_9 七个 + v13 新增的 owner_character_created）都必须被 DROP。
        // v13 短名在 MIGRATION_13_14 与 MIGRATION_14_15 中各 DROP 一次（IF EXISTS 幂等），取并集断言
        val dropped =
            extractDroppedIndexNames(AppDatabase.MIGRATION_13_14_SQL) +
                extractDroppedIndexNames(AppDatabase.MIGRATION_14_15_SQL)
        val allShortNames = migrationV8ShortIndexNames + "index_messages_owner_character_created"
        assertEquals("v14/v14_15 应清理全部短名索引（v8_9 + v13）", allShortNames, dropped)
    }

    @Test
    fun `v14_15 迁移真实 SQL 以默认名重建 v13 短名索引`() {
        val created = extractIndexNames(AppDatabase.MIGRATION_14_15_SQL)
        assertTrue(
            "v14_15 应以实体默认名重建 messages(owner_id, character_id, created_at) 索引",
            roomDefaultIndexName("messages", "owner_id", "character_id", "created_at") in created,
        )
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
            migrationV8ShortIndexNames.any { it in messageDefaults },
        )
    }

    @Test
    fun `v10 v11 v12 迁移真实 SQL 的列默认值与实体注解对齐`() {
        // characters.theme_id / sessions.theme_id / sessions.author_note 的实体注解均为
        // @ColumnInfo(defaultValue = "")，ALTER 语句必须带 DEFAULT ''——
        // Room TableInfo 会比对列默认值，缺失即触发 "Migration didn't properly handle"。
        // 修复5：直接断言迁移实际执行的常量，而非测试自拼接的字符串
        fun check(
            ddl: String,
            realSql: List<String>,
        ) {
            assertTrue(
                "迁移实际 SQL 缺少与实体 defaultValue 对齐的语句: $ddl\n实际: $realSql",
                ddl in realSql,
            )
        }
        check(
            "ALTER TABLE characters ADD COLUMN theme_id TEXT NOT NULL DEFAULT ''",
            AppDatabase.MIGRATION_9_10_SQL,
        )
        check(
            "ALTER TABLE sessions ADD COLUMN theme_id TEXT NOT NULL DEFAULT ''",
            AppDatabase.MIGRATION_10_11_SQL,
        )
        check(
            "ALTER TABLE sessions ADD COLUMN author_note TEXT NOT NULL DEFAULT ''",
            AppDatabase.MIGRATION_11_12_SQL,
        )
    }

    @Test
    fun `theme_packs 表真实 DDL 与实体逐列对齐`() {
        // 这是 v9→v10 新建的表，DDL 必须与 ThemePackEntity 完全一致：
        // id 主键、background_file 可空（无 NOT NULL），其余 NOT NULL，共 7 列
        val createStatement =
            AppDatabase.MIGRATION_9_10_SQL.firstOrNull {
                it.trimStart().startsWith("CREATE TABLE IF NOT EXISTS theme_packs")
            }
        assertNotNull("MIGRATION_9_10_SQL 应包含 theme_packs 建表语句", createStatement)

        val body = createStatement!!.substringAfter('(').substringBeforeLast(')')
        val columns =
            body.split(',')
                .map { it.trim().substringBefore(' ').trim('`') }
                .filter {
                    it.isNotEmpty() &&
                        !it.equals("PRIMARY", ignoreCase = true) &&
                        !it.equals("UNIQUE", ignoreCase = true)
                }
                .toSet()
        assertEquals(
            "theme_packs 列集合应与 ThemePackEntity 完全一致",
            setOf("id", "name", "author", "version", "tokens_json", "background_file", "created_at"),
            columns,
        )
        val idColumn = body.split(',').map { it.trim() }.first { it.startsWith("id") }
        assertTrue("theme_packs.id 应为主键", idColumn.contains("PRIMARY KEY"))
        assertFalse("background_file 不应有 NOT NULL", body.contains("background_file TEXT NOT NULL"))
        assertTrue("created_at 应 NOT NULL", body.contains("created_at TEXT NOT NULL"))
    }
}
