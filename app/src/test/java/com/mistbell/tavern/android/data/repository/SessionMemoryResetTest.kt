package com.mistbell.tavern.android.data.repository

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.InvalidationTracker
import com.mistbell.tavern.android.data.local.AppDatabase
import com.mistbell.tavern.android.data.local.dao.ApiConfigDao
import com.mistbell.tavern.android.data.local.dao.CharacterDao
import com.mistbell.tavern.android.data.local.dao.CustomPromptDao
import com.mistbell.tavern.android.data.local.dao.MemoryDao
import com.mistbell.tavern.android.data.local.dao.MessageDao
import com.mistbell.tavern.android.data.local.dao.RulePackDao
import com.mistbell.tavern.android.data.local.dao.SessionDao
import com.mistbell.tavern.android.data.local.dao.SettingsDao
import com.mistbell.tavern.android.data.local.dao.StructuredMemoryDao
import com.mistbell.tavern.android.data.local.dao.ThemePackDao
import com.mistbell.tavern.android.data.local.dao.VectorMemoryDao
import com.mistbell.tavern.android.data.local.dao.VectorSyncOutboxDao
import com.mistbell.tavern.android.data.local.dao.WorldBookDao
import com.mistbell.tavern.android.data.local.entity.SettingsEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * 契约：SessionMemoryReset 一次性升级清零的成败边界（无 Robolectric——
 * Context 用 ContextWrapper 子类、SharedPreferences/Room DAO 都是接口，用 JVM 动态代理做替身）——
 * 1) 只有**全部步骤成功**才写 DONE 标记（commit 必须被实际调用且返回 true）；
 *    commit 失败时撤销内存态，闸门保持关闭；
 * 2) 删除失败（文件存在但 delete 返回 false）→ 整体失败、不写 DONE、**失败不缓存**，
 *    修复后下一次 runIfNeeded 重试成功；
 * 3) 幂等与补通知（三个互相独立的用例，各自自建夹具）：
 *    - 成功后的二次调用：不清数据、文件不动、不重复挂提示；
 *    - DONE 已持久化 + seen=1 → 不补挂 pending；
 *    - DONE 已持久化 + seen 缺失 → 补挂 pending="1"。
 *
 * 所有用例互相独立：object 的 completed 进程内缓存在 @Before 逐案重置，
 * 任何用例不依赖其他用例设置的缓存或文件状态。
 */
class SessionMemoryResetTest {
    private lateinit var filesDir: File

    @Before
    fun setUp() {
        filesDir = Files.createTempDirectory("session-memory-reset-test").toFile()
        // SessionMemoryReset 是 object：成功态缓存在进程内。用例彼此独立，逐案重置。
        resetCompleted()
    }

    private companion object {
        const val DONE_KEY = "session_memory_reset_v1_done"

        /** 代理未实现的方法时按返回类型给 JVM 默认值 */
        private fun defaultValue(method: Method): Any? =
            when (method.returnType) {
                java.lang.Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L
                else -> null
            }
    }

    // ---- 测试替身 ----

    /** 脚本化 SharedPreferences：commit 的返回值可注入失败；commit 调用次数与内存 map 全程可查 */
    private class ScriptedPrefs(
        var failNextCommit: Boolean = false,
    ) {
        val map = linkedMapOf<String, String>()
        val commitCalls = AtomicInteger(0)

        fun asProxy(): SharedPreferences {
            val handler =
                InvocationHandler { _, method, args ->
                    when (method.name) {
                        "getString" -> map[args[0] as String] ?: args[1] as String?
                        "contains" -> map.containsKey(args[0])
                        "edit" -> editorProxy()
                        "toString" -> "ScriptedPrefs$map"
                        else -> defaultValue(method)
                    }
                }
            return Proxy.newProxyInstance(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java),
                handler,
            ) as SharedPreferences
        }

        /** 返回编辑器自身的变更方法（链式调用契约） */
        private val mutatorNames = setOf("putString", "putInt", "putLong", "putBoolean", "remove", "clear")

        private fun editorProxy(): SharedPreferences.Editor {
            val staged = linkedMapOf<String, String?>()
            lateinit var editor: SharedPreferences.Editor
            val handler =
                InvocationHandler { _, method, args ->
                    when (method.name) {
                        "commit" -> commitStaged(staged)
                        "apply" -> {
                            applyStaged(staged)
                            null
                        }
                        in mutatorNames -> {
                            stageMutation(staged, method.name, args ?: emptyArray())
                            editor
                        }
                        else -> defaultValue(method)
                    }
                }
            editor =
                Proxy.newProxyInstance(
                    SharedPreferences.Editor::class.java.classLoader,
                    arrayOf(SharedPreferences.Editor::class.java),
                    handler,
                ) as SharedPreferences.Editor
            return editor
        }

        /** putString 等写入与 remove/clear 的查表分派：暂存改动（commit/apply 时才落进内存 map） */
        private fun stageMutation(
            staged: MutableMap<String, String?>,
            name: String,
            args: Array<Any?>,
        ) {
            when (name) {
                "putString" -> staged[args[0] as String] = args[1] as String
                "putInt" -> staged[args[0] as String] = (args[1] as Int).toString()
                "putLong" -> staged[args[0] as String] = (args[1] as Long).toString()
                "putBoolean" -> staged[args[0] as String] = (args[1] as Boolean).toString()
                "remove" -> staged[args[0] as String] = null
                "clear" -> staged.clear()
            }
        }

        /** 把暂存改动落进内存 map（apply/commit 共用） */
        private fun applyStaged(staged: MutableMap<String, String?>) {
            staged.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
            staged.clear()
        }

        /** commit：应用暂存并返回脚本化结果（failNextCommit 消费一次失败） */
        private fun commitStaged(staged: MutableMap<String, String?>): Boolean {
            commitCalls.incrementAndGet()
            applyStaged(staged)
            return if (failNextCommit) {
                failNextCommit = false
                false
            } else {
                true
            }
        }
    }

    private fun contextProxy(prefs: ScriptedPrefs): Context =
        object : ContextWrapper(null) {
            override fun getSharedPreferences(
                name: String?,
                mode: Int,
            ): SharedPreferences = prefs.asProxy()

            override fun getFilesDir(): File = this@SessionMemoryResetTest.filesDir
        }

    /** 只记录调用、返回空值的 DAO 替身；Unit 返回的挂起函数必须回 Unit */
    private class DaoSpy {
        val deleteAllCalls = AtomicInteger(0)
        val noticeUpserts = mutableListOf<SettingsEntity>()

        /** settingsDao().getValue(...) 的脚本化返回值（null = 键缺失） */
        var noticeSeenValue: String? = null

        @Suppress("UNCHECKED_CAST")
        private fun <T> proxy(
            iface: Class<T>,
            onCall: (String, Array<Any?>) -> Any?,
        ): T =
            Proxy.newProxyInstance(
                iface.classLoader,
                arrayOf(iface),
            ) { _, method, args ->
                val custom = onCall(method.name, args ?: emptyArray())
                when {
                    custom != null -> custom
                    method.returnType == Unit::class.java -> Unit
                    else -> null
                }
            } as T

        fun structuredMemoryDao(): StructuredMemoryDao =
            proxy(StructuredMemoryDao::class.java) { name, _ ->
                if (name == "deleteAll") deleteAllCalls.incrementAndGet()
                null
            }

        fun settingsDao(): SettingsDao =
            proxy(SettingsDao::class.java) { name, args ->
                when (name) {
                    "upsert" -> {
                        noticeUpserts += args[0] as SettingsEntity
                        null
                    }
                    "getValue" -> noticeSeenValue
                    else -> null
                }
            }
    }

    private fun dbSpy(spy: DaoSpy): AppDatabase =
        object : AppDatabase() {
            override fun characterDao(): CharacterDao = throw UnsupportedOperationException()

            override fun sessionDao(): SessionDao = throw UnsupportedOperationException()

            override fun messageDao(): MessageDao = throw UnsupportedOperationException()

            override fun worldBookDao(): WorldBookDao = throw UnsupportedOperationException()

            override fun settingsDao(): SettingsDao = spy.settingsDao()

            override fun memoryDao(): MemoryDao = throw UnsupportedOperationException()

            override fun structuredMemoryDao(): StructuredMemoryDao = spy.structuredMemoryDao()

            override fun vectorMemoryDao(): VectorMemoryDao = throw UnsupportedOperationException()

            override fun vectorSyncOutboxDao(): VectorSyncOutboxDao = throw UnsupportedOperationException()

            override fun themePackDao(): ThemePackDao = throw UnsupportedOperationException()

            override fun apiConfigDao(): ApiConfigDao = throw UnsupportedOperationException()

            override fun customPromptDao(): CustomPromptDao = throw UnsupportedOperationException()

            override fun rulePackDao(): RulePackDao = throw UnsupportedOperationException()

            override fun createInvalidationTracker(): InvalidationTracker = throw UnsupportedOperationException()

            override fun clearAllTables() = throw UnsupportedOperationException()
        }

    private fun writeLegacyFiles() {
        File(filesDir, "vector_store.json").writeText("""{"vectors":[]}""")
        File(filesDir, "pending_extraction.json").writeText("""{"sessions":[],"inflight":[]}""")
    }

    // ---- 用例（全部互相独立，无顺序依赖） ----

    @Test
    fun `commit 失败时撤销内存态_不写 DONE_不挂提示`() {
        writeLegacyFiles()
        val prefs = ScriptedPrefs(failNextCommit = true)
        val spy = DaoSpy()

        val result = runBlocking { SessionMemoryReset.runIfNeeded(contextProxy(prefs), dbSpy(spy)) }

        assertFalse("commit 失败 = 清零未完成", result)
        assertNull("DONE 不得留存（内存态已撤销）", prefs.map[DONE_KEY])
        assertTrue("成功之前不得写一次性提示", spy.noticeUpserts.isEmpty())
    }

    @Test
    fun `删除失败不写 DONE 且不缓存_修复后重试成功`() {
        // 删除失败注入：vector_store.json 是一个**非空目录**，File.delete 对非空目录返回 false
        val blockedDir = File(filesDir, "vector_store.json")
        blockedDir.mkdirs()
        File(blockedDir, "occupied.txt").writeText("占位")
        writeLegacyFilesExceptVector()
        val prefs = ScriptedPrefs()
        val spy = DaoSpy()

        val failed = runBlocking { SessionMemoryReset.runIfNeeded(contextProxy(prefs), dbSpy(spy)) }

        assertFalse("删除失败 = 整体失败", failed)
        assertNull("失败不写 DONE", prefs.map[DONE_KEY])
        assertTrue("失败不缓存：结构化记忆仍会被再次清理", spy.deleteAllCalls.get() >= 1)

        // 修复阻碍（删掉占位文件和目录），重试——同一用例内完成，不依赖其他用例
        blockedDir.deleteRecursively()
        writeLegacyFiles()
        val prefs2 = ScriptedPrefs()
        val spy2 = DaoSpy()

        val succeeded = runBlocking { SessionMemoryReset.runIfNeeded(contextProxy(prefs2), dbSpy(spy2)) }

        assertTrue("修复后重试必须成功", succeeded)
        assertTrue("DONE 写入必须真的经过 commit", prefs2.commitCalls.get() >= 1)
        assertEquals("1", prefs2.map[DONE_KEY])
        assertTrue("旧文件必须真的被删掉", !File(filesDir, "vector_store.json").exists())
        assertEquals(
            "成功后才挂一次性提示",
            listOf("session_memory_reset_notice_pending" to "1"),
            spy2.noticeUpserts.map { it.key to it.value },
        )
    }

    @Test
    fun `成功后的二次调用幂等_不清数据_文件不动_不重复挂提示`() {
        writeLegacyFiles()
        val prefs = ScriptedPrefs()
        val spy = DaoSpy()

        // 第一次：完整清零成功
        val first = runBlocking { SessionMemoryReset.runIfNeeded(contextProxy(prefs), dbSpy(spy)) }

        assertTrue("首次必须成功", first)
        assertTrue("DONE 写入必须真的经过 commit", prefs.commitCalls.get() >= 1)
        assertEquals("1", prefs.map[DONE_KEY])
        assertTrue("旧文件必须已被清掉", !File(filesDir, "vector_store.json").exists())
        assertEquals(
            listOf("session_memory_reset_notice_pending" to "1"),
            spy.noticeUpserts.map { it.key to it.value },
        )

        // 第二次（同一夹具，复用同一 prefs/spy 以便计数比对）：必须什么都不做
        val second = runBlocking { SessionMemoryReset.runIfNeeded(contextProxy(prefs), dbSpy(spy)) }

        assertTrue(second)
        assertEquals(
            "不得重做清理",
            1,
            spy.deleteAllCalls.get(),
        )
        assertEquals(
            "不得重复挂提示",
            1,
            spy.noticeUpserts.size,
        )
        assertTrue("文件不得被再次触碰", !File(filesDir, "vector_store.json").exists())
        assertEquals("1", prefs.map[DONE_KEY])
    }

    @Test
    fun `DONE 已持久化且提示已确认_不补挂 pending`() {
        writeLegacyFiles()
        val prefs = ScriptedPrefs()
        prefs.map[DONE_KEY] = "1"
        val spy = DaoSpy().apply { noticeSeenValue = "1" }

        val result = runBlocking { SessionMemoryReset.runIfNeeded(contextProxy(prefs), dbSpy(spy)) }

        assertTrue(result)
        assertEquals("结构化记忆不得再被清", 0, spy.deleteAllCalls.get())
        assertTrue("提示已确认后不得补挂", spy.noticeUpserts.isEmpty())
        assertTrue("文件不得再被删", File(filesDir, "vector_store.json").exists())
    }

    @Test
    fun `DONE 已持久化且提示未确认_补挂 pending`() {
        writeLegacyFiles()
        val prefs = ScriptedPrefs()
        prefs.map[DONE_KEY] = "1"
        val spy = DaoSpy() // noticeSeenValue=null → 用户未确认过提示

        val result = runBlocking { SessionMemoryReset.runIfNeeded(contextProxy(prefs), dbSpy(spy)) }

        assertTrue(result)
        assertEquals("只补通知，绝不重做清理", 0, spy.deleteAllCalls.get())
        assertEquals(
            "提示未确认时必须补挂 pending=1",
            listOf("session_memory_reset_notice_pending" to "1"),
            spy.noticeUpserts.map { it.key to it.value },
        )
        assertTrue("文件不得再被删", File(filesDir, "vector_store.json").exists())
    }

    private fun resetCompleted() {
        val field = SessionMemoryReset::class.java.getDeclaredField("completed")
        field.isAccessible = true
        field.set(SessionMemoryReset, null)
    }

    private fun writeLegacyFilesExceptVector() {
        File(filesDir, "pending_extraction.json").writeText("""{"sessions":[],"inflight":[]}""")
    }
}
