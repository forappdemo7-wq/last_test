package com.yourpackage.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Delete

/**
 * Room DAO (Data Access Object) for PendingPayload entities.
 * Handles all database operations for the offline retry queue.
 */
@Dao
interface PendingPayloadDao {

    /**
     * Insert a single pending payload into the database.
     */
    @Insert
    suspend fun insert(pendingPayload: PendingPayload): Long

    /**
     * Insert multiple pending payloads at once (batch insert).
     */
    @Insert
    suspend fun insertAll(vararg pendingPayloads: PendingPayload): List<Long>

    /**
     * Get all pending payloads that are ready to retry.
     * Ready means: nextRetry <= current time.
     * Ordered by nextRetry (oldest first).
     */
    @Query("SELECT * FROM pending_payloads WHERE nextRetry <= :currentTime ORDER BY nextRetry ASC")
    suspend fun getReadyToRetry(currentTime: Long): List<PendingPayload>

    /**
     * Get all pending payloads (regardless of retry time) - useful for debugging.
     */
    @Query("SELECT * FROM pending_payloads ORDER BY nextRetry ASC")
    suspend fun getAllPending(): List<PendingPayload>

    /**
     * Delete a specific pending payload (after successful send).
     */
    @Delete
    suspend fun delete(pendingPayload: PendingPayload)

    /**
     * Delete a pending payload by its ID.
     */
    @Query("DELETE FROM pending_payloads WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * Delete all pending payloads (emergency cleanup).
     */
    @Query("DELETE FROM pending_payloads")
    suspend fun deleteAll()

    /**
     * Get the total count of pending payloads in the queue.
     */
    @Query("SELECT COUNT(*) FROM pending_payloads")
    suspend fun getCount(): Int

    /**
     * Update a pending payload (e.g., after incrementing attempts).
     */
    @Query("UPDATE pending_payloads SET attempts = :attempts, nextRetry = :nextRetry WHERE id = :id")
    suspend fun updateRetryInfo(id: Long, attempts: Int, nextRetry: Long)
}