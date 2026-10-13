package com.cdhunter.pro

import com.cdhunter.pro.core.util.HostnameValidator
import com.cdhunter.pro.network.ErrorClassifier
import com.cdhunter.pro.core.model.ErrorCategory
import com.cdhunter.pro.network.SpeedStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class UtilsAndEngineTest {

    // ------------------------------------------------ HostnameValidator (SK rules)

    @Test
    fun `hostname validation mirrors shir-o-khorshid rules`() {
        assertTrue(HostnameValidator.isValid("cloudflare.com"))
        assertTrue(HostnameValidator.isValid("a248.e.akamai.net"))
        assertTrue(HostnameValidator.isValid("EXAMPLE.COM."))
        assertFalse(HostnameValidator.isValid("-bad.com"))
        assertFalse(HostnameValidator.isValid("bad-.com"))
        assertFalse(HostnameValidator.isValid("1.2.3.4"))
        assertFalse(HostnameValidator.isValid("2606:4700::1111"))
        assertFalse(HostnameValidator.isValid(""))
        assertFalse(HostnameValidator.isValid("a".repeat(64) + ".com"))
        assertFalse(HostnameValidator.isValid("space name.com"))
    }

    @Test
    fun `hostname normalization lowercases and strips trailing dot`() {
        assertEquals("example.com", HostnameValidator.normalize("EXAMPLE.COM."))
    }

    // ------------------------------------------------ ErrorClassifier

    @Test
    fun `exception classification`() {
        assertEquals(ErrorCategory.DNS, ErrorClassifier.classify(UnknownHostException("no dns")))
        assertEquals(ErrorCategory.TCP_TIMEOUT, ErrorClassifier.classify(SocketTimeoutException("connect timed out")))
        assertEquals(ErrorCategory.TCP_REFUSED, ErrorClassifier.classify(ConnectException("connection refused")))
        val ssl = SSLHandshakeException("sun.security.validator.ValidatorException: certificate")
        assertEquals(ErrorCategory.TLS_CERT, ErrorClassifier.classify(ssl))
        val sslOther = SSLHandshakeException("handshake_failure")
        assertEquals(ErrorCategory.TLS_HANDSHAKE, ErrorClassifier.classify(sslOther))
    }

    @Test
    fun `http status classification`() {
        assertEquals(ErrorCategory.NONE, ErrorClassifier.classifyHttpStatus(200))
        assertEquals(ErrorCategory.HTTP_ERROR, ErrorClassifier.classifyHttpStatus(403))
        assertEquals(ErrorCategory.HTTP_ERROR, ErrorClassifier.classifyHttpStatus(500))
    }

    // ------------------------------------------------ SpeedStats (latency/jitter)

    @Test
    fun `latency stats min avg median max`() {
        val s = SpeedStats.fromSamples(listOf(120, 40, 80, 60), attempts = 4, successes = 4)
        assertEquals(40, s.minMs)
        assertEquals(75, s.avgMs)
        assertEquals(70, s.medianMs) // (60+80)/2
        assertEquals(120, s.maxMs)
        assertEquals(4, s.attempts)
    }

    @Test
    fun `jitter is mean abs diff of consecutive samples`() {
        val s = SpeedStats.fromSamples(listOf(100, 120, 90), attempts = 3, successes = 3)
        assertEquals((20 + 30) / 2, s.jitterMs)
    }

    @Test
    fun `empty sample set yields zero stats with attempts preserved`() {
        val s = SpeedStats.fromSamples(emptyList(), attempts = 3, successes = 0)
        assertEquals(0, s.minMs)
        assertEquals(0, s.successes)
        assertEquals(3, s.attempts)
    }

    @Test
    fun `rate-limit suspicion needs doubling not certainty`() {
        assertTrue(SpeedStats.suspectRateLimit(50, 150))
        assertFalse(SpeedStats.suspectRateLimit(50, 60))
        assertFalse(SpeedStats.suspectRateLimit(0, 150))
    }
}
