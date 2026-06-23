package com.mistbell.tavern.android.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ChangelogResponse(
    val versions: List<VersionInfo>
)

@Serializable
data class VersionInfo(
    val version: String,          // "0.2.0"
    val versionCode: Int,          // 2
    val releaseDate: String,       // "2026-06-22"
    val changes: List<ChangeItem>
)

@Serializable
data class ChangeItem(
    val type: String,              // "feature", "fix", "improvement", "chore"
    val description: String        // "引号高亮显示功能"
)
