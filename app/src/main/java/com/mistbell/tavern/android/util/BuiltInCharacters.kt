package com.mistbell.tavern.android.util

import com.mistbell.tavern.android.data.local.AppDatabase
import com.mistbell.tavern.android.data.local.entity.CharacterEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 内置角色（P3 第 4 项「首启与内容」，docs/ROADMAP.md「P3 门面」）：随 APK 分发在
 * assets/sample_characters/ 下的 v2 卡，共四位。单一真相：
 *
 * - **身份口径**：落库时角色 id 固定为 [ID_PREFIX] + 资产名去扩展名（如 `builtin_aria`），
 *   不再是导入路径的随机 UUID。「落库」与「生态信号查询」（UiModeEcosystemProbe 排除内置）
 *   用同一个 [isBuiltIn] 判断，口径永远一致。
 * - **幂等落库**：[install] 按「已有同 id 或同名角色就整体跳过（含世界书）」——
 *   同名兜底覆盖老用户升级前经旧引导导入的随机 id 示例卡，避免二次安装出重复角色。
 * - **不自动写入**：install 只有两个调用方（首启引导选了角色时、角色 Tab 的「内置角色」
 *   入口），启动路径绝不调用——老用户升级后不会被悄悄塞进新角色。
 */
object BuiltInCharacters {
    /** 内置角色稳定 id 的统一前缀（isBuiltIn 的唯一判据） */
    const val ID_PREFIX = "builtin_"

    /** 资产目录（相对 assets 根） */
    const val ASSET_DIR = "sample_characters/"

    /** 一位内置角色的定义（目录条目；blurb 用于首启选角色页与角色 Tab 的「内置角色」列表） */
    data class Definition(
        val assetName: String,
        val id: String,
        val displayName: String,
        val blurb: String,
    )

    /** 全部内置角色（新增内置角色：加资产 JSON + 在这里补一行；测试会核对两者不漂移） */
    val all: List<Definition> =
        listOf(
            Definition(
                "aria.json",
                "builtin_aria",
                "阿莉雅",
                "雾铃镇临海老店「夜航灯」的女主人，记性极好，认得镇上每一张脸",
            ),
            Definition(
                "kael.json",
                "builtin_kael",
                "凯尔",
                "货运船「游隼号」的领航员，话少，但每一句都算数",
            ),
            Definition(
                "qingwu.json",
                "builtin_qingwu",
                "沈青梧",
                "民国水乡小镇的修表匠，修表先听表的故事，句句落在实处",
            ),
            Definition(
                "chengye.json",
                "builtin_chengye",
                "程野",
                "山顶气象站的驻站观测员，追着一整个夏天的雷暴",
            ),
        )

    fun byId(id: String): Definition? = all.firstOrNull { it.id == id }

    /** 内置身份判定（落库与生态信号查询共用的唯一口径） */
    fun isBuiltIn(characterId: String): Boolean = characterId.startsWith(ID_PREFIX)

    /** 还没落库的内置角色（全新安装 = 全部；老用户 = 缺哪位补哪位） */
    fun missingInstalled(existingIds: Collection<String>): List<Definition> {
        return all.filter { def -> existingIds.none { it == def.id } }
    }

    /**
     * 纯核心：解析资产 JSON 并把角色 id 换成内置稳定 id。
     * 世界书 id 保持 CardParser 生成的随机 UUID（生态信号只看角色 id，无需稳定）。
     * 解析失败返回 null（资产损坏不应让调用方崩溃；单测守住资产可解析）。
     */
    fun parseAsset(
        json: String,
        def: Definition,
    ): CharacterImportResult? = CardParser.parse(json)?.let { it.copy(character = it.character.copy(id = def.id)) }

    /**
     * 把内置角色落库（幂等，[Dispatchers.IO]）。
     *
     * 每位定义独立处理：解析资产 → 已有同 id 角色则跳过 → 已有同名角色（老用户升级前
     * 经旧引导导入的随机 id 示例卡）也跳过，避免二次安装出现重复角色 → 否则世界书先行、
     * 角色随后（与导入路径同一顺序）。资产读取经 [readAssetJson] 由调用方注入
     * （Android 读 assets；JVM 测试读仓库文件），本函数本体保持纯协程、可单测。
     *
     * @return 本次新落库的角色名列表（全部已存在时为空表）
     */
    suspend fun install(
        db: AppDatabase,
        definitions: List<Definition>,
        readAssetJson: suspend (String) -> String,
    ): List<String> =
        withContext(Dispatchers.IO) {
            val existing = db.characterDao().getAllOnce()
            definitions.mapNotNull { def -> installOne(db, existing, def, readAssetJson) }
        }

    /** 落库一位内置角色；已存在（同 id 或同名）或资产损坏时返回 null（不写入） */
    private suspend fun installOne(
        db: AppDatabase,
        existing: List<CharacterEntity>,
        def: Definition,
        readAssetJson: suspend (String) -> String,
    ): String? {
        val parsed =
            if (existing.any { it.id == def.id }) {
                null
            } else {
                parseAsset(readAssetJson(def.assetName), def).also { result ->
                    if (result == null) {
                        android.util.Log.w("BuiltInCharacters", "内置角色资产解析失败，跳过: ${def.assetName}")
                    }
                }
            }
        // 同名兜底：老用户升级前的旧示例卡是随机 id，同 id 检查认不出，靠名字拦住重复
        val writable = parsed?.takeIf { result -> existing.none { it.name.trim() == result.character.name.trim() } }
        if (writable == null) return null
        writable.worldBook?.let { book ->
            db.worldBookDao().upsertBook(book)
            if (writable.worldBookEntries.isNotEmpty()) {
                db.worldBookDao().upsertEntries(writable.worldBookEntries)
            }
        }
        db.characterDao().upsert(writable.character)
        return writable.character.name
    }
}
