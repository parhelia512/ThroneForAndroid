package io.nekohasekai.sagernet.fmt.hysteria

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HysteriaFmtTest {

    @Test
    fun firstPortFromSinglePort() {
        assertEquals(443, getFirstPort("443"))
    }

    @Test
    fun firstPortFromCommaList() {
        assertEquals(443, getFirstPort("443,8000-9000"))
    }

    @Test
    fun firstPortFromDashRange() {
        assertEquals(1000, getFirstPort("1000-2000"))
    }

    @Test
    fun firstPortFromMixedList() {
        assertEquals(2000, getFirstPort("2000-3000,4000"))
    }

    @Test
    fun firstPortInvalidFallsBackTo443() {
        assertEquals(443, getFirstPort("bad"))
        assertEquals(443, getFirstPort(""))
    }

    @Test
    fun hopPortsNormalizeToColonRangeFormat() {
        // sing-box 1.14 内核 ParsePorts 要求 "start:end" 冒号区间
        assertEquals(listOf("1000:2000"), hopPortsToSingboxList("1000-2000"))
        assertEquals(listOf("443:443", "8000:9000"), hopPortsToSingboxList("443, 8000-9000"))
        assertTrue("非法片段被丢弃", hopPortsToSingboxList("bad,1000-2000").contains("1000:2000"))
        assertTrue("空输入返回空列表", hopPortsToSingboxList("").isEmpty())
    }

    @Test
    fun hopIntervalFloorIs15sAndDefault30s() {
        // sing-quic 硬下限 5s，取更保守的 15s 下限；未配置/低于下限默认 30s
        assertEquals(30, resolveHopInterval(null))
        assertEquals(30, resolveHopInterval(10))
        assertEquals(15, resolveHopInterval(15))
        assertEquals(45, resolveHopInterval(45))
    }
}