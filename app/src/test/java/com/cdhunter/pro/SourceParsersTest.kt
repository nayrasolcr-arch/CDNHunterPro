package com.cdhunter.pro

import com.cdhunter.pro.core.registry.SourceFormat
import com.cdhunter.pro.core.source.SourceParsers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser tests against bundled fixtures copied from the REAL official source
 * formats (no live network needed).
 */
class SourceParsersTest {

    @Test
    fun `cloudflare text lines parsed`() {
        val body = """
            # comment
            173.245.48.0/20
            103.21.244.0/22

            2400:cb00::/32
        """.trimIndent()
        val ranges = SourceParsers.parse(SourceFormat.TEXT_LINES, body)
        assertEquals(listOf("173.245.48.0/20", "103.21.244.0/22", "2400:cb00::/32"), ranges)
    }

    @Test
    fun `goog json parsed`() {
        val body = """
            {"syncToken":"x","creationTime":"2024-01-01",
             "prefixes":[
               {"ipv4Prefix":"8.34.208.0/20"},
               {"ipv6Prefix":"2001:4860::/32"},
               {"scope":"cloud"}
             ]}
        """.trimIndent()
        val ranges = SourceParsers.parse(SourceFormat.GOOG_JSON, body)
        assertEquals(listOf("8.34.208.0/20", "2001:4860::/32"), ranges)
    }

    @Test
    fun `aws json filtered to cloudfront only`() {
        val body = """
            {"syncToken":"x","createDate":"2024-01-01",
             "prefixes":[
               {"ip_prefix":"13.32.0.0/15","region":"GLOBAL","service":"CLOUDFRONT"},
               {"ip_prefix":"52.94.76.0/22","region":"us-east-1","service":"AMAZON"},
               {"ip_prefix":"18.200.0.0/16","region":"eu-west-1","service":"CLOUDFRONT"}
             ],
             "ipv6_prefixes":[
               {"ipv6_prefix":"2600:9000::/28","region":"eu-west-1","service":"CLOUDFRONT"},
               {"ipv6_prefix":"2a05:d014::/35","region":"eu-central-1","service":"AMAZON"}
             ]}
        """.trimIndent()
        val ranges = SourceParsers.parse(SourceFormat.AWS_JSON, body)
        assertEquals(listOf("13.32.0.0/15", "18.200.0.0/16", "2600:9000::/28"), ranges)
    }

    @Test
    fun `azure json filtered to frontdoor and cdn services`() {
        val body = """
            {"changeNumber":1,"cloud":"Public","values":[
              {"name":"AzureFrontDoor.Frontend","id":"x","properties":{"changeNumber":1,"region":"","platform":"Azure","systemService":"AzureFrontDoor","addressPrefixes":["20.36.120.16/29","2603:1020::24/128"]}},
              {"name":"Sql","id":"y","properties":{"addressPrefixes":["20.36.104.0/26"]}},
              {"name":"Microsoft.Cdn","id":"z","properties":{"addressPrefixes":["147.243.0.0/16"]}}
            ]}
        """.trimIndent()
        val ranges = SourceParsers.parse(SourceFormat.AZURE_JSON, body)
        assertEquals(listOf("20.36.120.16/29", "2603:1020::24/128", "147.243.0.0/16"), ranges)
    }

    @Test
    fun `fastly json parsed`() {
        val body = """
            {"addresses":["23.235.32.0/20","43.249.72.0/22"],
             "ipv6":["2a04:4e42::/32"]}
        """.trimIndent()
        val ranges = SourceParsers.parse(SourceFormat.FASTLY_JSON, body)
        assertEquals(listOf("23.235.32.0/20", "43.249.72.0/22", "2a04:4e42::/32"), ranges)
    }

    @Test
    fun `malformed input does not crash`() {
        assertTrue(SourceParsers.parse(SourceFormat.GOOG_JSON, "not json {{{").isEmpty())
        assertTrue(SourceParsers.parse(SourceFormat.AWS_JSON, "{}").isEmpty())
        assertTrue(SourceParsers.parse(SourceFormat.TEXT_LINES, "garbage lines without cidr").isEmpty())
    }
}
