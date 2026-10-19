package com.mistbell.tavern.android.data.vector

/**
 * 嵌入服务接口
 *
 * 用于将文本转换为向量表示
 * 移植自后端 EmbeddingService.java
 */
interface EmbeddingService {
    /**
     * 将文本转换为向量
     *
     * @param text 输入文本
     * @return 向量数组（通常是 1536 维）
     */
    suspend fun embed(text: String): FloatArray

    /**
     * 批量转换文本为向量
     *
     * @param texts 文本列表
     * @return 向量列表
     */
    suspend fun embedBatch(texts: List<String>): List<FloatArray>

    /**
     * 获取向量维度
     */
    fun getDimension(): Int

    /**
     * 向量身份指纹（provider:model:dimension）。
     *
     * 不同 embedding 配置产出的向量处于不同向量空间，余弦相似度跨空间没有意义，
     * 且维度不同会直接让相似度计算抛错。指纹随每条向量落进 metadata，检索时按当前
     * 指纹过滤——换模型后旧向量自动退出召回，而不是混搜出一堆无意义结果。
     *
     * 实现方应显式覆写（默认值只是给测试替身兜底）。
     */
    val fingerprint: String
        get() = "${this::class.java.simpleName}:${getDimension()}"
}
