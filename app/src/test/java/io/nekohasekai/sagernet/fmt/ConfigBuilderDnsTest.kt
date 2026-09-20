package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.ktx.isIpAddress
import moe.matsuri.nb4a.SingBoxOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * sing-box 1.14 typed DNS server 生成契约（对应 change port-ownbox-sb1141）：
 * - 旧式 address/address_resolver/address_strategy 字段 MUST NOT 出现在发射结果中；
 * - 各 scheme MUST 映射为对应 type 的 typed server；
 * - 非 IP 服务器地址 MUST 携带 domain_resolver/domain_strategy 引用。
 */
class ConfigBuilderDnsTest {

    private fun mapOf(server: SingBoxOptions.DNSServerOptions): Map<String, Any> = server.asMap()

    @Test
    fun httpsDohConvertsToTypedServer() {
        val dns = buildDnsServer(
            "https://dns.example.com/dns-query", "dns-remote",
            detour = "proxy", domainResolver = "dns-direct", domainStrategy = "ipv4_only"
        )
        assertEquals("https", dns.type)
        assertEquals("dns-remote", dns.tag)
        assertEquals("dns.example.com", dns.server)
        assertEquals(443, dns.server_port)
        assertEquals("/dns-query", dns.path)
        assertEquals("proxy", dns.detour)
        assertEquals("dns-direct", dns.domain_resolver)
        assertEquals("ipv4_only", dns.domain_strategy)

        val map = mapOf(dns)
        assertEquals("https", map["type"])
        assertFalse("legacy address field must not be emitted", map.containsKey("address"))
        assertFalse("legacy address_resolver field must not be emitted", map.containsKey("address_resolver"))
        assertFalse("legacy address_strategy field must not be emitted", map.containsKey("address_strategy"))
        assertFalse("legacy strategy field must not be emitted", map.containsKey("strategy"))
    }

    @Test
    fun httpsDohCustomPortAndDefaultPath() {
        val dns = buildDnsServer("https://dns.google:8443", "dns-x")
        assertEquals("https", dns.type)
        assertEquals("dns.google", dns.server)
        assertEquals(8443, dns.server_port)
        assertEquals("/dns-query", dns.path)
    }

    @Test
    fun tlsQuicTcpUdpDefaultPorts() {
        assertEquals("tls", buildDnsServer("tls://dns.example.com", "t").type)
        assertEquals(853, buildDnsServer("tls://dns.example.com", "t").server_port)
        assertEquals("quic", buildDnsServer("quic://dns.example.com", "t").type)
        assertEquals(853, buildDnsServer("quic://dns.example.com", "t").server_port)
        assertEquals("tcp", buildDnsServer("tcp://dns.example.com", "t").type)
        assertEquals(53, buildDnsServer("tcp://dns.example.com", "t").server_port)
        val udp = buildDnsServer("8.8.4.4:5353", "t")
        assertEquals("udp", udp.type)
        assertEquals("8.8.4.4", udp.server)
        assertEquals(5353, udp.server_port)
    }

    @Test
    fun h3ConvertsToTypedServer() {
        val dns = buildDnsServer("h3://dns.example.com/q", "dns-h3")
        assertEquals("h3", dns.type)
        assertEquals("dns.example.com", dns.server)
        assertEquals(443, dns.server_port)
        assertEquals("/q", dns.path)
    }

    @Test
    fun localAndHostsMapToLocalType() {
        val local = buildDnsServer("local", "dns-local", detour = "direct")
        assertEquals("local", local.type)
        assertEquals("direct", local.detour)
        assertNull(local.server)
        val hosts = buildDnsServer("hosts", "dns-hosts")
        assertEquals("local", hosts.type)
    }

    @Test
    fun ipAddressHostSkipsDomainResolver() {
        val dns = buildDnsServer("https://8.8.8.8/dns-query", "dns-direct", domainResolver = "dns-local")
        assertNull(dns.domain_resolver)
        assertNull(dns.domain_strategy)
        assertTrue("8.8.8.8".isIpAddress())
    }

    @Test
    fun domainHostKeepsDomainResolver() {
        val dns = buildDnsServer("tls://dns.example.com:8853", "dns-direct", domainResolver = "dns-local")
        assertEquals("dns.example.com", dns.server)
        assertEquals(8853, dns.server_port)
        assertEquals("dns-local", dns.domain_resolver)
    }

    @Test
    fun ipv6BracketAddressParses() {
        val dns = buildDnsServer("[2001:db8::1]:53", "dns-v6")
        assertEquals("udp", dns.type)
        assertEquals("2001:db8::1", dns.server)
        assertEquals(53, dns.server_port)
        assertNull(dns.domain_resolver)
    }

    @Test
    fun fakeipServerCarriesRanges() {
        // sing-box 1.14：fakeip 为 dns.servers 中 type:"fakeip" 的 server
        val fakeip = SingBoxOptions.DNSServerOptions().apply {
            type = "fakeip"
            tag = "dns-fake"
            inet4_range = "198.18.0.0/15"
            inet6_range = "fc00::/18"
        }
        val map = mapOf(fakeip)
        assertEquals("fakeip", map["type"])
        assertEquals("198.18.0.0/15", map["inet4_range"])
        assertEquals("fc00::/18", map["inet6_range"])
    }

    @Test
    fun hostsServerUsesTypedTypeField() {
        val hosts = SingBoxOptions.DNSServerOptions().apply {
            type = "hosts"
            tag = "dns-hosts"
        }
        val map = mapOf(hosts)
        assertEquals("hosts", map["type"])
        assertFalse(map.containsKey("address"))
    }

    @Test
    fun dnsRuleSupportsRejectAction() {
        // sing-box 1.14：dns-block server 移除，拦截规则以 action:"reject" 表达
        val rule = SingBoxOptions.DNSRule_DefaultOptions().apply {
            query_type = listOf("AAAA")
            action = "reject"
        }
        val map = rule.asMap()
        assertEquals("reject", map["action"])
        assertNull(rule.server)
    }

    @Test
    fun fragmentFallbackDelayParsesFirstRangeValue() {
        assertEquals("10ms", parseFragmentFallbackDelay("10-20"))
        // 多段时取首段的首值（spec：逗号分隔多段时取首段）
        assertEquals("10ms", parseFragmentFallbackDelay("10-20,30-40"))
        assertEquals("30ms", parseFragmentFallbackDelay("25-35,30-40"))
        assertEquals("10ms", parseFragmentFallbackDelay("10 20"))
        assertEquals("10ms", parseFragmentFallbackDelay("10-20-30"))
        assertEquals("10ms", parseFragmentFallbackDelay(""))
        assertEquals("10ms", parseFragmentFallbackDelay("   "))
        assertEquals("abc", parseFragmentFallbackDelay("abc"))
    }
}
