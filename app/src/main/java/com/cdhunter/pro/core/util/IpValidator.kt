package com.cdhunter.pro.core.util

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Strict IP validation and public-routability checks.
 *
 * The app must never present private/reserved addresses as public CDN IPs,
 * therefore every imported candidate is checked with [isPublicRoutable].
 */
object IpValidator {

    fun isValidIp(raw: String): Boolean = isValidIpv4(raw) || isValidIpv6(raw)

    /** Strict dotted-quad IPv4: exactly 4 decimal parts, each 0..255. */
    fun isValidIpv4(raw: String): Boolean {
        val s = raw.trim()
        if (s.isEmpty()) return false
        val parts = s.split(".")
        if (parts.size != 4) return false
        for (part in parts) {
            if (part.isEmpty() || part.length > 3) return false
            for (c in part) if (c !in '0'..'9') return false
            // Reject leading zeros (e.g. 01.2.3.4) to avoid octal-interpretation pitfalls.
            if (part.length > 1 && part[0] == '0') return false
            val v = part.toIntOrNull() ?: return false
            if (v !in 0..255) return false
        }
        return true
    }

    /** IPv6 literal validation via InetAddress (handles ::, dual notation; zones rejected). */
    fun isValidIpv6(raw: String): Boolean {
        val s = raw.trim()
        // Require at least one ':' so hostnames and decimal/int literals are never
        // accepted, and InetAddress never performs a DNS lookup for this check.
        if (s.isEmpty() || s.contains('%') || !s.contains(':')) return false
        return try {
            val addr = InetAddress.getByName(s)
            // IPv4-mapped literals (::ffff:a.b.c.d) are returned by the JVM as
            // Inet4Address — they are still valid IPv6 literals.
            addr is Inet6Address ||
                (addr is Inet4Address && s.lowercase().startsWith("::ffff:"))
        } catch (_: Exception) {
            false
        }
    }

    fun parse(raw: String): InetAddress? = try {
        InetAddress.getByName(raw.trim())
    } catch (_: Exception) {
        null
    }

    fun family(raw: String): Int = when {
        isValidIpv4(raw) -> 4
        isValidIpv6(raw) -> 6
        else -> 0
    }

    /**
     * True only for globally routable, non-reserved addresses.
     * Covers the standard IANA special-purpose ranges for IPv4 and the
     * main reserved blocks for IPv6 (loopback, link-local, ULA, multicast,
     * documentation, unspecified).
     */
    fun isPublicRoutable(raw: String): Boolean {
        val ip = parse(raw) ?: return false
        return if (ip is Inet4Address) isPublicV4(ip.address) else isPublicV6(ip.address)
    }

    private fun v4ToInt(b: ByteArray): Long =
        ((b[0].toLong() and 0xFF) shl 24) or ((b[1].toLong() and 0xFF) shl 16) or
            ((b[2].toLong() and 0xFF) shl 8) or (b[3].toLong() and 0xFF)

    private fun inCidr(value: Long, base: Long, prefix: Int): Boolean {
        if (prefix == 0) return true
        val mask = (-1L shl (32 - prefix)) and 0xFFFFFFFFL
        return (value and mask) == (base and mask)
    }

    private fun isPublicV4(bytes: ByteArray): Boolean {
        val v = v4ToInt(bytes)
        val specials = listOf(
            "0.0.0.0" to 8,          // this-network
            "10.0.0.0" to 8,         // private
            "100.64.0.0" to 10,      // CGNAT
            "127.0.0.0" to 8,        // loopback
            "169.254.0.0" to 16,     // link-local
            "172.16.0.0" to 12,      // private
            "192.0.0.0" to 24,       // IETF protocol assignments
            "192.0.2.0" to 24,       // documentation
            "192.88.99.0" to 24,     // 6to4 relay anycast (deprecated)
            "192.168.0.0" to 16,     // private
            "198.18.0.0" to 15,      // benchmarking
            "198.51.100.0" to 24,    // documentation
            "203.0.113.0" to 24,     // documentation
            "224.0.0.0" to 4,        // multicast
            "240.0.0.0" to 4,        // reserved/broadcast
        )
        for ((base, prefix) in specials) {
            val b = parse(base)!!.address
            if (inCidr(v, v4ToInt(b), prefix)) return false
        }
        return true
    }

    private fun isPublicV6(bytes: ByteArray): Boolean {
        val first = bytes[0].toInt() and 0xFF
        val second = bytes[1].toInt() and 0xFF
        // ::/128 unspecified, ::1/128 loopback
        if (bytes.all { it.toInt() == 0 }) return false
        if (bytes.dropLast(1).all { it.toInt() == 0 } && bytes.last().toInt() == 1) return false
        // fe80::/10 link-local
        if (first == 0xFE && (second and 0xC0) == 0x80) return false
        // fc00::/7 unique-local
        if ((first and 0xFE) == 0xFC) return false
        // ff00::/8 multicast
        if (first == 0xFF) return false
        // 2001:db8::/32 documentation
        if (first == 0x20 && second == 0x01 &&
            (bytes[2].toInt() and 0xFF) == 0x0D && (bytes[3].toInt() and 0xFF) == 0xB8
        ) return false
        // 64:ff9b::/96 NAT64 translation (not a usable CDN edge)
        if (first == 0x00 && second == 0x64 && (bytes[2].toInt() and 0xFF) == 0xFF &&
            (bytes[3].toInt() and 0xFF) == 0x9B
        ) return false
        // ::ffff:0:0/96 IPv4-mapped
        if (bytes.take(10).all { it.toInt() == 0 } && bytes[10].toInt() == 0 && bytes[11].toInt() == 0) {
            return isPublicV4(bytes.drop(12).toByteArray())
        }
        return true
    }
}
