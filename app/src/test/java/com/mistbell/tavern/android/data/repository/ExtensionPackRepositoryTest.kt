package com.mistbell.tavern.android.data.repository

import com.mistbell.tavern.android.data.extensionpack.ExtensionPackStore
import com.mistbell.tavern.android.data.local.dao.ExtensionPackDao
import com.mistbell.tavern.android.data.local.entity.ExtensionPackEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 扩展包仓库 JVM 测试（假 DAO + 临时目录 + 字节注入）：
 * 两段式导入、启停守卫（未确认不可启用）、删除清理目录、覆盖安装 hash 比对语义。
 */
class ExtensionPackRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** 内存 DAO：capture upsert / 启停 / 删除，observeAll 由内存表驱动 */
    private class FakeExtensionPackDao : ExtensionPackDao {
        val rows = MutableStateFlow<List<ExtensionPackEntity>>(emptyList())

        override fun observeAll(): Flow<List<ExtensionPackEntity>> = rows

        override suspend fun getById(id: String): ExtensionPackEntity? = rows.value.firstOrNull { it.id == id }

        override fun observeEnabled(): Flow<List<ExtensionPackEntity>> = rows.map { list -> list.filter { it.enabled } }

        override suspend fun upsert(pack: ExtensionPackEntity) {
            rows.value = rows.value.filterNot { it.id == pack.id } + pack
        }

        override suspend fun setEnabled(
            id: String,
            enabled: Boolean,
        ) {
            rows.value = rows.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
        }

        override suspend fun deleteById(id: String) {
            rows.value = rows.value.filterNot { it.id == id }
        }
    }

    private val dao = FakeExtensionPackDao()
    private lateinit var filesDir: File

    @Before
    fun setUp() {
        filesDir = tmp.newFolder("files")
    }

    private fun repo(): ExtensionPackRepository = ExtensionPackRepository(filesDir, daoProvider = { dao })

    private fun zipBytes(entries: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** v1：声明 prompt:inject + 预注入脚本 */
    private val v1Zip =
        zipBytes(
            mapOf(
                "manifest.json" to
                    """{"id":"demo-ext","name":"演示扩展","author":"Mistbell","version":"1.0.0",""" +
                    """"type":"extension","permissions":["prompt:inject"],"webviewPreload":"facade.js"}""",
                "facade.js" to "window.TavernHelper = {};",
            ),
        )

    /** v2：权限集不变（内容变化） */
    private val v2Zip =
        zipBytes(
            mapOf(
                "manifest.json" to
                    """{"id":"demo-ext","name":"演示扩展","author":"Mistbell","version":"1.1.0",""" +
                    """"type":"extension","permissions":["prompt:inject"],"webviewPreload":"facade.js"}""",
                "facade.js" to "window.TavernHelper = { v: 2 };",
            ),
        )

    /** v3：权限集扩大（新增 messages:write） */
    private val v3Zip =
        zipBytes(
            mapOf(
                "manifest.json" to
                    """{"id":"demo-ext","name":"演示扩展","author":"Mistbell","version":"1.2.0",""" +
                    """"type":"extension","permissions":["prompt:inject","messages:write"],""" +
                    """"webviewPreload":"facade.js"}""",
                "facade.js" to "window.TavernHelper = { v: 3 };",
            ),
        )

    private fun previewOf(zip: ByteArray): ExtensionPackRepository.ExtensionPreview = repo().previewFromBytes(zip)

    // ---- 预览 ----

    @Test
    fun `preview 附带内容哈希与中文权限清单`() {
        val preview = previewOf(v1Zip)
        assertEquals(64, preview.contentHash.length)
        assertEquals(1, preview.permissions.size)
        assertEquals("prompt:inject", preview.permissions.single().raw)
        assertTrue(preview.permissions.single().description.isNotBlank())
        assertTrue(preview.needsConfirmation)
    }

    // ---- persist：新包 ----

    @Test
    fun `确认导入新包落库并解压落盘且默认启用`() =
        runBlocking {
            val result = repo().persist(previewOf(v1Zip), confirmedByUser = true)
            assertEquals(ExtensionPackStore.PersistMode.FRESH, result.mode)

            val row = dao.getById("demo-ext")
            assertNotNull(row)
            assertTrue(row!!.enabled)
            assertTrue(row.permissionsConfirmed)
            assertTrue(row.hasPreload)
            assertEquals(listOf("prompt:inject"), ExtensionPackStore.decodePermissions(row.permissionsJson))

            val dir = ExtensionPackStore.packDir(filesDir, "demo-ext")
            assertTrue(File(dir, "manifest.json").exists())
            assertTrue(File(dir, "facade.js").exists())
        }

    @Test
    fun `未经确认的新包保持禁用且不可手动启用`() =
        runBlocking {
            repo().persist(previewOf(v1Zip), confirmedByUser = false)
            assertFalse(dao.getById("demo-ext")!!.enabled)
            assertFalse(dao.getById("demo-ext")!!.permissionsConfirmed)

            assertThrows(IllegalStateException::class.java) {
                runBlocking { repo().setEnabled("demo-ext", true) }
            }
            assertFalse(dao.getById("demo-ext")!!.enabled)
        }

    // ---- persist：覆盖安装 ----

    @Test
    fun `同 hash 覆盖仅刷新元数据不重解压`() =
        runBlocking {
            val repo = repo()
            repo.persist(previewOf(v1Zip), confirmedByUser = true)

            // 在包目录植入哨兵文件：REFRESH_METADATA 不重解压 → 哨兵应存活
            val sentinel = File(ExtensionPackStore.packDir(filesDir, "demo-ext"), "sentinel.txt")
            sentinel.writeText("keep")

            val result = repo.persist(previewOf(v1Zip), confirmedByUser = true)
            assertEquals(ExtensionPackStore.PersistMode.REFRESH_METADATA, result.mode)
            assertTrue(sentinel.exists())
            assertTrue(dao.getById("demo-ext")!!.enabled)
        }

    @Test
    fun `hash 变化覆盖更新内容文件与元数据且保留启用`() =
        runBlocking {
            val repo = repo()
            repo.persist(previewOf(v1Zip), confirmedByUser = true)

            val sentinel = File(ExtensionPackStore.packDir(filesDir, "demo-ext"), "sentinel.txt")
            sentinel.writeText("stale")

            val result = repo.persist(previewOf(v2Zip), confirmedByUser = true)
            assertEquals(ExtensionPackStore.PersistMode.UPDATE_CONTENT, result.mode)
            assertFalse("旧内容应被清掉", sentinel.exists())
            val facade = File(ExtensionPackStore.packDir(filesDir, "demo-ext"), "facade.js")
            assertTrue(facade.readText().contains("v: 2"))
            assertEquals("1.1.0", dao.getById("demo-ext")!!.version)
            assertTrue(dao.getById("demo-ext")!!.enabled) // 无扩权 → 启用保留
        }

    @Test
    fun `覆盖安装权限扩大未经确认则强制回未授权禁用态`() =
        runBlocking {
            val repo = repo()
            repo.persist(previewOf(v1Zip), confirmedByUser = true)

            // v3 扩大权限集，防御路径未带确认
            val result = repo.persist(previewOf(v3Zip), confirmedByUser = false)
            assertEquals(ExtensionPackStore.PersistMode.UPDATE_CONTENT, result.mode)
            val row = dao.getById("demo-ext")!!
            assertFalse(row.enabled)
            assertFalse(row.permissionsConfirmed)
            assertEquals(
                listOf("prompt:inject", "messages:write"),
                ExtensionPackStore.decodePermissions(row.permissionsJson),
            )

            // 未授权态下启用被拒
            val thrown =
                assertThrows(IllegalStateException::class.java) {
                    runBlocking { repo.setEnabled("demo-ext", true) }
                }
            assertTrue(thrown.message!!.contains("权限"))
            assertFalse(dao.getById("demo-ext")!!.enabled)
        }

    @Test
    fun `覆盖安装权限扩大且本轮已确认则保持启用`() =
        runBlocking {
            val repo = repo()
            repo.persist(previewOf(v1Zip), confirmedByUser = true)
            repo.persist(previewOf(v3Zip), confirmedByUser = true)
            val row = dao.getById("demo-ext")!!
            assertTrue(row.enabled)
            assertTrue(row.permissionsConfirmed)
        }

    // ---- 启停 ----

    @Test
    fun `已确认包可正常启停且状态持久于 DAO`() =
        runBlocking {
            val repo = repo()
            repo.persist(previewOf(v1Zip), confirmedByUser = true)
            repo.setEnabled("demo-ext", false)
            assertFalse(dao.getById("demo-ext")!!.enabled)
            repo.setEnabled("demo-ext", true)
            assertTrue(dao.getById("demo-ext")!!.enabled)
        }

    @Test
    fun `启用不存在的包报错`() {
        val thrown =
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repo().setEnabled("ghost", true) }
            }
        assertTrue(thrown.message!!.contains("ghost"))
    }

    // ---- 删除 ----

    @Test
    fun `删除清理 DB 行与内容目录`() =
        runBlocking {
            val repo = repo()
            repo.persist(previewOf(v1Zip), confirmedByUser = true)
            val dir = ExtensionPackStore.packDir(filesDir, "demo-ext")
            assertTrue(dir.exists())

            repo.deletePack("demo-ext")
            assertFalse(dir.exists())
            assertEquals(emptyList<ExtensionPackEntity>(), dao.rows.value)
        }
}
