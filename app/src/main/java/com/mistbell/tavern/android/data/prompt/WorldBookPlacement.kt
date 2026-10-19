package com.mistbell.tavern.android.data.prompt

import com.mistbell.tavern.android.data.local.entity.WorldBookEntryEntity

/**
 * 世界书条目插入位置规划（纯函数，JVM 可测）。
 *
 * 语义（v19 起 UI 名实相符）：
 * - depth = 0：按 insertPosition 锚定提示词区——
 *   [POSITION_BEFORE_PROMPT] 角色定义之前 / [POSITION_AFTER_PROMPT] 角色定义之后（LTM 之前）/
 *   [POSITION_AN_BEFORE] 作者注释之前 / [POSITION_AN_AFTER] 作者注释之后 /
 *   [POSITION_EM_BEFORE] 示例消息块之前 / [POSITION_EM_AFTER] 示例消息块之后；
 * - depth ≥ 1：@D 模式，对齐酒馆 position=4（atDepth）：depth 越小越靠近生成点
 *   （酒馆 depth=1 紧贴最新一条消息）；insertPosition 不再生效；
 * - disable 条目一律不参与。
 */
object WorldBookPlacement {
    const val POSITION_BEFORE_PROMPT = "before_prompt"
    const val POSITION_AFTER_PROMPT = "after_prompt"
    const val POSITION_AN_BEFORE = "an_before"
    const val POSITION_AN_AFTER = "an_after"
    const val POSITION_EM_BEFORE = "em_before"
    const val POSITION_EM_AFTER = "em_after"

    /** @D 插入角色（对齐酒馆 role）：条目以什么身份注入 @D 深度块 */
    const val DEPTH_ROLE_SYSTEM = "system"
    const val DEPTH_ROLE_USER = "user"
    const val DEPTH_ROLE_ASSISTANT = "assistant"

    data class Plan(
        val beforePrompt: List<WorldBookEntryEntity>,
        val afterPrompt: List<WorldBookEntryEntity>,
        val emBefore: List<WorldBookEntryEntity>,
        val emAfter: List<WorldBookEntryEntity>,
        val anBefore: List<WorldBookEntryEntity>,
        val anAfter: List<WorldBookEntryEntity>,
        /** @D 分组，key = (深度, 角色)；按深度降序（大 D 在前、D=1 最贴近生成点），
         *  同深度角色序为 assistant → user → system（对齐酒馆最终呈现序）；同组多条合成一条该角色的消息 */
        val byDepth: Map<Pair<Int, String>, List<WorldBookEntryEntity>>,
    )

    /** 酒馆同深度多 role 的最终呈现顺序（script.js doChatInject 反转数组所致）：assistant 最前，system 最靠底 */
    internal fun depthRoleRank(role: String): Int =
        when (role) {
            DEPTH_ROLE_ASSISTANT -> 0
            DEPTH_ROLE_USER -> 1
            else -> 2
        }

    fun plan(entries: List<WorldBookEntryEntity>): Plan {
        val active = entries.filter { !it.disable }
        val anchored = active.filter { it.depth <= 0 }
        return Plan(
            beforePrompt = anchored.filter { it.insertPosition == POSITION_BEFORE_PROMPT },
            afterPrompt = anchored.filter { it.insertPosition == POSITION_AFTER_PROMPT },
            emBefore = anchored.filter { it.insertPosition == POSITION_EM_BEFORE },
            emAfter = anchored.filter { it.insertPosition == POSITION_EM_AFTER },
            anBefore = anchored.filter { it.insertPosition == POSITION_AN_BEFORE },
            anAfter = anchored.filter { it.insertPosition == POSITION_AN_AFTER },
            byDepth =
                active
                    .filter { it.depth > 0 }
                    .groupBy { it.depth to it.depthRole }
                    .toSortedMap(
                        compareByDescending<Pair<Int, String>> { it.first }.thenBy { depthRoleRank(it.second) },
                    ),
        )
    }
}
