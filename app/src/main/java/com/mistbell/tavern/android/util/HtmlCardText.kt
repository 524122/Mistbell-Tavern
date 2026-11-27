package com.mistbell.tavern.android.util

/**
 * 卡面文本的「HTML 界面片段」判定（零依赖纯函数，P3 导入报告口径，docs/UI_MODE.md §5）。
 *
 * 判定集合与楼层渲染入口 `ui.chat.htmlui.HtmlCardDetector.containsHtml` 一致——该函数已改为
 * 委托这里。下沉到 util 是为了让导入判定（util / data 不得依赖 ui）与楼层拆分共用同一份口径，
 * 避免两处正则漂移；委托方向是 ui→util，不产生反向依赖。
 *
 * 四种命中形态（对齐酒馆助手的渲染入口）：
 * 1. ```html 围栏块（围栏内是完整 HTML 文档）
 * 2. 整段以 <!DOCTYPE html 或 <html 开头（卡级正则替换出的裸 HTML）
 * 3. 行内 HTML：<style> 块或带 class 属性的 span/div（心声魂音/对话玉简类卡片常用）
 * 4. 行内表格 <table>
 */
object HtmlCardText {
    /** 整段裸 HTML 前缀：<!DOCTYPE html 或 <html（供楼层拆分复用同一正则） */
    internal val BARE_HTML_PREFIX = Regex("""^\s*(<!DOCTYPE\s+html|<html[\s>])""", RegexOption.IGNORE_CASE)

    /** 行内 HTML 特征：<style> 块或带 class 属性的 span/div（供楼层拆分复用同一正则） */
    internal val NARRATIVE_HTML = Regex("""<style\b|<(?:span|div)\b[^>]*class="""", RegexOption.IGNORE_CASE)

    /** 行内表格（供楼层拆分复用同一正则） */
    internal val TABLE_HTML = Regex("""<table\b""", RegexOption.IGNORE_CASE)

    /** 文本是否包含可渲染的 HTML 界面片段（导入报告与楼层预检共用） */
    fun containsHtml(content: String): Boolean =
        content.contains("```html", ignoreCase = true) ||
            BARE_HTML_PREFIX.containsMatchIn(content) ||
            NARRATIVE_HTML.containsMatchIn(content) ||
            TABLE_HTML.containsMatchIn(content)
}
