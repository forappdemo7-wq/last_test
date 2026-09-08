package com.yourpackage.network

/**
 * Sealed class representing the result of a network operation.
 * Provides type-safe handling of success, error, and loading states.
 */
sealed class ApiResponse<out T> {
    /**
     * Represents a successful network operation with data.
     */
    data class Success<T>(val data: T) : ApiResponse<T>()

    /**
     * Represents a failed network operation with an error message.
     */
    data class Error(val message: String, val code: Int? = null) : ApiResponse<Nothing>()

    /**
     * Represents a network operation that is currently in progress.
     */
    object Loading : ApiResponse<Nothing>()

    /**
     * Convenience method to check if the response is successful.
     */
    fun isSuccess(): Boolean = this is Success

    /**
     * Convenience method to check if the response is an error.
     */
    fun isError(): Boolean = this is Error

    /**
     * Convenience method to check if the response is loading.
     */
    fun isLoading(): Boolean = this is Loading

    /**
     * Get the data if successful, or null otherwise.
     */
    fun getDataOrNull(): T? = when (this) {
        is Success -> this.data
        else -> null
    }

    /**
     * Get the error message if it's an error, or null otherwise.
     */
    fun getErrorMessageOrNull(): String? = when (this) {
        is Error -> this.message
        else -> null
    }

    /**
     * Suspend function to handle the response.
     * Usage: 
     * when (result) {
     *   is ApiResponse.Success -> handleSuccess(result.data)
     *   is ApiResponse.Error -> handleError(result.message)
     *   is ApiResponse.Loading -> showLoading()
     * }
     */
    suspend fun <R> fold(
        onSuccess: suspend (T) -> R,
        onError: suspend (String) -> R,
        onLoading: suspend () -> R = { onError("Loading...") }
    ): R {
        return when (this) {
            is Success -> onSuccess(data)
            is Error -> onError(message)
            is Loading -> onLoading()
        }
    }
}

/**
 * Extension function to map a successful response to a different type.
 */
fun <T, R> ApiResponse<T>.map(transform: (T) -> R): ApiResponse<R> {
    return when (this) {
        is ApiResponse.Success -> ApiResponse.Success(transform(this.data))
        is ApiResponse.Error -> ApiResponse.Error(this.message, this.code)
        is ApiResponse.Loading -> ApiResponse.Loading
    }
}

/**
 * Extension function to get the data or throw an exception if it's an error.
 */
fun <T> ApiResponse<T>.getOrThrow(): T {
    return when (this) {
        is ApiResponse.Success -> this.data
        is ApiResponse.Error -> throw Exception(this.message)
        is ApiResponse.Loading -> throw IllegalStateException("Response is still loading")
    }
}