package com.mistbell.tavern.android.extension.builtin

import com.mistbell.tavern.android.data.local.dao.SettingsDao
import com.mistbell.tavern.android.data.local.entity.SettingsEntity
import com.mistbell.tavern.android.data.model.UiMode
import com.mistbell.tavern.android.extension.ReplyHookContext
import com.mistbell.tavern.android.extension.TavernEvent
import com.mistbell.tavern.android.extension.TavernEventBus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MVU 扩展的界面模式闸（P3 执行边界，docs/UI_MODE.md）JVM 单测：
 * 简洁模式返回 MvuEngine 的 cleanedText（块不外漏）、不写 ext.mvu.*、不发 VariablesUpdated；
 * 高级模式三者照旧；模式流不给确定值时 process 挂起等待，绝不按「未知即高级」放行。
 * 块不会在 process 之前漏出去——断言返回值不含 UpdateVariable 标记。
 */
class MvuExtensionUiModeGateTest {
    private val memSettings = mutableMapOf<String, String>()

    private fun extensionOf(mode: UiMode) =
        MvuExtension(
            modeProvider = { flowOf(mode) },
            settingsDaoProvider = { fakeSettingsDao },
        )

    private val fakeSettingsDao =
        object : SettingsDao {
            override fun getAll(): Flow<List<SettingsEntity>> {
                return MutableStateFlow(memSettings.map { SettingsEntity(it.key, it.value) })
            }

            override suspend fun getValue(key: String): String? = memSettings[key]

            override fun observeValue(key: String): Flow<String?> = MutableStateFlow(memSettings[key]).map { it }

            override suspend fun upsert(setting: SettingsEntity) {
                memSettings[setting.key] = setting.value
            }

            override suspend fun deleteByKey(key: String) {
                memSettings.remove(key)
            }

            override suspend fun deleteAll() {
                memSettings.clear()
            }
        }

    /** 一条必然应用成功的 UpdateVariable 回复 */
    private fun updateReply(): String = "前文${UPDATE_BLOCK}后文"

    private val ctx = ReplyHookContext("owner", "char", "session-1")

    /** 先订阅再执行，收齐执行期间的 VariablesUpdated */
    private fun processCollectingEvents(extension: MvuExtension): Pair<String, List<TavernEvent>> =
        runBlocking {
            val events = mutableListOf<TavernEvent>()
            val collector = launch { TavernEventBus.events.collect { events.add(it) } }
            yield()
            val out = extension.process(updateReply(), ctx)
            yield()
            collector.cancel()
            out to events
        }

    @Test
    fun `简洁模式返回干净文本 不写库 不发事件`() {
        val (out, events) = processCollectingEvents(extensionOf(UiMode.SIMPLE))

        // 块被剥掉：返回的是 cleanedText，且块标记不会在 process 之前漏出去
        assertEquals("前文后文", out)
        assertFalse(out.contains("<UpdateVariable>"))
        assertFalse(out.contains("JSONPatch"))

        // 不写 ext.mvu.*（stat 与 ver 两个键都没有）
        assertTrue(memSettings.isEmpty())
        assertTrue(memSettings.none { it.key.startsWith("ext.mvu.") })

        // 不发 VariablesUpdated
        assertTrue(events.none { it is TavernEvent.VariablesUpdated })
    }

    @Test
    fun `高级模式写库并发事件`() {
        val (out, events) = processCollectingEvents(extensionOf(UiMode.ADVANCED))

        assertEquals("前文后文", out)
        assertFalse(out.contains("<UpdateVariable>"))
        val statRaw = memSettings[MvuExtension.statKey("session-1")]
        val statData = kotlinx.serialization.json.Json.parseToJsonElement(statRaw!!).jsonObject
        assertEquals("42", statData["好感度"]!!.jsonPrimitive.content)
        assertEquals("1", memSettings[MvuExtension.versionKey("session-1")])
        assertEquals(1, events.filterIsInstance<TavernEvent.VariablesUpdated>().single().version)
    }

    @Test
    fun `模式未知时 process 挂起等待 不读不写不放行`() {
        // 生产接法（AppContainer）传 uiMode.filterNotNull()：启动头几毫秒模式流无确定值且
        // 永不完成，process 的 first() 挂起——用短超时验证它不会放行，也不落库
        val extension =
            MvuExtension(
                modeProvider = { MutableSharedFlow() },
                settingsDaoProvider = { fakeSettingsDao },
            )
        val result =
            runBlocking {
                withTimeoutOrNull(timeMillis = 300) { extension.process(updateReply(), ctx) }
            }
        assertNull("模式未知时 process 应挂起等待而非放行", result)
        assertTrue(memSettings.isEmpty())
    }

    private companion object {
        /** 一条必然应用成功的 UpdateVariable 块 */
        const val UPDATE_BLOCK =
            "<UpdateVariable>\n<Analysis>x</Analysis>\n<JSONPatch>\n" +
                """[{"op":"replace","path":"/好感度","value":42}]""" +
                "\n</JSONPatch>\n</UpdateVariable>"
    }
}
