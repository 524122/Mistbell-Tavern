package com.mistbell.tavern.android.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Entity(tableName = "settings")
@Serializable
data class SettingsEntity(
    @PrimaryKey val key: String,
    val value: String,
)
