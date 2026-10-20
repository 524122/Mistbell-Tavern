package com.mistbell.tavern.android.data.vector

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 向量索引的 JSON 落盘/备份格式（`vector_store.json` 的线格式契约）。
 *
 * 从 [InMemoryVectorStore] 私有提升为模块级共享：sqlite-vec 实现（SqliteVecStore）的
 * `importIndex` 与备份导出复用同一格式——备份 zip 里的 `vector_store.json` 条目对两种
 * 存储引擎语义一致，旧备份可以直接恢复进新引擎。
 *
 * 紧凑输出（不开 prettyPrint）：512 维向量每个分量独占一行会多出约 1/3 体积，
 * 索引是机器读的，没有人会去看它。解析容忍未知字段（老版本读到新格式的扩展字段不炸）。
 */
@Serializable
internal data class VectorIndexEntry(
    val id: String,
    val vector: List<Float>,
    val metadata: Map<String, String>,
)

@Serializable
internal data class VectorIndexData(
    val vectors: List<VectorIndexEntry>,
)

private val indexJson =
    Json {
        ignoreUnknownKeys = true
    }

/** 序列化索引条目（元数据值统一 toString——线格式只有字符串映射，与历史格式一致） */
internal fun encodeVectorIndex(entries: List<VectorStore.VectorEntry>): String =
    indexJson.encodeToString(
        VectorIndexData(
            vectors =
                entries.map { entry ->
                    VectorIndexEntry(
                        id = entry.id,
                        vector = entry.vector.toList(),
                        metadata = entry.metadata.mapValues { (_, v) -> v.toString() },
                    )
                },
        ),
    )

/**
 * 解析索引内容；失败返回 null（调用方据此拒绝导入、保留现有索引——
 * 写进一个坏索引会让召回整体失效且不可逆，故先验后写）。
 */
internal fun decodeVectorIndex(content: String): List<VectorStore.VectorEntry>? =
    try {
        indexJson.decodeFromString<VectorIndexData>(content).vectors.map {
            VectorStore.VectorEntry(
                id = it.id,
                vector = it.vector.toFloatArray(),
                metadata = it.metadata,
            )
        }
    } catch (_: Exception) {
        null
    }

/** 备份恢复前的索引校验：能解析才接受 */
internal fun isValidVectorIndex(content: String): Boolean = decodeVectorIndex(content) != null
