package com.mistbell.tavern.android.data.repository

import com.mistbell.tavern.android.data.extensionpack.BuiltinPackSeeder
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

        override suspend fun getEnabled(): List<ExtensionPackEntity> = rows.value.filter { it.enabled }

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

    // ---- 提供者互斥（P2）----

    /** 声明同一 provides 名的两个门面包（A/B）与不声明 provides 的旁观包（providesJson 为 JSON 数组原文） */
    private fun providerZip(
        id: String,
        providesJson: String,
    ): ByteArray =
        zipBytes(
            mapOf(
                "manifest.json" to
                    """{"id":"$id","name":"门面包$id","author":"Mistbell","version":"1.0.0",""" +
                    """"type":"extension","provides":$providesJson,"permissions":["messages:read"],""" +
                    """"webviewPreload":"facade.js"}""",
                "facade.js" to "window.TavernHelper = { id: '$id' };",
            ),
        )

    private val facadeProvides: String = """["facade:tavernhelper"]"""

    @Test
    fun `启用声明同 provides 名的包自动停用已启用竞争包并上报名单`() =
        runBlocking {
            val repo = repo()
            repo.persist(previewOf(providerZip("facade-a", facadeProvides)), confirmedByUser = true)
            assertTrue(dao.getById("facade-a")!!.enabled)

            // 导入即启用 B：同一事务内 A 被自动停用，名单上报 UI
            val result =
                repo.persist(previewOf(providerZip("facade-b", facadeProvides)), confirmedByUser = true)
            assertTrue(dao.getById("facade-b")!!.enabled)
            assertFalse(dao.getById("facade-a")!!.enabled)
            assertEquals(listOf("门面包facade-a"), result.disabledCompetitors)
        }

    @Test
    fun `手动启用走同一互斥返回被停用包名`() =
        runBlocking {
            val repo = repo()
            repo.persist(previewOf(providerZip("facade-a", facadeProvides)), confirmedByUser = true)
            repo.persist(previewOf(providerZip("facade-b", facadeProvides)), confirmedByUser = true)
            // 此时 A 已被 B 互斥停用：重新启用 A 应反向停用 B
            val disabledNames = repo.setEnabled("facade-a", true)
            assertTrue(dao.getById("facade-a")!!.enabled)
            assertFalse(dao.getById("facade-b")!!.enabled)
            assertEquals(listOf("门面包facade-b"), disabledNames)
        }

    @Test
    fun `未声明 provides 的包不参与互斥`() =
        runBlocking {
            val repo = repo()
            repo.persist(previewOf(providerZip("facade-a", facadeProvides)), confirmedByUser = true)
            val result = repo.persist(previewOf(v1Zip), confirmedByUser = true)
            assertTrue(dao.getById("facade-a")!!.enabled)
            assertTrue(result.disabledCompetitors.isEmpty())
        }

    @Test
    fun `禁用操作不触发互斥`() =
        runBlocking {
            val repo = repo()
            repo.persist(previewOf(providerZip("facade-a", facadeProvides)), confirmedByUser = true)
            repo.persist(previewOf(providerZip("facade-b", facadeProvides)), confirmedByUser = true)
            val disabledNames = repo.setEnabled("facade-b", false)
            assertTrue(disabledNames.isEmpty())
            assertFalse(dao.getById("facade-a")!!.enabled)
        }

    // ---- 内置包种子（P2）----

    /** 内置 tavernhelper-compat 包的 assets 文件组（镜像真实制品形态） */
    private fun builtinFiles(facade: String = "window.TavernHelper = {};"): Map<String, String> =
        mapOf(
            "manifest.json" to
                """{"id":"tavernhelper-compat","name":"TavernHelper 兼容层","author":"Mistbell",""" +
                """"version":"1.0.0","type":"extension","provides":["facade:tavernhelper"],""" +
                """"permissions":["messages:read","messages:write","generation:control","variables",""" +
                """"events:subscribe","prompt:inject","reply:process"],"webviewPreload":"facade.js"}""",
            "facade.js" to facade,
        )

    private suspend fun seed(files: Map<String, String> = builtinFiles()): ExtensionPackRepository.ImportResult? {
        return repo().seedBuiltinPack(BuiltinPackSeeder.zipOf(files))
    }

    @Test
    fun `种子新装落库落盘且为启用已确认的内置包`() =
        runBlocking {
            val result = seed()
            assertNotNull(result)
            val row = dao.getById("tavernhelper-compat")
            assertNotNull(row)
            assertTrue(row!!.isBuiltin)
            assertTrue(row.enabled)
            assertTrue(row.permissionsConfirmed)
            assertTrue(File(ExtensionPackStore.packDir(filesDir, "tavernhelper-compat"), "facade.js").exists())
        }

    @Test
    fun `种子同 hash 已存在则跳过并保留用户停用态`() =
        runBlocking {
            seed()
            // 用户停用内置包
            repo().setEnabled("tavernhelper-compat", false)
            // 同 hash 再种子：SKIP，停用态保留
            val result = seed()
            assertEquals(null, result)
            assertFalse(dao.getById("tavernhelper-compat")!!.enabled)
            assertTrue(dao.getById("tavernhelper-compat")!!.isBuiltin)
        }

    @Test
    fun `种子 hash 变化刷新内容且保留停用态`() =
        runBlocking {
            seed()
            repo().setEnabled("tavernhelper-compat", false)

            val result = seed(builtinFiles(facade = "window.TavernHelper = { v: 2 };"))
            assertNotNull(result)
            assertEquals(ExtensionPackStore.PersistMode.UPDATE_CONTENT, result!!.mode)
            val row = dao.getById("tavernhelper-compat")!!
            assertFalse("用户停用态保留", row.enabled)
            assertTrue(row.isBuiltin)
            assertTrue(
                File(ExtensionPackStore.packDir(filesDir, "tavernhelper-compat"), "facade.js")
                    .readText()
                    .contains("v: 2"),
            )
        }

    @Test
    fun `删除内置包后下次启动种子重播种`() =
        runBlocking {
            seed()
            repo().deletePack("tavernhelper-compat")
            assertEquals(emptyList<ExtensionPackEntity>(), dao.rows.value)

            val result = seed()
            assertNotNull(result)
            val row = dao.getById("tavernhelper-compat")
            assertNotNull(row)
            assertTrue(row!!.isBuiltin)
            assertTrue(row.enabled)
        }

    @Test
    fun `内置包种子触发提供者互斥停用竞争第三方包`() =
        runBlocking {
            val repo = repo()
            // 用户先装了第三方门面（同 provides 名）并启用
            repo.persist(previewOf(providerZip("facade-3rd", facadeProvides)), confirmedByUser = true)
            assertTrue(dao.getById("facade-3rd")!!.enabled)

            // 内置包种子（启用态）→ 第三方被互斥停用
            seed()
            assertTrue(dao.getById("tavernhelper-compat")!!.enabled)
            assertFalse(dao.getById("facade-3rd")!!.enabled)
        }
}
