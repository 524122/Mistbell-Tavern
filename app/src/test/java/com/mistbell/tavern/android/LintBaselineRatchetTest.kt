package com.mistbell.tavern.android

import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * P1「基线棘轮」（docs/ROADMAP.md「P1 立规矩与冻结」第 3 项）：
 * detekt 与 ktlint 的 baseline 条目数不得超过 config/lint-baseline.limits 里的上限。
 *
 * 棘轮语义与 ArchitectureRulesTest 一致：超过上限失败；低于上限同样失败，
 * 提示把上限下调到当前值（变好必须锁住）。上限文件的调整没有自动模式，必须手改并提交。
 */
class LintBaselineRatchetTest {
    @Test
    fun `detekt 基线条目数不超过上限`() {
        check("detekt", "<ID>")
    }

    @Test
    fun `ktlint 基线条目数不超过上限`() {
        check("ktlint", "<error ")
    }

    private fun check(
        tool: String,
        marker: String,
    ) {
        val root = ArchitectureScan.repoRoot()
        val limitsFile = File(root, LIMITS_PATH)
        val limit =
            readLimits(limitsFile)[tool]
                ?: error("上限文件 $LIMITS_PATH 缺少 $tool 条目，请补上（格式：$tool=<条目数>）")
        val baselineFile = File(root, baselinePath(tool))
        if (!baselineFile.isFile) fail("$tool 基线文件不存在：${baselineFile.path}")
        val current = countOccurrences(baselineFile.readText(), marker)
        when {
            current > limit ->
                fail(
                    "$tool 基线条目数 $current 超过上限 $limit：baseline 里新增了条目。" +
                        "新代码必须零告警，请修复告警本身，不要扩基线",
                )
            current < limit ->
                fail(
                    "$tool 基线条目数 $current 低于上限 $limit：基线变干净了，请把 $LIMITS_PATH 里 " +
                        "$tool 的上限下调为 $current 并一并提交",
                )
        }
    }

    /** 逐级向上定位由 [ArchitectureScan.repoRoot] 承担；这里只做解析：`#` 开头为注释，`key=值` */
    private fun readLimits(file: File): Map<String, Int> {
        if (!file.isFile) fail("上限文件不存在：${file.path}")
        val parsed = linkedMapOf<String, Int>()
        for (line in file.readLines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val key = trimmed.substringBefore('=').trim()
            val value =
                trimmed.substringAfter('=').trim().toIntOrNull()
                    ?: error("上限文件 ${file.path} 里「$key」一行不是「key=整数」：$trimmed")
            parsed[key] = value
        }
        return parsed
    }

    private fun baselinePath(tool: String): String =
        if (tool == "detekt") "config/detekt/baseline.xml" else "app/config/ktlint/baseline.xml"

    private fun countOccurrences(
        text: String,
        marker: String,
    ): Int = text.split(marker).size - 1

    private companion object {
        const val LIMITS_PATH = "config/lint-baseline.limits"
    }
}
