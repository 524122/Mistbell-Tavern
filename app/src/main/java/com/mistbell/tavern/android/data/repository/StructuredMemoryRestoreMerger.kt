package com.mistbell.tavern.android.data.repository

import android.util.Log
import com.mistbell.tavern.android.data.local.AppDatabase
import com.mistbell.tavern.android.data.local.entity.StructuredMemoryEntity

/**
 * 结构化记忆的备份恢复合并器（可注入 [db]，行为与 BackupManager 内联实现逐行等价）。
 *
 * 钉住的产品契约（2026-10 会话内长期记忆改造）：
 * - **归属校验** [isAttachableMemory]：sessionId 非 blank、合并后的 sessions 表存在该会话、
 *   且 session.ownerId == memory.ownerId；否则丢弃并计数（[MergeResult.unattachable]）。
 *   校验针对"合并后的 sessions 表"：本地已有会话同样算合法归属（合并且跳过已存在主键是
 *   本备份的既定语义）。
 * - **两阶段冲突插入**（顺序无关正确性）：先落全部显式 id 的 fresh 记录，再为冲突记录分配
 *   自增 id。若"冲突项立即 id=0 插入、fresh 末尾统一 REPLACE"，JSON 顺序 [1(C), 2(B)]
 *   下 C 的自增 id 会被 B 的显式 id REPLACE 覆盖（记忆丢失）；两阶段保证冲突重插发生在所有
 *   显式 id 落库之后，自增 id 必然大于一切现存 id，无碰撞。
 * - **签名查重**：同 id 同内容跳过；同内容已存在（换过 id 的重复恢复）按重复跳过
 *   （[MergeResult.duplicated]）——重复恢复同一备份不制造副本。
 */
internal class StructuredMemoryRestoreMerger(
    private val db: AppDatabase,
) {
    /** 单次合并结果计数（added/duplicated/unattachable 与 BackupSummary 口径一致） */
    data class MergeResult(
        /** 落库条数 = fresh + 冲突重插 */
        val added: Int,
        /** 跳过条数：同 id 同内容 + 同内容签名已存在 */
        val duplicated: Int,
        /** 丢弃条数：sessionId 空 / 会话不存在 / ownerId 归属不一致 */
        val unattachable: Int,
        /** 显式 id 直接落库条数 */
        val freshCount: Int,
        /** 冲突后以新自增 id 重插条数 */
        val reinsertedCount: Int,
    )

    suspend fun merge(restored: List<StructuredMemoryEntity>): MergeResult {
        val (attachableMemories, unattachableMemories) =
            restored.partition { isAttachableMemory(it) }
        if (unattachableMemories.isNotEmpty()) {
            Log.i(
                TAG,
                "恢复：丢弃 ${unattachableMemories.size} 条归属无效的结构化记忆" +
                    "（sessionId 为空、会话不存在或归属不一致）",
            )
        }

        // 同 ID 冲突（跨设备自增 id 撞车）：内容签名一致 → 跳过；不同 → 以新自增 id 重插。
        // 绝不静默跳过当成功——那会让本机旧内容冒充备份内容继续存活，备份恢复名存实亡。
        val existingMemories = db.structuredMemoryDao().getAllOnce()
        val existingById = existingMemories.associateBy { it.id }
        val existingSignatures =
            existingMemories.mapTo(HashSet()) { signature(it) }
        val freshMemories = mutableListOf<StructuredMemoryEntity>()
        val conflictMemories = mutableListOf<StructuredMemoryEntity>()
        var duplicated = 0
        attachableMemories.forEach { memory ->
            val sig = signature(memory)
            val existing = existingById[memory.id]
            when {
                existing != null && signature(existing) == sig -> duplicated++
                sig in existingSignatures -> duplicated++
                existing == null -> {
                    freshMemories.add(memory)
                    existingSignatures.add(sig)
                }
                else -> conflictMemories.add(memory)
            }
        }
        freshMemories.forEach { db.structuredMemoryDao().insert(it) }
        var reinserted = 0
        conflictMemories.forEach { memory ->
            Log.w(TAG, "结构化记忆 id=${memory.id} 冲突且内容不同，以新自增 id 重新插入")
            db.structuredMemoryDao().insert(memory.copy(id = 0))
            existingSignatures.add(signature(memory))
            reinserted++
        }
        return MergeResult(
            added = freshMemories.size + reinserted,
            duplicated = duplicated,
            unattachable = unattachableMemories.size,
            freshCount = freshMemories.size,
            reinsertedCount = reinserted,
        )
    }

    /**
     * 归属校验（mergeTables 与 shouldRebuildIndex 同源——两边判定不一致就会出现
     * "merge 丢弃了、索引判定却以为一致"的漏洞，备份自带索引会把丢弃记忆带回来）：
     * sessionId 非 blank、（合并后的）sessions 表存在该会话、且 ownerId 一致。
     */
    suspend fun isAttachableMemory(memory: StructuredMemoryEntity): Boolean {
        val session = memory.sessionId?.takeIf { it.isNotBlank() }?.let { db.sessionDao().getById(it) }
        return session != null && session.ownerId == memory.ownerId
    }

    companion object {
        private const val TAG = "BackupManager"

        /** 内容签名：id 之外的全部业务字段（与合并去重口径一致） */
        fun signature(memory: StructuredMemoryEntity): String =
            listOf(
                memory.ownerId,
                memory.characterId,
                memory.sessionId,
                memory.memoryType,
                memory.title,
                memory.content,
                memory.structuredData,
                memory.importance,
                memory.tags,
                memory.keywords,
            ).joinToString("\u0000")
    }
}
