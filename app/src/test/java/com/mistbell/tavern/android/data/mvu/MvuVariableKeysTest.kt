package com.mistbell.tavern.android.data.mvu

import com.mistbell.tavern.android.data.local.dao.SettingsDao
import com.mistbell.tavern.android.data.local.entity.SettingsEntity
import com.mistbell.tavern.android.extension.builtin.MvuExtension
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MVU 会话变量键的契约（无 Robolectric，SettingsDao 接口小、直接手写内存替身）：
 * 1) 键名格式（stat_data 与版本号两个键，与 MvuExtension 的存量格式逐字符一致）；
 * 2) deleteSessionKeys 删除/清空会话时把两个键一并清掉、不动其他键；键不存在时是安全 no-op。
 * 该清理挂在 ChatRepository 的 deleteSession/clearConversation 与 ChatListViewModel 的
 * deleteSessionDataLocked（批量删除共用）三处删除路径上。
 */
class MvuVariableKeysTest {
    /** 内存版 SettingsDao 替身：键值即库态 */
    private class FakeSettingsDao : SettingsDao {
        val map = linkedMapOf<String, String>()

        override fun getAll(): Flow<List<SettingsEntity>> = flow { emit(map.map { SettingsEntity(it.key, it.value) }) }

        override fun observeValue(key: String): Flow<String?> = flow { emit(map[key]) }

        override suspend fun getValue(key: String): String? = map[key]

        override suspend fun upsert(setting: SettingsEntity) {
            map[setting.key] = setting.value
        }

        override suspend fun deleteByKey(key: String) {
            map.remove(key)
        }

        override suspend fun deleteAll() {
            map.clear()
        }
    }

    @Test
    fun `statKey与versionKey按会话id生成且与存量格式一致`() {
        assertEquals("ext.mvu.s1", MvuVariableKeys.statKey("s1"))
        assertEquals("ext.mvu.ver.s1", MvuVariableKeys.versionKey("s1"))
        // MvuExtension 委托同一生成器：存量调用方（ChatViewModel 等）拿到的键名不变
        assertEquals(MvuVariableKeys.statKey("s9"), MvuExtension.statKey("s9"))
        assertEquals(MvuVariableKeys.versionKey("s9"), MvuExtension.versionKey("s9"))
    }

    @Test
    fun `deleteSessionKeys清掉会话的两个键且不动其他键`() =
        runBlocking {
            val dao = FakeSettingsDao()
            dao.upsert(SettingsEntity(MvuVariableKeys.statKey("s1"), """{"hp":3}"""))
            dao.upsert(SettingsEntity(MvuVariableKeys.versionKey("s1"), "7"))
            dao.upsert(SettingsEntity("ext.mvu.s2", "其他会话的变量"))
            dao.upsert(SettingsEntity("unrelated.key", "无关设置"))

            MvuVariableKeys.deleteSessionKeys(dao, "s1")

            assertFalse("stat_data 键必须随会话删除", dao.map.containsKey("ext.mvu.s1"))
            assertFalse("版本号键必须随会话删除", dao.map.containsKey("ext.mvu.ver.s1"))
            assertEquals("其他会话的变量不得误删", "其他会话的变量", dao.map["ext.mvu.s2"])
            assertEquals("无关设置不得误删", "无关设置", dao.map["unrelated.key"])
        }

    @Test
    fun `键不存在时deleteSessionKeys是安全no-op`() =
        runBlocking {
            val dao = FakeSettingsDao()

            MvuVariableKeys.deleteSessionKeys(dao, "s-empty")

            assertTrue("空库删除不得抛错、不得写入任何键", dao.map.isEmpty())
        }
}
