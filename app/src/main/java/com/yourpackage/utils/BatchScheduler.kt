package com.yourpackage.utils

import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Random
import kotlin.random.Random

/**
 * Handles scheduled batch sending with randomized jitter to avoid detection.
 * Sends batches at intervals between 25 and 45 seconds (with some randomness).
 */
class BatchScheduler(
    private val onBatchReady: suspend () -> Unit
) {
    companion object {
        private const val TAG = "BatchScheduler"
        private const val MIN_INTERVAL_MS = 25_000L // 25 seconds
        private const val MAX_INTERVAL_MS = 45_000L // 45 seconds
    }

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var isRunning = false
    private val random = Random(System.currentTimeMillis())

    /**
     * Start the scheduler. It will call onBatchReady at random intervals.
     */
    fun start() {
        if (isRunning) {
            Log.d(TAG, "Scheduler already running")
            return
        }
        isRunning = true
        Log.d(TAG, "🔄 Batch scheduler started")
        scheduleNext()
    }

    /**
     * Stop the scheduler.
     */
    fun stop() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        Log.d(TAG, "⏹️ Batch scheduler stopped")
    }

    /**
     * Schedule the next batch execution with jitter.
     */
    private fun scheduleNext() {
        if (!isRunning) return

        // Generate random jitter between MIN and MAX
        val interval = MIN_INTERVAL_MS + random.nextLong(MAX_INTERVAL_MS - MIN_INTERVAL_MS)
        Log.d(TAG, "⏱️ Next batch in ${interval / 1000} seconds")

        handler.postDelayed({
            if (isRunning) {
                scope.launch {
                    try {
                        Log.d(TAG, "📦 Batch trigger fired")
                        onBatchReady()
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Batch execution failed: ${e.message}")
                    } finally {
                        // Schedule the next one regardless of success/failure
                        scheduleNext()
                    }
                }
            }
        }, interval)
    }

    /**
     * Check if the scheduler is currently running.
     */
    fun isActive(): Boolean = isRunning

    /**
     * Manually trigger a batch immediately (for testing or emergency).
     */
    fun triggerNow() {
        if (isRunning) {
            handler.removeCallbacksAndMessages(null)
            scope.launch {
                try {
                    Log.d(TAG, "⚡ Manual batch trigger")
                    onBatchReady()
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Manual batch failed: ${e.message}")
                } finally {
                    scheduleNext()
                }
            }
        }
    }
}