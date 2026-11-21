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
        assertNull(OffTimer.slot(List(6) { Program() }))
    }

    @Test fun conflictsWithActiveProgramOnThatDay() {
        assertEquals(1, OffTimer.conflicts(progs, 3, at(18, 0)).size)   // samedi 18:00 : dans « weekend soir »
        assertTrue(OffTimer.conflicts(progs, 3, at(20, 0)).isEmpty())   // à l'arrêt du programme : pas dedans
        assertTrue(OffTimer.conflicts(progs, 3, at(15, 0)).isEmpty())   // entre deux programmes
        assertTrue(OffTimer.conflicts(progs, 3, at(7, 0)).isEmpty())    // « semaine matin » ne tourne pas le samedi
        val disabled = progs.toMutableList().also { it[1] = it[1]!!.copy(enabled = false) }
        assertTrue(OffTimer.conflicts(disabled, 3, at(18, 0)).isEmpty())
    }

    @Test fun timerProgramIsStopOnlyOnDeadlineDay() {
        val p = OffTimer.program(at(15, 45), 4)
        assertEquals(OffTimer.NAME, p.name)
        assertEquals(false, p.startOn); assertEquals(true, p.stopOn)
        assertEquals(15, p.eh); assertEquals(3, p.eq)
        assertEquals(1 shl 5, p.days) // samedi
        assertTrue(ChronoRules.validate(p).isEmpty())
    }
}
