package com.cdhunter.pro.core.util

/**
 * Retry policy for transient network errors. Permanent failures are never
 * retried; a temporary failure never removes an IP from future scans
 * (spec section 8).
 */
class RetryPolicy(
    private val maxAttempts: Int = 2,
    private val backoffBaseMs: Long = 150,
) {

    init {
        require(maxAttempts in 1..10)
    }

    /** Executes [block], retrying only on transient failures, with linear backoff. */
    suspend fun <T> run(block: suspend (attempt: Int) -> T): T {
        var lastError: Throwable? = null
        for (attempt in 1..maxAttempts) {
            try {
                return block(attempt)
            } catch (t: Throwable) {
                lastError = t
                if (attempt == maxAttempts || !isTransient(t)) throw t
                kotlinx.coroutines.delay(backoffBaseMs * attempt)
            }
        }
        throw lastError ?: IllegalStateException("retry failed")
    }

    companion object {
        /** Errors worth retrying: timeouts and soft failures, not refusals/cert errors. */
        fun isTransient(t: Throwable): Boolean = when (t) {
            is java.net.SocketTimeoutException -> true
            is java.net.ConnectException -> t.message?.contains("timeout", ignoreCase = true) == true
            is java.io.IOException -> t.message?.contains("ECONNRESET", ignoreCase = true) == true
            else -> false
        }

        /** Classification hint used by the engine for retry decisions. */
        fun shouldRetry(category: com.cdhunter.pro.core.model.ErrorCategory): Boolean =
            when (category) {
                com.cdhunter.pro.core.model.ErrorCategory.TCP_TIMEOUT,
                com.cdhunter.pro.core.model.ErrorCategory.HTTP_TIMEOUT,
                com.cdhunter.pro.core.model.ErrorCategory.NETWORK_CHANGED,
                -> true
                else -> false
            }
    }
}
