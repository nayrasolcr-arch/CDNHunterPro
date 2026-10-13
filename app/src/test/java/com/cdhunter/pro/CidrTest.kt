package com.cdhunter.pro

import com.cdhunter.pro.core.util.Cidr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CidrTest {

    @Test
    fun `parses valid ipv4 cidr`() {
        val p = Cidr.parse("104.16.0.0/12")
        assertEquals("104.16.0.0", p!!.base)
        assertEquals(12, p.prefix)
        assertEquals(4, p.family)
        assertEquals(1L shl 20, p.total)
    }

    @Test
    fun `parses ipv6 cidr`() {
        val p = Cidr.parse("2606:4700::/32")
        assertEquals(32, p!!.prefix)
        assertEquals(6, p.family)
        assertTrue(p.total > 0)
    }

    @Test
    fun `rejects invalid cidr`() {
        listOf("", "1.2.3.4", "1.2.3.4/33", "1.2.3.4/x", "2606:4700::/129", "nonsense/8")
            .forEach { assertNull("should be invalid: '$it'", Cidr.parse(it)) }
    }

    @Test
    fun `sampling is deterministic bounded and spread`() {
        val a = Cidr.sample("104.16.0.0/12", 5)
        val b = Cidr.sample("104.16.0.0/12", 5)
        assertEquals(a, b)
        assertEquals(5, a.size)
        // first sample is the base address
        assertEquals("104.16.0.0", a.first())
        // spread across the range, not sequential from start
        assertTrue(a.distinct().size == 5)
        // cannot exceed range size (/30 holds 4 addresses; network+broadcast
        // are still legitimate scan targets for CDN blocks)
        assertEquals(4, Cidr.sample("10.0.0.0/30", 8).size)
    }

    @Test
    fun `sampling from ipv6 block returns valid ipv6`() {
        val samples = Cidr.sample("2606:4700::/32", 3)
        assertEquals(3, samples.size)
        samples.forEach {
            assertTrue("sample should be valid v6: $it", com.cdhunter.pro.core.util.IpValidator.isValidIpv6(it))
        }
    }

    @Test
    fun `contains checks ip membership`() {
        assertTrue(Cidr.contains("104.16.0.0/12", "104.20.5.5"))
        assertFalse(Cidr.contains("104.16.0.0/12", "105.20.5.5"))
        assertTrue(Cidr.contains("2606:4700::/32", "2606:4700:4700::1111"))
        assertFalse(Cidr.contains("2606:4700::/32", "2607:4700::1111"))
    }

    @Test
    fun `dedupe removes duplicates and covered ranges`() {
        val deduped = Cidr.dedupeRanges(
            listOf(
                "104.16.0.0/12",
                "104.16.0.0/12",           // exact duplicate
                "104.18.32.0/20",          // inside /12
                "151.101.0.0/22",          // independent
            ),
        )
        assertEquals(2, deduped.size)
        assertTrue(deduped.contains("104.16.0.0/12"))
        assertTrue(deduped.contains("151.101.0.0/22"))
    }
}
