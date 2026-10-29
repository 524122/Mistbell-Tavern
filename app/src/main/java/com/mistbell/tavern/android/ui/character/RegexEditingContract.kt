package com.mistbell.tavern.android.ui.character

import com.mistbell.tavern.android.data.regex.RegexPreviewRequest
import com.mistbell.tavern.android.data.regex.RegexRuleDraft

/**
 * 正则编辑组件与其宿主 ViewModel 的契约：卡级（CharacterRegexViewModel）与
 * 全局（GlobalRegexViewModel）共用同一套编辑器/列表/预览组件，仅持久化目标不同。
 */
interface RegexEditingContract {
    fun selectRule(id: Int)

    fun addRule(sourceId: Int? = null)

    fun deleteRule(id: Int)

    fun moveRule(
        id: Int,
        offset: Int,
    )

    fun updateRule(
        id: Int,
        transform: RegexRuleDraft.() -> RegexRuleDraft,
    )

    fun updatePreview(transform: RegexPreviewRequest.() -> RegexPreviewRequest)

    fun save()
}
