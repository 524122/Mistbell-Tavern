package com.mistbell.tavern.android.render.builtin

import com.mistbell.tavern.android.render.model.TemplateDocument
import com.mistbell.tavern.android.render.parse.TemplateParser

/**
 * 内置默认模板 v1（设计文档 §4.5）。
 *
 * **v1 冻结即事实规范**：templateVersion = 1 即 v0 模板语言的事实规范与回归基线——主题语言
 * 演进 = 冻结新版本号 + 引擎同时支持新旧版本，不做不兼容就地改。manifest.templateVersion
 * 与 [TEMPLATE_VERSION] 不匹配的包在导入/启用双闸被拒。
 *
 * 模板极简：`.msg` 承载基线排版（见 StyleResolver.DEFAULT_STYLESHEET），两个槽位按默认顺序
 * 承接消息分段——narrative 承接 Markdown 叙述段，panels 承接 HTML 卡片段（slot 默认块级全宽）。
 */
object DefaultTemplate {
    /** 默认模板版本（v0 冻结值） */
    const val TEMPLATE_VERSION = 1

    /** 默认模板 HTML（Kotlin 常量字符串；默认排版在默认样式表，不在模板里写样式） */
    const val HTML = "<div class=\"msg\"><slot name=\"narrative\"></slot><slot name=\"panels\"></slot></div>"

    /** 解析默认模板（容错语义与主题模板一致；随引擎发布，解析应零 ERROR 诊断） */
    fun document(): TemplateDocument = TemplateParser.parse(HTML, TEMPLATE_VERSION)
}
