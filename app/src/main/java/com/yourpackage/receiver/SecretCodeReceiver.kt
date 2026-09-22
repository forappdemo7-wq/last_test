package com.yourpackage.receiver

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import com.yourpackage.MainActivity

/**
 * Listens for the secret dialer code *#*#12345#*#*
 * When the user dials it, this receiver:
 *   1. Re-enables the launcher icon (unhides the app)
 *   2. Opens MainActivity so the user can manage services
 *
 * Usage:
 *   Open the Phone/Dialer app and type: *#*#12345#*#*
 *   (No need to press call – the code triggers automatically)
 */
class SecretCodeReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SecretCodeReceiver"
        private const val LAUNCHER_ALIAS = "com.yourpackage.LauncherAlias"
    }

    override fun onReceive(context: Context, intent: Intent) {
        // Verify this is the secret code action
        if (intent.action != "android.provider.Telephony.SECRET_CODE") {
            Log.d(TAG, "Ignoring non-secret-code intent: ${intent.action}")
            return
        }

        Log.d(TAG, "🔓 Secret code received – unhiding app...")

        try {
            // Re-enable the launcher alias so the icon shows up again
            val launcherAlias = ComponentName(context.packageName, LAUNCHER_ALIAS)
            context.packageManager.setComponentEnabledSetting(
                launcherAlias,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )

            Log.d(TAG, "✅ Launcher icon re-enabled")

            Toast.makeText(
                context,
                "🔓 App unhidden – icon restored",
                Toast.LENGTH_SHORT
            ).show()

            // Open MainActivity so the user can manage services
            val mainIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(mainIntent)

            Log.d(TAG, "✅ MainActivity launched")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to unhide app: ${e.message}")
            Toast.makeText(
                context,
                "Error unhiding app: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
