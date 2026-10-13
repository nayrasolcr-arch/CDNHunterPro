package com.cdhunter.pro.core.util

import java.math.BigInteger
import java.net.InetAddress

/**
 * CIDR parsing, deterministic range sampling and overlap de-duplication.
 *
 * Sampling is evenly spaced across the range (instead of sequential scanning)
 * which both reduces wasted probes and avoids hammering the first addresses
 * of every block.
 */
object Cidr {

    data class ParsedCidr(
        val base: String,
        val prefix: Int,
        val family: Int,
        val total: Long,
    )

    fun parse(raw: String): ParsedCidr? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        val parts = s.split("/")
        if (parts.size != 2) return null
        val ip = parts[0].trim()
        val prefix = parts[1].trim().toIntOrNull() ?: return null
        return when {
            IpValidator.isValidIpv4(ip) && prefix in 0..32 -> {
                val total = if (prefix == 0) 1L shl 32 else (1L shl (32 - prefix))
                ParsedCidr(ip, prefix, 4, total)
            }
            IpValidator.isValidIpv6(ip) && prefix in 0..128 -> {
                val totalBI = BigInteger.ONE.shiftLeft(128 - prefix)
                val total = if (totalBI > BigInteger.valueOf(Long.MAX_VALUE)) Long.MAX_VALUE
                else totalBI.toLong()
                ParsedCidr(canonicalV6(ip), prefix, 6, total)
            }
            else -> null
        }
    }

    private fun canonicalV6(ip: String): String = try {
        InetAddress.getByName(ip).hostAddress ?: ip
    } catch (_: Exception) {
        ip
    }

    private fun v4ToLong(ip: String): Long {
        val b = IpValidator.parse(ip)!!.address
        return ((b[0].toLong() and 0xFF) shl 24) or ((b[1].toLong() and 0xFF) shl 16) or
            ((b[2].toLong() and 0xFF) shl 8) or (b[3].toLong() and 0xFF)
    }

    private fun longToV4(v: Long): String = String.format(
        "%d.%d.%d.%d",
        (v ushr 24) and 0xFF, (v ushr 16) and 0xFF, (v ushr 8) and 0xFF, v and 0xFF,
    )

    /**
     * Returns up to [count] deterministic, evenly spaced sample IPs of [cidr].
     * Never more than the range can hold; includes the base address.
     * For very large IPv6 blocks (smaller prefix than 112) the sample is taken
     * from the first addresses because probing the whole space is impossible —
     * this is documented as a known limitation.
     */
    fun sample(cidr: String, count: Int): List<String> {
        val parsed = parse(cidr) ?: return emptyList()
        if (count <= 0 || parsed.total <= 0) return emptyList()
        val n = minOf(count.toLong(), parsed.total).toInt()
        return if (parsed.family == 4) {
            val base = v4ToLong(parsed.base)
            val total = parsed.total
            val step = if (n >= total) 1L else total / n
            (0 until n).map { i ->
                val v = base + i * step
                longToV4(v)
            }
        } else {
            // IPv6: sample the first N addresses of the block (documented limitation).
            val base = BigInteger(1, IpValidator.parse(parsed.base)!!.address)
            (0 until n).map { i ->
                val v = base.add(BigInteger.valueOf(i.toLong()))
                val bytes = v.toByteArray()
                val padded = ByteArray(16)
                val copyFrom = maxOf(0, bytes.size - 16)
                val copyLen = minOf(16, bytes.size)
                System.arraycopy(bytes, copyFrom, padded, 16 - copyLen, copyLen)
                InetAddress.getByAddress(padded).hostAddress
                    ?: padded.joinToString(":") { "%02x".format(it) }
            }
        }
    }

    /** True if [ip] is inside [cidr] (both must be the same family). */
    fun contains(cidr: String, ip: String): Boolean {
        val parsed = parse(cidr) ?: return false
        return when (parsed.family) {
            4 -> {
                if (!IpValidator.isValidIpv4(ip)) return false
                val base = v4ToLong(parsed.base)
                val v = v4ToLong(ip)
                val mask = if (parsed.prefix == 0) 0L else (-1L shl (32 - parsed.prefix)) and 0xFFFFFFFFL
                (v and mask) == (base and mask)
            }
            6 -> {
                if (!IpValidator.isValidIpv6(ip)) return false
                val base = BigInteger(1, IpValidator.parse(parsed.base)!!.address)
                val v = BigInteger(1, IpValidator.parse(ip)!!.address)
                val mask = v6Mask(parsed.prefix)
                v.and(mask) == base.and(mask)
            }
            else -> false
        }
    }

    private fun v6Mask(prefix: Int): BigInteger {
        if (prefix <= 0) return BigInteger.ZERO
        if (prefix >= 128) return BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE)
        return BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE.shiftLeft(128 - prefix))
    }

    /**
     * De-duplicates CIDR list: exact duplicates and ranges already covered by
     * a bigger range are removed. Mixed families are kept separately.
     */
    fun dedupeRanges(ranges: List<String>): List<String> {
        val parsed = ranges.mapNotNull { parse(it) }.distinctBy { it.family to it.base to it.prefix }
        val out = mutableListOf<ParsedCidr>()
        for (r in parsed.sortedWith(compareBy({ it.family }, { it.prefix }))) {
            val covered = out.any { keep ->
                keep.family == r.family && covers(keep, r)
            }
            if (!covered) out.add(r)
        }
        // remove newly-covered items that appear after adding wider ones
        val final = out.filter { r ->
            !out.any { other -> other !== r && other.family == r.family && covers(other, r) }
        }
        return final.map { "${it.base}/${it.prefix}" }
    }

    private fun covers(wider: ParsedCidr, narrower: ParsedCidr): Boolean =
        wider.prefix <= narrower.prefix && contains("${wider.base}/${wider.prefix}", narrower.base)
}
