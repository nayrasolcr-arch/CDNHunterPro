package com.cdhunter.pro.network

import com.cdhunter.pro.core.model.ErrorCategory
import com.cdhunter.pro.core.model.TlsProbe
import com.cdhunter.pro.core.util.HostnameValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SNIServerName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.security.auth.x500.X500Principal
import java.security.cert.X509Certificate

/**
 * TLS tester with FULL certificate validation always enabled (spec: certificate
 * validation must never be disabled in production).
 *
 * SNI is set only for valid hostnames — an IP literal cannot be an SNI per
 * RFC 6066, in which case the handshake runs without the extension and the
 * certificate match usually fails (recorded honestly as incompatible).
 */
class TlsTester(
    private val socketFactory: SSLSocketFactory = getDefaultFactory(),
) {

    suspend fun probe(
        ip: String,
        port: Int,
        sni: String,
        timeoutMs: Int,
        verifyHostname: Boolean = true,
    ): TlsProbe = withContext(Dispatchers.IO) {
        val effectiveSni = sni.trim().let { if (HostnameValidator.isValid(it)) HostnameValidator.normalize(it) else "" }
        val socket = socketFactory.createSocket() as SSLSocket
        try {
            socket.soTimeout = timeoutMs.coerceIn(200, 30_000)
            socket.connect(InetSocketAddress(ip, port), timeoutMs.coerceIn(200, 30_000))
            if (effectiveSni.isNotEmpty()) {
                val params: SSLParameters = socket.sslParameters
                params.serverNames = listOf<SNIServerName>(SNIHostName(effectiveSni))
                socket.sslParameters = params
            }
            val start = System.nanoTime()
            socket.startHandshake()
            val ms = ((System.nanoTime() - start) / 1_000_000L).toInt().coerceAtLeast(1)

            val session = socket.session
            val protocol = try { session.protocol } catch (_: Exception) { null }
            val certs = try {
                session.peerCertificates?.filterIsInstance<X509Certificate>()
            } catch (_: Exception) {
                null
            }
            val leaf = certs?.firstOrNull()
            val cn = leaf?.let { extractCn(it.subjectX500Principal) }
            val notAfter = leaf?.notAfter?.time

            val matched = if (verifyHostname && effectiveSni.isNotEmpty()) {
                // Default platform hostname verifier — never bypassed.
                HttpsURLConnection.getDefaultHostnameVerifier()
                    .verify(effectiveSni, session)
            } else {
                false
            }

            TlsProbe(
                ok = true,
                handshakeMs = ms,
                protocol = protocol,
                certCn = cn,
                certNotAfterMs = notAfter,
                certMatched = matched,
                category = if (matched) ErrorCategory.NONE else ErrorCategory.TLS_CERT,
                message = if (matched) null else "certificate did not match SNI '$effectiveSni'",
            )
        } catch (t: Throwable) {
            TlsProbe(
                ok = false,
                handshakeMs = 0,
                protocol = null,
                certCn = null,
                certNotAfterMs = null,
                certMatched = false,
                category = ErrorClassifier.classify(t),
                message = t.message?.take(160) ?: t.javaClass.simpleName,
            )
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun extractCn(principal: X500Principal): String? {
        val parts = principal.getName(javax.security.auth.x500.X500Principal.RFC2253).split(",")
        return parts.firstOrNull { it.trim().startsWith("CN=") }?.trim()?.removePrefix("CN=")
    }

    companion object {
        private fun getDefaultFactory(): SSLSocketFactory = (javax.net.ssl.SSLSocketFactory.getDefault() as SSLSocketFactory)
    }
}
