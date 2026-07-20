package com.example

import android.app.Application
import com.example.data.ClipDatabase
import com.example.data.ClipRepository

class SereneClipApp : Application() {
    val database by lazy { ClipDatabase.getDatabase(this) }
    val repository by lazy { ClipRepository(database.clipDao()) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: SereneClipApp
            private set
    }
}
