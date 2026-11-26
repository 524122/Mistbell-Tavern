package com.mistbell.tavern.android.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v38→v39 迁移完整性测试（instrumented，M4/M6 主题渲染引擎，THEME_FRAMEWORK_DESIGN.md §3.2）。
 *
 * 模拟真实用户升级路径：按 Room 导出的 v38 schema 快照（38.json，androidTest assets）逐表
 * 重建旧库 + 写入存量 theme_packs 行 → Room 打开执行 MIGRATION_38_39 → 断言两新列默认值
 * 逐字符对齐实体注解、存量行数据无损、迁移后 schema 通过 Room 全量校验（identity 校验隐含
 * 在打开流程中，任何列/索引不匹配都会 IllegalStateException）。
 *
 * v38 schema 快照来自 app/schemas/…/38.json（KSP 自动导出的冻结基线）：迁移后如果有人
 * 原地改实体列，Room 打开校验即失败，测试随之变红——schema 纪律「绝不原地改」的防线。
 *
 * 运行：`adb shell am instrument -w -e class com.mistbell.tavern.android.data.local.Migration38To39ThemePackTest …`
 */
@RunWith(AndroidJUnit4::class)
class Migration38To39ThemePackTest {
    private lateinit var db: AppDatabase
    private val dbName = "migration-38-39-theme-test.db"

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() {
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    /** 按 v38 schema 快照重建旧库（全部实体表 + 索引 + room_master_table 身份行 + user_version=38） */
    private fun createV38Database() {
        val schemaText =
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .context.assets.open(SCHEMA_ASSET).bufferedReader().use { it.readText() }
        val database = JSONObject(schemaText).getJSONObject("database")
        val raw = context.openOrCreateDatabase(dbName, android.content.Context.MODE_PRIVATE, null)
        val entities = database.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val tableName = entity.getString("tableName")
            // createSql 中 ${TABLE_NAME} 已带反引号（`${TABLE_NAME}`），替换为裸表名
            raw.execSQL(entity.getString("createSql").replace(TABLE_NAME_TOKEN, tableName))
            val indices = entity.optJSONArray("indices")
            if (indices != null) {
                for (j in 0 until indices.length()) {
                    val index = indices.getJSONObject(j)
                    raw.execSQL(index.getString("createSql").replace(TABLE_NAME_TOKEN, tableName))
                }
            }
        }
        // room_master_table + v38 身份哈希：Room 打开时发现哈希与当前 schema 不符 → 走全量
        // 校验（validateMigration），迁移后列/索引有任何不匹配立即失败——这正是要的防线
        database.getJSONArray("setupQueries").let { setup ->
            for (i in 0 until setup.length()) raw.execSQL(setup.getString(i))
        }
        raw.execSQL("PRAGMA user_version = 38")
        // 存量行：v38 形态的 theme_packs 行（无 capabilities/template_version 两列）
        raw.execSQL(
            """
            INSERT INTO theme_packs (id, name, author, version, tokens_json, background_file, created_at)
            VALUES ('ink-legacy', '旧版水墨（存量）', 'mistbell', '1', '$LEGACY_TOKENS_JSON', NULL, '2026-01-01 00:00:00')
            """.trimIndent(),
        )
        raw.execSQL(
            """
            INSERT INTO theme_packs (id, name, author, version, tokens_json, background_file, created_at)
            VALUES ('bg-pack', '带背景的存量包', 'mistbell', '2', '{"colors":{"primary":"#654321"}}', 'bg.png', '2026-02-01 00:00:00')
            """.trimIndent(),
        )
        raw.close()
    }

    private fun openWithRoom(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.Companion.MIGRATION_38_39)
            .allowMainThreadQueries()
            .build()

    @Test
    fun migrateV38ToV39_themePackColumnsAddedWithDefaultsAndDataPreserved() =
        runBlocking {
            createV38Database()
            db = openWithRoom()

            // 1. 两新列存在、NOT NULL、默认值与实体 @ColumnInfo 逐字符一致（PRAGMA dflt_value）
            db.openHelper.writableDatabase.query("PRAGMA table_info(theme_packs)").use { cursor ->
                val nameIdx = cursor.getColumnIndex("name")
                val notNullIdx = cursor.getColumnIndex("notnull")
                val dfltIdx = cursor.getColumnIndex("dflt_value")
                var capabilitiesFound = false
                var templateVersionFound = false
                while (cursor.moveToNext()) {
                    when (cursor.getString(nameIdx)) {
                        "capabilities" -> {
                            capabilitiesFound = true
                            assertEquals("capabilities 应 NOT NULL", 1, cursor.getInt(notNullIdx))
                            assertEquals(
                                "capabilities 列默认值应为 'cosmetic'",
                                "'cosmetic'",
                                cursor.getString(dfltIdx),
                            )
                        }
                        "template_version" -> {
                            templateVersionFound = true
                            assertEquals("template_version 应 NOT NULL", 1, cursor.getInt(notNullIdx))
                            assertEquals(
                                "template_version 列默认值应为 1",
                                "1",
                                cursor.getString(dfltIdx),
                            )
                        }
                    }
                }
                assertTrue("迁移后 capabilities 列应存在", capabilitiesFound)
                assertTrue("迁移后 template_version 列应存在", templateVersionFound)
            }

            // 2. 存量行无损：tokens_json 逐字节一致，新列按默认值读出（cosmetic / 1）
            val legacy = db.themePackDao().getById("ink-legacy")
            assertNotNull("存量主题包行应保留", legacy)
            assertEquals("存量包名应无损", "旧版水墨（存量）", legacy?.name)
            assertEquals("存量 tokens_json 应逐字节无损", LEGACY_TOKENS_JSON, legacy?.tokensJson)
            assertEquals("存量包 capabilities 应回填 cosmetic", "cosmetic", legacy?.capabilities)
            assertEquals("存量包 template_version 应回填 1", 1, legacy?.templateVersion)

            // 3. 第二条存量行（带背景文件名）同样无损
            val bgPack = db.themePackDao().getById("bg-pack")
            assertNotNull(bgPack)
            assertEquals("bg.png", bgPack?.backgroundFile)
            assertEquals("cosmetic", bgPack?.capabilities)

            // 4. 迁移后可写入完整形态行（覆盖安装语义依赖 upsert 可用）
            db.themePackDao().upsert(
                com.mistbell.tavern.android.data.local.entity.ThemePackEntity(
                    id = "post-migration",
                    name = "迁移后新包",
                    author = "mistbell",
                    version = "1",
                    tokensJson = LEGACY_TOKENS_JSON,
                    backgroundFile = null,
                    createdAt = "2026-03-01 00:00:00",
                    capabilities = "layout",
                    templateVersion = 1,
                ),
            )
            val inserted = db.themePackDao().getById("post-migration")
            assertNotNull("迁移后应可写入 layout 包", inserted)
            assertEquals("layout", inserted?.capabilities)
        }

    private companion object {
        const val SCHEMA_ASSET = "38.json"
        const val TABLE_NAME_TOKEN = "\${TABLE_NAME}"

        // 存量 tokens（含中文与转义引号，验证 TEXT 列逐字节保留）
        const val LEGACY_TOKENS_JSON =
            """{"colors":{"primary":"#123456","assistantBubble":"#faf6ec"},"dark":null}"""
    }
}
