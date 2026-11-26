package com.mistbell.tavern.android

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** architecture-baseline.json 的结构（键有序、格式固定，重写时避免无意义 diff） */
@Serializable
data class ArchitectureBaseline(
    /** 口径说明与生成信息：键固定为 orderedMeta() 里那几条，便于以后读懂当时怎么数的 */
    val meta: Map<String, String>,
    /** extension → ui.* 各边的引用数上限（"extension->ui.chat.htmlui" 形式；出现新边即失败） */
    val extensionToUiEdges: Map<String, Int>,
    /** 允许的包组互赖对（无序对，字典序小者在前，`↔` 连接，排序存储） */
    val mutualPairs: List<String>,
    /** ui.* → data.local.entity 的引用数上限（import 与全限定名都计入） */
    val uiToEntityRefCount: Int,
    /** ui.* → data.local.entity 的引用涉及文件数上限 */
    val uiToEntityRefFiles: Int,
    /** TavernApplication.instance 出现次数上限 */
    val tavernInstanceCount: Int,
    /** TavernApplication.instance 涉及文件数上限 */
    val tavernInstanceFiles: Int,
)

/** architecture-baseline.json 的定位 / 读写 / 更新开关 */
object ArchitectureBaselineStore {
    const val SYS_PROP_UPDATE = "arch.baseline.update"

    private val json = Json { prettyPrint = true }
    private val serializer = ArchitectureBaseline.serializer()

    fun file(root: File): File = File(root, "app/src/test/resources/architecture-baseline.json")

    fun load(file: File): ArchitectureBaseline = json.decodeFromString(serializer, file.readText())

    fun write(
        file: File,
        baseline: ArchitectureBaseline,
    ) {
        file.parentFile.mkdirs()
        file.writeText(json.encodeToString(ArchitectureBaseline.serializer(), baseline) + "\n", Charsets.UTF_8)
    }

    /** true = 本次单测以 -ParchBaselineUpdate=true 启动：重写基线后通过 */
    val updateRequested: Boolean
        get() = System.getProperty(SYS_PROP_UPDATE) == "true"
}
