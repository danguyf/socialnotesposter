package com.fivesided.socialnotesposter

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DraftDao {
    @Query("SELECT * FROM drafts ORDER BY id DESC")
    fun getAllDrafts(): Flow<List<NoteDraft>>

    @Insert
    suspend fun insert(draft: NoteDraft)

    @Delete
    suspend fun delete(draft: NoteDraft)
}
