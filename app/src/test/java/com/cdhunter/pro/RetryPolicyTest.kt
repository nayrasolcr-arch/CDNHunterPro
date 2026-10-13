package com.cdhunter.pro

import com.cdhunter.pro.core.model.ExportFormat
import com.cdhunter.pro.core.util.RetryPolicy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

class RetryPolicyTest {

    @Test
    fun `retries transient timeouts up to max attempts`() = runTest {
        var attempts = 0
        val policy = RetryPolicy(maxAttempts = 3, backoffBaseMs = 1)
        val result = policy.run { attempt ->
            attempts = attempt
            if (attempt < 3) throw SocketTimeoutException("timeout") else "ok"
        }
        assertEquals("ok", result)
        assertEquals(3, attempts)
    }

    @Test
    fun `does not retry permanent failures`() = runTest {
        var attempts = 0
        val policy = RetryPolicy(maxAttempts = 3, backoffBaseMs = 1)
        try {
            policy.run {
                attempts++
                throw java.net.ConnectException("connection refused")
            }
            error("must throw")
        } catch (e: java.net.ConnectException) {
            assertEquals(1, attempts)
        }
    }

    @Test
    fun `exhausted retries throw last error`() = runTest {
        var attempts = 0
        val policy = RetryPolicy(maxAttempts = 2, backoffBaseMs = 1)
        try {
            policy.run {
                attempts++
                throw SocketTimeoutException("still timing out")
            }
            error("must throw")
        } catch (_: SocketTimeoutException) {
            assertEquals(2, attempts)
        }
    }
}
