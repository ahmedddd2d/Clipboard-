package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ClipDao {
    @Query("SELECT * FROM clips ORDER BY isPinned DESC, timestamp DESC")
    fun getAllClips(): Flow<List<Clip>>

    @Query("SELECT * FROM clips WHERE text LIKE '%' || :query || '%' ORDER BY isPinned DESC, timestamp DESC")
    fun searchClips(query: String): Flow<List<Clip>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertClip(clip: Clip): Long

    @Update
    suspend fun updateClip(clip: Clip)

    @Query("DELETE FROM clips WHERE id = :id")
    suspend fun deleteClipById(id: Long)

    @Query("DELETE FROM clips WHERE isPinned = 0 AND timestamp < :cutoff")
    suspend fun deleteOldUnpinnedClips(cutoff: Long)

    @Query("DELETE FROM clips")
    suspend fun deleteAllClips()
}
