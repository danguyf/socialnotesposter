package com.fivesided.socialnotesposter

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "drafts")
data class NoteDraft(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val content: String
)
