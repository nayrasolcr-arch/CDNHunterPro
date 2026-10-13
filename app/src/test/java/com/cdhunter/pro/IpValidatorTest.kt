package com.cdhunter.pro

import com.cdhunter.pro.core.util.IpValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IpValidatorTest {

    // ---------- IPv4 ----------

    @Test
    fun `valid ipv4 addresses accepted`() {
        listOf("1.2.3.4", "0.0.0.0", "255.255.255.255", "104.16.1.1", "8.8.8.8")
            .forEach { assertTrue("should be valid: $it", IpValidator.isValidIpv4(it)) }
    }

    @Test
    fun `invalid ipv4 addresses rejected`() {
        listOf(
            "", "1.2.3", "1.2.3.4.5", "256.1.1.1", "1.2.3.-1", "1.2.3.a",
            "01.2.3.4", "1.2.3.04", " 1.2.3.4x", "1..2.3", "1.2.3.",
        ).forEach { assertFalse("should be invalid: '$it'", IpValidator.isValidIpv4(it)) }
    }

    @Test
    fun `private and reserved ipv4 are not public`() {
        listOf(
            "10.0.0.1", "172.16.0.1", "172.31.255.255", "192.168.1.1",
            "127.0.0.1", "169.254.1.1", "100.64.0.1", "0.1.2.3",
            "224.0.0.1", "240.0.0.1", "255.255.255.255", "198.18.0.1",
            "192.0.2.1", "198.51.100.7", "203.0.113.9",
        ).forEach { assertFalse("must not be public: $it", IpValidator.isPublicRoutable(it)) }
    }

    @Test
    fun `public ipv4 accepted as routable`() {
        listOf("1.1.1.1", "8.8.4.4", "104.16.132.229", "151.101.1.140", "13.107.42.14")
            .forEach { assertTrue(IpValidator.isPublicRoutable(it)) }
    }

    // ---------- IPv6 ----------

    @Test
    fun `valid ipv6 literals accepted`() {
        listOf(
            "2606:4700:4700::1111",
            "::1",
            "2001:db8::1",
            "fe80::1",
            "2001:4860:4860::8888",
            "::ffff:1.2.3.4",
        ).forEach { assertTrue("should be valid: $it", IpValidator.isValidIpv6(it)) }
    }

    @Test
    fun `invalid ipv6 rejected`() {
        listOf("", "1.2.3.4", "example.com", "2606:4700::1111:", "gg::1", "1:2:3:4:5:6:7:8:9", "fe80::1%eth0")
            .forEach { assertFalse("should be invalid: '$it'", IpValidator.isValidIpv6(it)) }
    }

    @Test
    fun `reserved ipv6 blocks are not public`() {
        listOf("::1", "fe80::1", "fc00::1", "fd12::1", "ff02::1", "2001:db8::1")
            .forEach { assertFalse("must not be public: $it", IpValidator.isPublicRoutable(it)) }
    }

    @Test
    fun `global ipv6 accepted as routable`() {
        listOf("2606:4700:4700::1111", "2001:4860:4860::8888")
            .forEach { assertTrue(IpValidator.isPublicRoutable(it)) }
    }

    @Test
    fun `family detection`() {
        assertEquals(4, IpValidator.family("1.2.3.4"))
        assertEquals(6, IpValidator.family("2606:4700::1111"))
        assertEquals(0, IpValidator.family("nope"))
    }
}
