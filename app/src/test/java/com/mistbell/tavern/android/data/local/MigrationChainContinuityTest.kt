package com.mistbell.tavern.android.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 迁移链完整性（JVM，无需设备）。
 *
 * [AppDatabase.ALL_MIGRATIONS] 是生产库与迁移测试共用的唯一迁移清单。本测试回答四件事：
 * 清单里的迁移按版本首尾相接、没有重复也没有不升版本的；链的起点是 v3（最低仍支持的升级来源）；
 * 链的终点等于 `@Database` 注解里声明的版本；也等于 schemas/ 目录里最新导出的 schema。
 * 升版本时写了迁移却忘了登记进清单，或者改了版本号却没写迁移，这里都会变红。
 *
 * 版本号的两次读取互相独立：注解里的版本直接从 `AppDatabase.kt` 源码文本解析（Room 的
 * `@Database` 是 BINARY 保留策略，运行时反射读不到），导出的 schema 是 KSP 从同一注解生成的产物。
 */
class MigrationChainContinuityTest {
    @Test
    fun `迁移链首尾相接、没有重复版本、每一步都升版本`() {
        val chain = AppDatabase.ALL_MIGRATIONS.sortedBy { it.startVersion }
        assertTrue("迁移清单为空", chain.isNotEmpty())

        chain.zipWithNext().forEachIndexed { index, (previous, next) ->
            assertEquals(
                "迁移链在第 ${index + 1} 环断开：${previous.startVersion}→${previous.endVersion} 之后接的是 " +
                    "${next.startVersion}→${next.endVersion}（写好的迁移忘了登记进 ALL_MIGRATIONS？）",
                previous.endVersion,
                next.startVersion,
            )
        }

        val startVersions = chain.map { it.startVersion }
        assertEquals("存在起始版本重复的迁移", startVersions.size, startVersions.distinct().size)
        chain.forEach { migration ->
            assertTrue(
                "迁移 ${migration.startVersion}→${migration.endVersion} 没有升版本",
                migration.endVersion > migration.startVersion,
            )
        }
    }

    @Test
    fun `迁移链起点是最低仍支持的版本`() {
        assertEquals(LOWEST_SUPPORTED_VERSION, AppDatabase.ALL_MIGRATIONS.minOf { it.startVersion })
    }

    @Test
    fun `迁移链终点等于 Database 注解里声明的版本`() {
        assertEquals(declaredDatabaseVersion(), AppDatabase.ALL_MIGRATIONS.maxOf { it.endVersion })
    }

    @Test
    fun `迁移链终点等于最新导出的 schema 版本`() {
        assertEquals(latestExportedSchemaVersion(), AppDatabase.ALL_MIGRATIONS.maxOf { it.endVersion })
    }

    /** 从 AppDatabase.kt 的 `@Database(…)` 注解文本里取 `version = N`。 */
    private fun declaredDatabaseVersion(): Int {
        val source = moduleDirectory().resolve(APP_DATABASE_SOURCE_RELATIVE_PATH).readText()
        val annotationStart = source.indexOf("@Database(")
        val classStart = source.indexOf("abstract class AppDatabase")
        assertTrue("AppDatabase.kt 里找不到 @Database( 注解", annotationStart >= 0)
        assertTrue("AppDatabase.kt 里找不到 abstract class AppDatabase", classStart > annotationStart)
        val annotation = source.substring(annotationStart, classStart)
        val version =
            Regex("""\bversion\s*=\s*(\d+)""").find(annotation)?.groupValues?.get(1)?.toIntOrNull()
        return requireNotNull(version) { "AppDatabase.kt 的 @Database 注解里找不到 version = <数字>" }
    }

    /** schemas/<AppDatabase 全限定名>/<版本>.json 里最大的版本号。 */
    private fun latestExportedSchemaVersion(): Int {
        val versions =
            moduleDirectory()
                .resolve(SCHEMA_DIRECTORY_RELATIVE_PATH)
                .listFiles { file -> file.extension == "json" }
                .orEmpty()
                .mapNotNull { file -> file.nameWithoutExtension.toIntOrNull() }
        assertTrue("schema 导出目录里没有任何 <版本>.json", versions.isNotEmpty())
        return versions.max()
    }

    /** 模块目录 app/：从 Gradle 单测的工作目录逐级向上找，以免依赖 cwd 具体是哪一级。 */
    private fun moduleDirectory(): File {
        var directory: File? = File(System.getProperty("user.dir"))
        while (directory != null) {
            if (directory.resolve(SCHEMA_DIRECTORY_RELATIVE_PATH).isDirectory) return directory
            directory = directory.parentFile
        }
        error("从 ${System.getProperty("user.dir")} 起逐级向上都找不到 $SCHEMA_DIRECTORY_RELATIVE_PATH")
    }

    private companion object {
        /** 低于 v3 的库不再支持直接升级；生产迁移清单的第一环从这里起步 */
        const val LOWEST_SUPPORTED_VERSION = 3

        const val SCHEMA_DIRECTORY_RELATIVE_PATH = "schemas/com.mistbell.tavern.android.data.local.AppDatabase"

        const val APP_DATABASE_SOURCE_RELATIVE_PATH =
            "src/main/java/com/mistbell/tavern/android/data/local/AppDatabase.kt"
    }
}
