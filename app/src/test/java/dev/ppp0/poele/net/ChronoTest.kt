package dev.ppp0.poele.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChronoTest {
    // Programmes réels lus sur le poêle le 2026-11-21 (key=033).
    private val rows = listOf(
        listOf(1, 1, 8, 0, 1, 10, 0, 20, 3, 96, "weekend matin"),
        listOf(1, 1, 17, 2, 1, 20, 0, 20, 4, 96, "weekend soir"),
        listOf(1, 1, 6, 2, 1, 8, 0, 20, 3, 31, "semaine matin"),
        listOf(1, 1, 18, 1, 1, 20, 2, 20, 3, 31, "Semaine soir"),
        listOf(0, 0, 0, 0, 1, 0, 1, 0, 0, 0, ""), // slot supprimé : champs résiduels, nom vide
    )
    private val progs = rows.map { ChronoRules.parse(it) }

    @Test fun parseAndFreeSlot() {
        assertEquals(Program("weekend soir", true, true, 17, 2, true, 20, 0, 20, 4, 96), progs[1])
        assertNull(progs[4])
    }

    @Test fun realProgramsAreValidAndDoNotOverlap() {
        progs.filterNotNull().forEach { assertEquals(it.name, emptyList<String>(), ChronoRules.validate(it)) }
        assertTrue(ChronoRules.overlaps(progs).isEmpty())
    }

    @Test fun encodeMatchesOriginalUi() {
        val m = ChronoRules.encode(Chrono(true, progs + listOf(null)))
        assertEquals("032", m["key"]); assertEquals(1, m["enabled"])
        assertEquals((1 shl 7) or (17 shl 2) or 2, m["p022"]) // 17:30, démarrage actif
        assertEquals((1 shl 7) or (20 shl 2) or 0, m["p023"])
        assertEquals(96, m["p026"]); assertEquals("weekend soir", m["p027"])
        assertTrue("p051" !in m) // slot vide : rien envoyé
    }

    @Test fun rules() {
        val p = progs[0]!!
        assertTrue(ChronoRules.validate(p.copy(sh = 10, eh = 8)).any { "après" in it })
        assertTrue(ChronoRules.validate(p.copy(sh = 8, sq = 0, eh = 8, eq = 0)).any { "identiques" in it })
        assertTrue(ChronoRules.validate(p.copy(days = 0)).any { "jour" in it })
        assertTrue(ChronoRules.validate(p.copy(startOn = false, stopOn = false)).any { "au moins" in it })
        assertTrue(ChronoRules.validate(p.copy(name = "Matinée")).any { "ASCII" in it })
        assertEquals(emptyList<String>(), ChronoRules.validate(p.copy(temp = 41))) // MAN
        assertEquals(1, ChronoRules.overlaps(listOf(p, progs[1]!!.copy(sh = 9))).size)
    }

    @Test fun sanitize() {
        assertEquals("Matinee", ChronoRules.sanitizeName("Matinée"))
        assertEquals("Soiree cafe", ChronoRules.sanitizeName("Soirée café"))
        assertEquals("Unicode  test", ChronoRules.sanitizeName("Ünïcödé ☀ test"))
        assertEquals(15, ChronoRules.sanitizeName("123456789012345678").length)
    }

    @Test fun bcd() {
        assertEquals(17, Bcd.decode(0x17)); assertEquals(0x59, Bcd.encode(59)); assertEquals(0x26, Bcd.encode(26))
    }
}
