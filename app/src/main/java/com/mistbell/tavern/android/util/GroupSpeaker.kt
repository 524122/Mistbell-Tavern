package com.mistbell.tavern.android.util

import com.mistbell.tavern.android.data.api.model.GroupSpeakerResult

// ---- 群聊说话方解析（跨代理契约 5：数据层实现 + 单测，UI 层不改）----
// 结果类型 GroupSpeakerResult 定义在 data/api/model/Models.kt（与 GroupChatContext 同处）

// 名字后允许的冒号（半角/全角），命中任意一个即视为说话方前缀
private const val HALF_WIDTH_COLON = ':'
private const val FULL_WIDTH_COLON = '：'

/**
 * 群聊回复归属解析（纯函数，无 Android 依赖，可直接 JVM 单测）。
 *
 * 匹配规则：
 * - 取 content trimStart 后的开头，要求「名字」+「0..n 空白」+「:」或「：」的结构；
 *   即名字与冒号之间允许任意空白（"Bob : hi" 也命中，跨代理契约 5 容错）；
 * - 名字与 speakerNames 的 value【trim 后】完全相等（前缀匹配，不模糊）；
 * - 多个名字同时前缀匹配时（如 "Alice" 与 "Alice Chen"，或名字本身含冒号的边界）取名字最长者优先；
 * - 命中返回 GroupSpeakerResult(对应 id, 冒号后 trimStart 的内容)；未命中返回 null。
 *   注意：名字与冒号之间的空白不参与前缀匹配——先 trimStart 名字后的余量再验证冒号，
 *   剥离内容从冒号后起算。
 *
 * 典型用途：群聊模式下模型回复形如「角色名: 发言内容」，落库前据此把助手消息
 * 归属到对应 NPC（character_id 写为说话者 id、内容剥前缀）。
 */
internal fun parseGroupSpeaker(
    content: String,
    speakerNames: Map<String, String>,
): GroupSpeakerResult? {
    val head = content.trimStart()
    if (head.isEmpty() || speakerNames.isEmpty()) return null

    // 遍历全部候选名字，取「前缀匹配 + 名字后允许空白再接冒号」中名字最长者
    // （严格大于保证并列时首个候选稳定胜出）。名字与冒号之间的余量先 trimStart 再验证冒号，
    // 因此 "Bob : hi"（名字后带空白）与 "Bob: hi" 同样命中，且不会误吞正文首个字符。
    var bestId = ""
    var bestName = ""
    var bestAfterName = ""
    for ((id, rawName) in speakerNames) {
        val name = rawName.trim()
        if (name.isEmpty() || !head.startsWith(name)) continue
        // 名字后的余量先 trimStart（契约 5 空白容错），再验证紧随的是否为冒号
        val afterName = head.substring(name.length).trimStart()
        if (afterName.isEmpty()) continue
        val colon = afterName.first()
        if (colon != HALF_WIDTH_COLON && colon != FULL_WIDTH_COLON) continue
        if (name.length > bestName.length) {
            bestId = id
            bestName = name
            bestAfterName = afterName
        }
    }
    if (bestName.isEmpty()) return null

    // 冒号后可以没有任何内容（内容为空的发言仍视为命中）；
    // 剥离内容从冒号后起算（bestAfterName 已去掉名字与冒号之间的空白余量）
    val stripped = bestAfterName.substring(1).trimStart()
    return GroupSpeakerResult(speakerId = bestId, strippedContent = stripped)
}
