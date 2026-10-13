package com.cdhunter.pro.core.util

/**
 * Hostname validation mirroring the rules of the official Shir o Khorshid
 * client (labels of letters/digits/hyphen, 63-char labels, 253-char names,
 * no bare IPv4). Used for SNI validation and export validation.
 */
object HostnameValidator {

    fun isValid(raw: String): Boolean {
        var s = raw.trim()
        if (s.isEmpty() || s.length > 253) return false
        if (IpValidator.isValidIpv4(s) || s.contains(':')) return false // no IPs as SNI
        if (s.endsWith(".")) s = s.dropLast(1)
        if (s.isEmpty()) return false
        val labels = s.split(".")
        for (label in labels) {
            if (label.isEmpty() || label.length > 63) return false
            if (label.startsWith("-") || label.endsWith("-")) return false
            for (c in label) {
                if (!c.isLetterOrDigit() && c != '-') return false
            }
        }
        return true
    }

    /** Lowercase + trailing-dot normalization (same as the client). */
    fun normalize(raw: String): String {
        var s = raw.trim().lowercase()
        if (s.endsWith(".")) s = s.dropLast(1)
        return s
    }
}
