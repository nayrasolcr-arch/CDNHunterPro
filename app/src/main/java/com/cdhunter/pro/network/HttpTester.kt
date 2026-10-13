package com.cdhunter.pro.network

import com.cdhunter.pro.core.model.ErrorCategory
import com.cdhunter.pro.core.model.HttpProbe
import com.cdhunter.pro.core.util.HostnameValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.create
import okhttp3.Response
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * HTTP/HTTPS tester built on OkHttp.
 *
 * A custom [Dns] pins the request to the candidate IP while the URL host
 * (SNI + certificate validation + Host header) stays the tested hostname —
 * exactly how CDN-fronting compatibility must be probed. Certificate
 * validation is never disabled.
 *
 * HTTP/2 is supported transparently by OkHttp (h2 via ALPN). HTTP/3 is NOT
 * supported by the library and is therefore reported as untested.
 */
class HttpTester {

    private data class Metrics(
        var ttfbMs: Int? = null,
        var connectMs: Int? = null,
        val start: Long = System.nanoTime(),
    )

    suspend fun probe(
        ip: String,
        hostHeaderOrSni: String,
        port: Int,
        timeoutMs: Int,
        maxBytes: Long = 256_000L,
        path: String = "/",
        method: String = "GET",
        bodyBytes: ByteArray? = null,
    ): HttpProbe = withContext(Dispatchers.IO) {
        val sni = hostHeaderOrSni.trim()
        val isHttps = port == 443 || port in listOf(2053, 2083, 2087, 2096, 8443)
        val effectiveSni = if (HostnameValidator.isValid(sni)) HostnameValidator.normalize(sni) else ""
        // URL host: use SNI when valid; otherwise the IP itself (cert check will
        // likely fail — recorded honestly, never bypassed).
        val urlHost = effectiveSni.ifEmpty { ip }
        val scheme = if (isHttps) "https" else "http"
        val url = "$scheme://$urlHost${if (port in listOf(80, 443)) "" else ":$port"}$path"

        val metrics = Metrics()
        val listener = object : EventListener() {
            override fun responseHeadersStart(call: Call) {
                metrics.ttfbMs = elapsedMs(metrics.start)
            }
        }

        val dns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val addr = InetAddress.getByName(ip) ?: throw java.net.UnknownHostException(ip)
                return listOf(addr)
            }
        }

        val base = OkHttpClient.Builder()
            .dns(dns)
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(timeoutMs.coerceIn(200, 30_000).toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs.coerceIn(200, 60_000).toLong(), TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs.coerceIn(500, 90_000) * 2L, TimeUnit.MILLISECONDS)
            .eventListener(listener)
            .followRedirects(true)

        val client = base.build()

        val requestBody = bodyBytes?.let {
            create("application/octet-stream".toMediaType(), it)
        }
        var builder = Request.Builder().url(url)
        if (effectiveSni.isNotEmpty() && urlHost != effectiveSni) {
            builder = builder.header("Host", effectiveSni)
        }
        if (method.equals("POST", ignoreCase = true) && requestBody != null) {
            builder = builder.post(requestBody)
        }
        builder = builder.header("User-Agent", "CDNHunterPro/1.0 (connectivity test)")

        val start = System.nanoTime()
        var bytes: Long = 0
        try {
            client.newCall(builder.build()).execute().use { resp: Response ->
                val code = resp.code
                bytes += readCapped(resp, maxBytes) { read -> bytes += read }
                val totalMs = elapsedMs(start)
                val version = when (resp.protocol) {
                    okhttp3.Protocol.HTTP_2 -> "h2"
                    okhttp3.Protocol.HTTP_1_1, okhttp3.Protocol.HTTP_1_0 -> "http/1.1"
                    else -> resp.protocol.toString()
                }
                HttpProbe(
                    ok = code in 200..399,
                    statusCode = code,
                    ttfbMs = metrics.ttfbMs ?: totalMs,
                    totalMs = totalMs,
                    version = version,
                    downloadKbps = if (bytes > 0 && totalMs > 0) (bytes * 8.0 / 1000.0) / (totalMs / 1000.0) else null,
                    bytesReceived = bytes,
                    category = ErrorClassifier.classifyHttpStatus(code),
                    message = if (code in 200..399) null else "HTTP $code",
                )
            }
        } catch (t: Throwable) {
            HttpProbe(
                ok = false,
                statusCode = null,
                ttfbMs = null,
                totalMs = elapsedMs(start),
                version = null,
                downloadKbps = null,
                bytesReceived = 0,
                category = ErrorClassifier.classify(t),
                message = t.message?.take(160) ?: t.javaClass.simpleName,
            )
        } finally {
            runCatching { client.dispatcher.executorService.shutdown() }
        }
    }

    /** POST upload probe (only used with a provider-controlled destination). */
    suspend fun uploadProbe(
        ip: String,
        sni: String,
        timeoutMs: Int,
        payloadBytes: Int = 16_384,
    ): HttpProbe = probe(
        ip = ip,
        hostHeaderOrSni = sni,
        port = 443,
        timeoutMs = timeoutMs,
        maxBytes = 4_000L,
        path = "/__up",
        method = "POST",
        bodyBytes = ByteArray(payloadBytes),
    )

    private fun readCapped(resp: Response, maxBytes: Long, onRead: (Long) -> Unit): Long {
        var total = 0L
        val src = resp.body?.byteStream() ?: return 0
        val buf = ByteArray(8_192)
        while (total < maxBytes) {
            val n = src.read(buf, 0, minOf(buf.size.toLong(), maxBytes - total).toInt())
            if (n < 0) break
            total += n
        }
        onRead(total)
        return total
    }

    private fun elapsedMs(startNanos: Long): Int =
        ((System.nanoTime() - startNanos) / 1_000_000L).toInt().coerceAtLeast(1)

    companion object {
        /** Approximate per-probe request overhead used for data-cap accounting. */
        const val REQUEST_OVERHEAD_BYTES: Long = 1_200L
    }
}
