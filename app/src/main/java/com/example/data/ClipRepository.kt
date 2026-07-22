package com.example.data

import kotlinx.coroutines.flow.Flow

class ClipRepository(private val clipDao: ClipDao) {
    val allClips: Flow<List<Clip>> = clipDao.getAllClips()

    fun searchClips(query: String): Flow<List<Clip>> {
        return clipDao.searchClips(query)
    }

    suspend fun insert(clip: Clip): Long {
        return clipDao.insertClip(clip)
    }

    suspend fun update(clip: Clip) {
        clipDao.updateClip(clip)
    }

    suspend fun deleteById(id: Long) {
        clipDao.deleteClipById(id)
    }

    suspend fun deleteOldUnpinnedClips(cutoff: Long) {
        clipDao.deleteOldUnpinnedClips(cutoff)
    }

    suspend fun getClipByText(text: String): Clip? {
        return clipDao.getClipByText(text)
    }

    suspend fun saveClip(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        val existing = clipDao.getClipByText(trimmed)
        if (existing != null) {
            clipDao.updateClip(existing.copy(timestamp = System.currentTimeMillis()))
        } else {
            clipDao.insertClip(Clip(text = trimmed, timestamp = System.currentTimeMillis()))
        }
    }

    suspend fun getLatestClipByTimestamp(): Clip? {
        return clipDao.getLatestClipByTimestamp()
    }

    suspend fun deleteAll() {
        clipDao.deleteAllClips()
    }
}
