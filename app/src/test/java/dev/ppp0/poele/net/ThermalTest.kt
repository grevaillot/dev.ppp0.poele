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
        assertEquals(20, Thermal.parse(List(72) { 40 }, emptyList()).minutesPerPoint)
    }

    @Test fun emptyFallback() {
        val t = Thermal.parse(emptyList(), emptyList())
        assertEquals(15f, t.yRange().start)
        assertNull(t.air.firstOrNull())
    }
}
