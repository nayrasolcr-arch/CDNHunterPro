package com.cdhunter.pro.network

import com.cdhunter.pro.core.model.ErrorCategory
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Maps low-level exceptions to machine-readable [ErrorCategory] values.
 * Pure and unit-testable. The categories drive verdicts, retry decisions and
 * the human-readable error report.
 */
object ErrorClassifier {

    fun classify(t: Throwable): ErrorCategory = when (t) {
        is UnknownHostException -> ErrorCategory.DNS
        is SocketTimeoutException -> ErrorCategory.TCP_TIMEOUT
        is PortUnreachableException -> ErrorCategory.TCP_REFUSED
        is NoRouteToHostException -> ErrorCategory.TCP_TIMEOUT
        is ConnectException -> when {
            t.message?.contains("refused", ignoreCase = true) == true -> ErrorCategory.TCP_REFUSED
            t.message?.contains("timeout", ignoreCase = true) == true -> ErrorCategory.TCP_TIMEOUT
            else -> ErrorCategory.TCP_REFUSED
        }
        is SSLPeerUnverifiedException -> ErrorCategory.TLS_CERT
        is SSLHandshakeException -> when {
            t.message?.contains("certificate", ignoreCase = true) == true -> ErrorCategory.TLS_CERT
            else -> ErrorCategory.TLS_HANDSHAKE
        }
        is SSLException -> ErrorCategory.TLS_HANDSHAKE
        is UnknownServiceException -> ErrorCategory.HTTP_ERROR
        is java.io.InterruptedIOException ->
            if (t.message?.contains("timeout", ignoreCase = true) == true) {
                ErrorCategory.HTTP_TIMEOUT
            } else {
                ErrorCategory.CANCELLED
            }
        is kotlinx.coroutines.CancellationException -> ErrorCategory.CANCELLED
        else -> ErrorCategory.UNKNOWN
    }

    /** 4xx/5xx HTTP responses are recorded, but never treated as tunnel proof. */
    fun classifyHttpStatus(code: Int): ErrorCategory =
        if (code in 200..399) ErrorCategory.NONE else ErrorCategory.HTTP_ERROR
}
