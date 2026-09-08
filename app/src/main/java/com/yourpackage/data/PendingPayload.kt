package com.yourpackage.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room Entity representing a pending payload that failed to send.
 * Stored locally in encrypted SQLite and retried later with exponential backoff.
 */
@Entity(tableName = "pending_payloads")
data class PendingPayload(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    val jsonData: String,          // The ExfilData object serialized to JSON
    
    val attempts: Int = 0,         // Number of times we've tried to send this
    
    val nextRetry: Long,           // Timestamp (millis) when we should retry
    
    val createdAt: Long = System.currentTimeMillis() // When this was first created
) {
    /**
     * Increment attempts and update next retry time using exponential backoff.
     * Backoff: 1min, 2min, 4min, 8min, 16min, 32min, 1hr, 2hr, 4hr, 8hr, 16hr, 1day, 2day, 4day, 7day (max)
     */
    fun getNextRetryDelay(): Long {
        // Cap at 7 days (604800000 ms)
        val baseDelay = 60_000L // 1 minute
        val maxDelay = 604_800_000L // 7 days
        val delay = baseDelay * (1L shl attempts) // Exponential: 1min * 2^attempts
        return if (delay > maxDelay) maxDelay else delay
    }

    fun withIncrementedAttempts(): PendingPayload {
        val newAttempts = attempts + 1
        val delay = getNextRetryDelay()
        return this.copy(
            attempts = newAttempts,
            nextRetry = System.currentTimeMillis() + delay
        )
    }
}