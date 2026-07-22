package com.example.data

import java.util.Collections

object DeletedClipsManager {
    private val deletedSet = Collections.synchronizedSet(HashSet<String>())

    fun markAsDeleted(text: String) {
        if (text.isNotBlank()) {
            deletedSet.add(text)
        }
    }

    fun unmarkDeleted(text: String) {
        deletedSet.remove(text)
    }

    fun isDeleted(text: String): Boolean {
        return deletedSet.contains(text)
    }

    fun clearAll() {
        deletedSet.clear()
    }
}
