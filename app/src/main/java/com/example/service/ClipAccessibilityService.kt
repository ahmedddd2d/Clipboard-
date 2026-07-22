package com.example.service

import android.accessibilityservice.AccessibilityService
import android.content.ClipboardManager
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.annotation.Keep
import com.example.SereneClipApp
import com.example.data.Clip
import com.example.data.DeletedClipsManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.firstOrNull

@Keep
class ClipAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var clipboardManager: ClipboardManager

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        checkAndSaveClipboard()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        try {
            clipboardManager.addPrimaryClipChangedListener(clipboardListener)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        checkAndSaveClipboard()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED ||
            event?.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            checkAndSaveClipboard()
        }
    }

    override fun onInterrupt() {
        // Required for AccessibilityService
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (::clipboardManager.isInitialized) {
                clipboardManager.removePrimaryClipChangedListener(clipboardListener)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        serviceScope.cancel()
    }

    private fun checkAndSaveClipboard() {
        try {
            if (!::clipboardManager.isInitialized) {
                clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            }
            if (clipboardManager.hasPrimaryClip()) {
                val clipData = clipboardManager.primaryClip
                if (clipData != null && clipData.itemCount > 0) {
                    val text = clipData.getItemAt(0).text?.toString()
                    if (!text.isNullOrBlank()) {
                        if (DeletedClipsManager.isDeleted(text)) {
                            return
                        }
                        serviceScope.launch {
                            val repository = SereneClipApp.instance.repository
                            val latest = repository.allClips.firstOrNull()?.firstOrNull()
                            if (latest == null || latest.text != text) {
                                repository.insert(Clip(text = text))
                                Toast.makeText(
                                    this@ClipAccessibilityService,
                                    "New clip captured automatically",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
