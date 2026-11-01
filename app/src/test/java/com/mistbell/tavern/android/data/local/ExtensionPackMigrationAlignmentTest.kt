package com.mistbell.tavern.android.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * extension_packs 表三代 schema（v36→v37 建表 / v37→v38 P2 加列）的迁移 DDL 对齐。
 * 独立成类：MigrationSchemaAlignmentTest 已达 detekt LargeClass 阈值。
 *
 * v37→v38 教训锁定（T1 实测）：已装机的 schema 绝不可「就地改」——P1 构建的存量设备
 * DB identityHash 与新 APK 编译期 schema 不一致时 Room 直接
 * `IllegalStateException: Room cannot verify the data integrity` 崩溃。
 * schema 演进只有一条正路：升版本号 + 注册迁移。
 */
class ExtensionPackMigrationAlignmentTest {
    @Test
    fun `v37 迁移真实 DDL 的列集合与 P1 ExtensionPackEntity 逐列对齐`() {
        // P1 原样（12 列）：P2 的新列由 v37→v38 迁移追加，v37 建表不得提前携带
        // （否则存量 P1 设备升级时两列重复、identity 校验失败）
        val ddl = AppDatabase.MIGRATION_36_37_SQL.single()
        val body = ddl.substringAfter('(').substringBeforeLast(')')
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
            "extension_packs 列集合应与 P1 ExtensionPackEntity 完全一致",
            setOf(
                "id",
                "name",
                "author",
                "version",
                "loading_order",
                "permissions_json",
                "has_preload",
                "has_hooks",
                "enabled",
                "permissions_confirmed",
                "content_hash",
                "installed_at",
            ),
            columns,
        )
        assertFalse("installed_at 不应有 DEFAULT", body.contains("`installed_at` TEXT NOT NULL DEFAULT"))
        assertFalse("v37 建表不得提前携带 P2 的 provides_json", body.contains("provides_json"))
        assertFalse("v37 建表不得提前携带 P2 的 is_builtin", body.contains("is_builtin"))
    }

    @Test
    fun `v37 to v38 adds provides and builtin columns`() {
        // P2 门面预注入（EXTENSION_PLATFORM_DESIGN.md §5）：纯加列迁移。
        // 列默认值对齐实体 Kotlin 默认值；实体未声明 @ColumnInfo(defaultValue)，
        // Room TableInfo 校验容忍库侧默认值（v37 permissions_json 同型先例）
        assertEquals(
            listOf(
                "ALTER TABLE extension_packs ADD COLUMN provides_json TEXT NOT NULL DEFAULT '[]'",
                "ALTER TABLE extension_packs ADD COLUMN is_builtin INTEGER NOT NULL DEFAULT 0",
            ),
            AppDatabase.MIGRATION_37_38_SQL,
        )
    }

    @Test
    fun `v38 迁移仅加列不触碰既有数据`() {
        // 防呆（v19/v20/v21/v22 同型）：纯加列迁移，只允许触碰 extension_packs，
        // 不得创建/删除索引、不得删数据（复现 v14 索引名事故或丢数据都会直接炸升级）
        val sql = AppDatabase.MIGRATION_37_38_SQL
        assertTrue(
            "v38 迁移不应包含索引 DDL",
            extractIndexNames(sql).isEmpty() && extractDroppedIndexNames(sql).isEmpty(),
        )
        val tableRefRegex = Regex("""(?:TABLE|INTO)\s+(?:IF\s+NOT\s+EXISTS\s+)?(\w+)""", RegexOption.IGNORE_CASE)
        val referenced = sql.flatMap { line -> tableRefRegex.findAll(line).map { it.groupValues[1] } }.toSet()
        assertEquals("v38 迁移只允许触碰 extension_packs", setOf("extension_packs"), referenced)
        assertFalse(
            "v38 迁移不应包含 DELETE/DROP",
            sql.any { it.contains("DELETE FROM", ignoreCase = true) || it.contains("DROP TABLE", ignoreCase = true) },
        )
    }

    // ---- 工具：与 MigrationSchemaAlignmentTest 同款迁移 SQL 索引名提取（本地轻量复制，两处各 ~8 行） ----

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
}
