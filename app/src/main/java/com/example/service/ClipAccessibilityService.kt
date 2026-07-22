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
        checkAndSaveClipboard()
        if (event == null) return
        try {
            val eventTexts = event.text
            if (!eventTexts.isNullOrEmpty()) {
                for (cs in eventTexts) {
                    val str = cs?.toString()?.trim()
                    if (!str.isNullOrBlank() && str.length > 1) {
                        saveTextIfNew(str)
                    }
                }
            }

            val sourceNode = event.source
            if (sourceNode != null) {
                val nodeText = sourceNode.text?.toString()
                if (!nodeText.isNullOrBlank()) {
                    val start = sourceNode.textSelectionStart
                    val end = sourceNode.textSelectionEnd
                    if (start >= 0 && end > start && end <= nodeText.length) {
                        val selected = nodeText.substring(start, end).trim()
                        if (selected.isNotBlank() && selected.length > 1) {
                            saveTextIfNew(selected)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
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

    private fun saveTextIfNew(text: String) {
        if (text.isBlank()) return
        if (DeletedClipsManager.isDeleted(text)) return
        DeletedClipsManager.clearAll()
        serviceScope.launch(Dispatchers.IO) {
            SereneClipApp.instance.repository.saveClip(text)
        }
    }

    private fun checkAndSaveClipboard() {
        try {
            if (!::clipboardManager.isInitialized) {
                clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            }
            if (clipboardManager.hasPrimaryClip()) {
                val clipData = clipboardManager.primaryClip
                if (clipData != null && clipData.itemCount > 0) {
                    val text = clipData.getItemAt(0).coerceToText(this@ClipAccessibilityService)?.toString()?.trim()
                    if (!text.isNullOrBlank()) {
                        saveTextIfNew(text)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
