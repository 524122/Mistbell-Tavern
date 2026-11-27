package com.mistbell.tavern.android.data.prompt

import com.mistbell.tavern.android.data.local.entity.CharacterEntity
import com.mistbell.tavern.android.data.local.entity.GlobalRegexRuleEntity
import com.mistbell.tavern.android.data.local.entity.MessageEntity
import com.mistbell.tavern.android.data.local.entity.SessionEntity
import com.mistbell.tavern.android.data.local.entity.SettingsEntity
import com.mistbell.tavern.android.data.local.entity.WorldBookEntryEntity
import com.mistbell.tavern.android.data.repository.ChatSettingsResolver
import com.mistbell.tavern.android.data.repository.SettingsRepository
import com.mistbell.tavern.android.eval.memory.FakeTavernDb
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 界面模式下的提示词装配（P3 执行边界，docs/UI_MODE.md）——走真实 buildPromptTrace +
 * FakeTavernDb（项目既有匿名 AppDatabase + Proxy 替身）：
 * - 世界书四源（会话书 / 人设书 / 角色书 / 全局书）在简洁模式下照常注入（不滤成只留角色书）；
 * - 全局正则在简洁模式下不生效：含 SECRET 的历史行在提示词里逐字节原样；高级模式下被改写。
 */
class PromptBuilderUiModeWorldBookTest {
    private val ownerId = "owner"
    private val characterId = "char-1"
    private val sessionId = "session-1"

    /** 四源世界书：书 id → 常驻条目内容 */
    private fun seedWorldBooks(db: FakeTavernDb) {
        val sources =
            mapOf(
                "book-chat" to "会话书内容MARK",
                "book-persona" to "人设书内容MARK",
                "book-character" to "角色书内容MARK",
                "book-global" to "全局书内容MARK",
            )
        runBlocking {
            sources.forEach { (bookId, content) ->
                db.db().worldBookDao().upsertEntries(
                    listOf(
                        WorldBookEntryEntity(
                            id = "entry-$bookId",
                            bookId = bookId,
                            comment = bookId,
                            keysJson = "[]",
                            content = content,
                            constant = true,
                            disable = false,
                            order = 100,
                        ),
                    ),
                )
            }
            db.db().settingsDao().upsert(
                SettingsEntity(ChatSettingsResolver.KEY_WORLD_INFO_PERSONA_BOOK_ID, "book-persona"),
            )
            db.db().settingsDao().upsert(
                SettingsEntity(ChatSettingsResolver.KEY_WORLD_INFO_GLOBAL_BOOK_IDS, """["book-global"]"""),
            )
        }
    }

    private fun seedSessionAndCharacter(db: FakeTavernDb) {
        runBlocking {
            db.db().characterDao().upsert(
                CharacterEntity(
                    id = characterId,
                    name = "阿莉雅",
                    role = "assistant",
                    description = "测试角色",
                    personality = "",
                    scenario = "",
                    firstMes = "",
                    mesExample = "",
                    color = "",
                    avatarData = "",
                    worldBookId = "book-character",
                    dataJson = "",
                ),
            )
            db.db().sessionDao().upsert(
                SessionEntity(
                    id = sessionId,
                    ownerId = ownerId,
                    characterId = characterId,
                    title = "测试会话",
                    createdAt = "2026-01-01T00:00:00Z",
                    updatedAt = "2026-01-01T00:00:00Z",
                    messageCount = 1,
                    providerId = "",
                    modelId = "",
                    worldBookId = "book-chat",
                    summaryJson = "",
                ),
            )
            db.db().messageDao().upsert(
                MessageEntity(
                    id = "m1",
                    sessionId = sessionId,
                    ownerId = ownerId,
                    characterId = characterId,
                    role = "assistant",
                    content = "历史消息 SECRET 一",
                    thinking = null,
                    createdAt = "2026-01-01T00:01:00Z",
                    memoryIdsJson = "",
                    swipesJson = "",
                    swipeIndex = 0,
                    thinkingSwipesJson = "",
                ),
            )
        }
    }

    private fun seedGlobalRegex(db: FakeTavernDb) {
        runBlocking {
            db.db().globalRegexDao().upsertAll(
                listOf(
                    GlobalRegexRuleEntity(
                        id = "g1",
                        name = "改写",
                        findRegex = "SECRET",
                        replaceString = "REVEALED",
                        disabled = false,
                        placementJson = "[1,2]",
                    ),
                ),
            )
        }
    }

    private fun setUiMode(
        db: FakeTavernDb,
        raw: String?,
    ) {
        runBlocking {
            if (raw == null) {
                db.db().settingsDao().deleteByKey(SettingsRepository.KEY_UI_MODE)
            } else {
                db.db().settingsDao().upsert(SettingsEntity(SettingsRepository.KEY_UI_MODE, raw))
            }
        }
    }

    private fun buildPrompt(db: FakeTavernDb): String =
        runBlocking {
            PromptBuilder.buildPrompt(
                db.db(),
                PromptBuilder.PromptRequest(
                    ownerId = ownerId,
                    characterId = characterId,
                    sessionId = sessionId,
                    userMessage = "当前消息 SECRET 结尾",
                ),
            ).joinToString("\n") { it.content }
        }

    @Test
    fun `简洁模式世界书四源都进提示词且正则不生效`() {
        val db = FakeTavernDb()
        seedWorldBooks(db)
        seedSessionAndCharacter(db)
        seedGlobalRegex(db)
        setUiMode(db, "simple")

        val prompt = buildPrompt(db)

        // 四源照常注入（会话书 / 人设书 / 角色书 / 全局书）
        assertTrue("会话书应注入：\n$prompt", prompt.contains("会话书内容MARK"))
        assertTrue("人设书应注入：\n$prompt", prompt.contains("人设书内容MARK"))
        assertTrue("角色书应注入：\n$prompt", prompt.contains("角色书内容MARK"))
        assertTrue("全局书应注入：\n$prompt", prompt.contains("全局书内容MARK"))

        // 简洁模式正则不生效：历史与当前消息都原样
        assertTrue(prompt.contains("历史消息 SECRET 一"))
        assertTrue(prompt.contains("当前消息 SECRET 结尾"))
        assertFalse("简洁模式不得出现改写痕迹", prompt.contains("REVEALED"))
    }

    @Test
    fun `高级模式四源照旧且正则生效`() {
        val db = FakeTavernDb()
        seedWorldBooks(db)
        seedSessionAndCharacter(db)
        seedGlobalRegex(db)
        setUiMode(db, "advanced")

        val prompt = buildPrompt(db)
        assertTrue(prompt.contains("会话书内容MARK"))
        assertTrue(prompt.contains("人设书内容MARK"))
        assertTrue(prompt.contains("角色书内容MARK"))
        assertTrue(prompt.contains("全局书内容MARK"))
        assertTrue("高级模式正则应改写历史", prompt.contains("历史消息 REVEALED 一"))
        assertTrue("高级模式正则应改写当前消息", prompt.contains("当前消息 REVEALED 结尾"))
    }

    @Test
    fun `未设置模式时与高级行为一致`() {
        val db = FakeTavernDb()
        seedWorldBooks(db)
        seedSessionAndCharacter(db)
        seedGlobalRegex(db)
        setUiMode(db, null)

        val prompt = buildPrompt(db)
        assertTrue(prompt.contains("历史消息 REVEALED 一"))
        assertTrue(prompt.contains("全局书内容MARK"))
    }
}
