package dev.ppp0.poele.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class OffTimerTest {
    private fun at(h: Int, m: Int, s: Int = 0) = LocalDateTime.of(2026, 11, 21, h, m, s) // samedi

    @Test fun deadlineRoundsUpToQuarter() {
        assertEquals(at(15, 0), OffTimer.deadline(at(14, 0), 60))        // pile sur un quart
        assertEquals(at(15, 15), OffTimer.deadline(at(14, 7), 60))       // 15:07 → 15:15
        assertEquals(at(15, 15), OffTimer.deadline(at(14, 0, 1), 60))    // une seconde de trop → quart suivant
        assertEquals(LocalDateTime.of(2026, 11, 22, 0, 0), OffTimer.deadline(at(23, 50), 5)) // passe minuit
    }

    // Programmes réels du poêle (samedi = bit 5, 96 = sam + dim).
    private val progs = listOf(
        Program("weekend matin", true, true, 8, 0, true, 10, 0, 20, 3, 96),
        Program("weekend soir", true, true, 17, 2, true, 20, 0, 20, 4, 96),
        Program("semaine matin", true, true, 6, 2, true, 8, 0, 20, 3, 31),
        null, null, null,
    )

    @Test fun slotPrefersExistingTimerThenFirstFree() {
        assertEquals(3, OffTimer.slot(progs))
        assertEquals(5, OffTimer.slot(progs.toMutableList().also { it[5] = OffTimer.program(at(15, 0), 3).copy(enabled = false) }))
        assertEquals(4, OffTimer.slot(progs.toMutableList().also { it[4] = Program(name = "AUTO-OFF 14/11", enabled = false) }))
        assertNull(OffTimer.slot(List(6) { Program() }))
    }

    @Test fun refusesDeadlineInUpcomingProgram() {
        val now = at(14, 0)
        assertEquals(1, OffTimer.conflicts(progs, 3, at(18, 0), now).size)   // samedi 18:00 : « weekend soir » commence à 17:30
        assertTrue(OffTimer.conflicts(progs, 3, at(20, 0), now).isEmpty())   // à l'arrêt du programme : pas dedans
        assertTrue(OffTimer.conflicts(progs, 3, at(15, 0), now).isEmpty())   // entre deux programmes
        assertTrue(OffTimer.conflicts(progs, 3, at(7, 0), now).isEmpty())    // « semaine matin » ne tourne pas le samedi
        val disabled = progs.toMutableList().also { it[1] = it[1]!!.copy(enabled = false) }
        assertTrue(OffTimer.conflicts(disabled, 3, at(18, 0), now).isEmpty())
    }

    @Test fun allowsCuttingTheRunningProgram() {
        assertTrue(OffTimer.conflicts(progs, 3, at(18, 30), at(17, 45)).isEmpty()) // dans « weekend soir », déjà en cours
        assertTrue(OffTimer.conflicts(progs, 3, at(9, 0), at(8, 10)).isEmpty())    // dans « weekend matin », en cours
        // Le lendemain (dimanche) même heure : programme à venir, refusé.
        assertEquals(1, OffTimer.conflicts(progs, 3, at(18, 30).plusDays(1), at(17, 45)).size)
    }

    @Test fun nameCarriesDeadlineDate() {
        assertEquals("AUTO-OFF 21/11", OffTimer.name(at(15, 45)))
        assertTrue(OffTimer.name(at(15, 45)).length <= ChronoRules.NAME_MAX)
        assertTrue(OffTimer.isTimer(Program(name = "AUTO-OFF 21/11")))
        assertTrue(OffTimer.isTimer(Program(name = "AUTO-OFF")))          // nom de la v1.2.0/1.2.1
        assertTrue(!OffTimer.isTimer(Program(name = "AUTO-OFFICE")))
        val today = java.time.LocalDate.of(2026, 11, 21)
        assertEquals(today, OffTimer.nameDate(Program(name = "AUTO-OFF 21/11"), today))
        assertEquals(java.time.LocalDate.of(2025, 12, 31), OffTimer.nameDate(Program(name = "AUTO-OFF 31/12"), java.time.LocalDate.of(2026, 1, 2)))
        assertEquals(java.time.LocalDate.of(2027, 1, 1), OffTimer.nameDate(Program(name = "AUTO-OFF 01/01"), java.time.LocalDate.of(2026, 12, 31))) // demain
        assertNull(OffTimer.nameDate(Program(name = "AUTO-OFF"), today))
        assertNull(OffTimer.nameDate(Program(name = "AUTO-OFF 31/02"), today))
    }

    @Test fun timerProgramIsStopOnlyOnDeadlineDay() {
        val p = OffTimer.program(at(15, 45), 4)
        assertEquals("AUTO-OFF 21/11", p.name)
        assertEquals(false, p.startOn); assertEquals(true, p.stopOn)
        assertEquals(15, p.eh); assertEquals(3, p.eq)
        assertEquals(1 shl 5, p.days) // samedi
        assertTrue(ChronoRules.validate(p).isEmpty())
    }
}
