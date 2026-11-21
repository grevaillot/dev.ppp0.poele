package dev.ppp0.poele.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThermalTest {
    @Test fun parseHalfDegreesAndGaps() {
        val t = Thermal.parse(listOf(38, 0, 44), listOf(20, 41, 22))
        assertEquals(listOf(19f, null, 22f), t.air)          // demi-degrés, 0 = trou
        assertEquals(listOf(20f, null, 22f), t.setpoint)     // 41 (MAN) = trou
    }

    @Test fun rangeHasMargin() {
        val r = Thermal.parse(List(72) { 40 }, List(72) { 20 }).yRange()
        assertEquals(18f, r.start); assertEquals(22f, r.endInclusive) // ≥ 4 °C d'amplitude autour de 20
        assertEquals(71 * 15, Thermal.parse(List(72) { 40 }, emptyList()).spanMinutes) // 72 points de 15 min ≈ 18 h
    }

    @Test fun ticksOnRealClockTimes() {
        val t = Thermal.parse(List(72) { 40 }, emptyList())
        val last = java.time.LocalDateTime.of(2026, 11, 22, 1, 15)       // dernier point : 22/11 01:15
        assertEquals(java.time.LocalDateTime.of(2026, 11, 21, 7, 30), t.sampleTime(0, last)) // 71 × 15 min avant
        assertEquals(listOf(18 to "12:00", 42 to "18:00", 66 to "22/11"), t.ticks(last)) // 07:30 + 18 × 15 min = 12:00
    }

    @Test fun emptyFallback() {
        val t = Thermal.parse(emptyList(), emptyList())
        assertEquals(15f, t.yRange().start)
        assertNull(t.air.firstOrNull())
    }
}
