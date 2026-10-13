package com.cdhunter.pro.network

import com.cdhunter.pro.core.model.ErrorCategory
import com.cdhunter.pro.core.model.TcpAttempt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * TCP connectivity tester. Measures pure connect time; a successful TCP
 * connect says nothing about TLS or proxy compatibility (enforced upstream).
 */
class TcpTester {

    suspend fun attempt(
        ip: String,
        port: Int,
        timeoutMs: Int,
    ): TcpAttempt = withContext(Dispatchers.IO) {
        val socket = Socket()
        val start = System.nanoTime()
        try {
            socket.connect(InetSocketAddress(ip, port), timeoutMs.coerceIn(200, 30_000))
            val ms = ((System.nanoTime() - start) / 1_000_000L).toInt().coerceAtLeast(1)
            TcpAttempt(ok = true, connectMs = ms, category = ErrorCategory.NONE, message = null)
        } catch (t: Throwable) {
            val ms = ((System.nanoTime() - start) / 1_000_000L).toInt().coerceAtLeast(0)
            TcpAttempt(
                ok = false,
                connectMs = ms,
                category = ErrorClassifier.classify(t),
                message = t.message?.take(160) ?: t.javaClass.simpleName,
            )
        } finally {
            runCatching { socket.close() }
        }
    }

    /**
     * Runs [attempts] connect probes and returns the successful ones.
     * Failing attempts are recorded so success-rate stays honest.
     */
    suspend fun measureLatency(
        ip: String,
        port: Int,
        timeoutMs: Int,
        attempts: Int,
    ): List<TcpAttempt> {
        val out = mutableListOf<TcpAttempt>()
        repeat(attempts.coerceIn(1, 20)) {
            out.add(attempt(ip, port, timeoutMs))
        }
        return out
    }
}
