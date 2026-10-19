package com.mistbell.tavern.android.data.repository

import android.content.Context
import android.util.Log
import com.mistbell.tavern.android.data.local.AppDatabase
import com.mistbell.tavern.android.data.local.entity.SettingsEntity
import com.mistbell.tavern.android.data.vector.InMemoryVectorStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException

/**
 * 记忆链闸门未通过（升级清零未完成/失败）时抛出：fail-closed，调用方必须显式决定
 * 是拒绝（写入/加载/备份路径原样上抛）还是降级为空召回（召回路径捕获后返回空结果）。
 */
class MemoryResetPendingException : IllegalStateException("会话内记忆升级清零未完成，记忆链拒绝访问")

/**
 * 一次性升级清零（docs/SESSION_SCOPED_MEMORY.md 第9章）：会话内长期记忆成为唯一形态，
 * 升级时把旧的全局/角色级记忆痕迹整体清掉——structured_memory 全表、向量索引文件、
 * 待抽取缓冲（pending + inflight 批次）。聊天消息/角色/世界书一律保留。
 *
 * 语义要点：
 * - **终身一次**：完成标记存在 SharedPreferences（与版本号无关，升级后只清这一次），
 *   成功才写标记；任何一步失败都不写，下次启动重试，绝不允许"每次启动都清"
 *   （那会把升级后攒的新记忆一起清掉）。
 * - **先于服务启用**：向量索引、待抽取缓冲、StructuredMemoryRepository、PromptBuilder 召回、
 *   备份导出/恢复都挂记忆链闸门（见 AppContainer.sessionMemoryResetGate），首次访问前
 *   必须等本清零完成，否则清掉的文件/记录会被立刻读回来。
 * - **失败可重试**：全程兜异常、记 warning、返回 false，绝不抛崩应用；失败不缓存，
 *   下一次闸门调用会重新尝试（Mutex 只挡并发，不挡重试）。
 * - **可提示**：成功后往 settings 表写 "session_memory_reset_notice_pending"=1，
 *   供应用内一次性提示读取（提示 UI 由别的任务实现）。
 */
@Suppress("TooGenericExceptionCaught")
object SessionMemoryReset {
    private const val TAG = "SessionMemoryReset"

    /** 与 TavernApplication / AppContainer 同一个 SharedPreferences，禁止另起文件名 */
    private const val PREFS_NAME = "tavern_settings"

    /** 完成标记：值为 "1" 即终身完成（与版本号无关，见类注释） */
    private const val KEY_DONE = "session_memory_reset_v1_done"

    /** 一次性提示占位：提示 UI 读取后自行清除 */
    private const val KEY_NOTICE_PENDING = "session_memory_reset_notice_pending"

    /** 用户已确认提示：补通知逻辑只在 seen != "1" 时重新挂 pending */
    private const val KEY_NOTICE_SEEN = "session_memory_reset_notice_seen"

    /**
     * 进程内并发防护：多个调用方同时进入时只执行一次，后来者等待首个完成并共享结果。
     * [completed] 只缓存 true——失败不缓存，下次调用自然重试清零。
     */
    private val mutex = Mutex()
    private var completed: Boolean? = null

    /**
     * 需要则执行一次性清零。
     *
     * @return 执行完（或此前已完成）是否处于完成态；任何一步失败返回 false（下次调用重试）
     */
    suspend fun runIfNeeded(
        context: Context,
        db: AppDatabase,
    ): Boolean {
        mutex.withLock {
            completed?.let { return it }
            val done = doReset(context, db)
            // 失败不缓存：下次调用自然重试清零（Mutex 只挡并发，不挡重试）
            completed = true.takeIf { done }
            return done
        }
    }

    /** DONE 短路（补通知）或执行清零；@return 是否处于完成态 */
    private suspend fun doReset(
        context: Context,
        db: AppDatabase,
    ): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val alreadyDone = prefs.getString(KEY_DONE, null) == "1"
        if (alreadyDone) {
            rearmNoticeIfNeeded(db)
        }
        return alreadyDone || performCleanup(context, db, prefs)
    }

    /**
     * 可恢复补通知：DONE 已落盘即清零终身完成，但提示标记可能因上次 upsert 失败而缺失。
     * 用户未确认过（seen != "1"）就重新挂上 pending——"必须告知"的契约不能因一次提示
     * 写失败而永久漏提示。只补通知，绝不重做清理。
     */
    private suspend fun rearmNoticeIfNeeded(db: AppDatabase) {
        runCatching {
            if (db.settingsDao().getValue(KEY_NOTICE_SEEN) != "1") {
                db.settingsDao().upsert(SettingsEntity(KEY_NOTICE_PENDING, "1"))
            }
        }.onFailure { Log.w(TAG, "补挂升级提示标记失败：${it.message}", it) }
    }

    /** 执行清零步骤；@return 全部步骤成功且完成标记已同步落盘 */
    private suspend fun performCleanup(
        context: Context,
        db: AppDatabase,
        prefs: android.content.SharedPreferences,
    ): Boolean {
        val ok =
            runCatching {
                db.structuredMemoryDao().deleteAll()
                deleteWithTemp(File(context.filesDir, InMemoryVectorStore.FILE_NAME))
                deleteWithTemp(File(context.filesDir, PendingExtractionStore.FILE_NAME))
            }.onFailure {
                Log.w(TAG, "会话内记忆升级清零失败，下次启动重试：${it.message}", it)
            }.isSuccess

        // 只有全部步骤成功才写完成标记——标记是先决条件的"已清"凭证，
        // 提前写会让下次启动跳过清零，旧记忆残留变成永久状态。
        // 用 commit() 同步落盘并检查返回值：apply() 是异步的，进程可能死在落盘前，
        // 留下"标记没写上但文件已删"的半完成态；commit 失败 = 未完成，闸门继续关闭。
        val committed =
            ok &&
                runCatching { prefs.edit().putString(KEY_DONE, "1").commit() }
                    .onFailure { Log.w(TAG, "完成标记落盘异常：${it.message}", it) }
                    .getOrDefault(false)
        if (ok && !committed) {
            // SharedPreferences.commit 先更新进程内存再等磁盘写入：返回 false 时内存里
            // 可能已是 "1"，下次重试的 getString 会把它误当就绪凭证。尽力撤销内存态，
            // 保证"本进程未成功持久化的 DONE"永远不会放行闸门。
            runCatching { prefs.edit().remove(KEY_DONE).commit() }
                .onFailure { Log.w(TAG, "完成标记内存态撤销失败：${it.message}", it) }
            Log.w(TAG, "完成标记落盘失败，清零视为未完成，闸门保持关闭")
        }

        if (committed) {
            // 一次性提示只在"清零全部成功且完成标记已落盘"之后才挂：
            // 用户看到提示 = 确实已清；commit 失败的场景绝不会提前报"已清空"。
            // 提示标记写失败不影响清零完成态——DONE 短路分支会按 seen 键补挂（见上）。
            runCatching { db.settingsDao().upsert(SettingsEntity(KEY_NOTICE_PENDING, "1")) }
                .onFailure { Log.w(TAG, "升级提示标记写入失败（DONE 短路时会补挂）：${it.message}", it) }
            Log.i(TAG, "会话内记忆升级清零完成：旧记忆/向量索引/待抽取缓冲已清理")
        }
        return committed
    }

    /**
     * 删除目标文件及其 .tmp 残留（与两个 Store 的 ".tmp + rename" 写入方式同源，禁止手写字面量）。
     *
     * 以"文件不存在或删除成功"为成功条件：delete() 返回 false 且文件仍存在（被占用/权限）
     * 视为该步失败——旧文件残留意味着清零没完成，必须让整体失败、不写 DONE、下次重试，
     * 绝不能静默留下"标记已写、旧文件还在"的分裂状态。
     */
    private fun deleteWithTemp(file: File) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        if (file.exists() && !file.delete()) {
            throw IOException("无法删除旧记忆文件 ${file.name}")
        }
        if (temp.exists() && !temp.delete()) {
            throw IOException("无法删除旧记忆文件临时残留 ${temp.name}")
        }
    }
}
