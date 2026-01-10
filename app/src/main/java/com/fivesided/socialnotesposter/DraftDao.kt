package com.fivesided.socialnotesposter

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DraftDao {
    @Query("SELECT * FROM drafts ORDER BY id DESC")
    fun getAllDrafts(): Flow<List<NoteDraft>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(draft: NoteDraft)

    @Update
    suspend fun update(draft: NoteDraft)

    @Delete
    suspend fun delete(draft: NoteDraft)
}
