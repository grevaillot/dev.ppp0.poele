package dev.ppp0.poele.net

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * Minuterie d'arrêt (expérimental, SPEC §11) : un programme chrono temporaire, nommé [NAME],
 * qui n'a qu'un arrêt, à l'échéance, le seul jour de l'échéance. L'appli mémorise la date exacte
 * et désactive le programme dès que l'échéance est passée, sinon il se répéterait chaque semaine.
 */
object OffTimer {
    const val NAME = "AUTO-OFF"
    const val MIN_MINUTES = 15
    const val MAX_MINUTES = 23 * 60 + 45

    /** Échéance : maintenant + [minutes], arrondie au quart d'heure supérieur. */
    fun deadline(now: LocalDateTime, minutes: Int): LocalDateTime {
        val target = now.plusMinutes(minutes.toLong())
        val hour = target.truncatedTo(ChronoUnit.HOURS)
        val quarters = Math.ceilDiv(ChronoUnit.SECONDS.between(hour, target), 900L)
        return hour.plusMinutes(quarters * 15)
    }

    /** Emplacement à utiliser : celui déjà nommé [NAME], sinon le premier libre ; null si aucun. */
    fun slot(programs: List<Program?>): Int? =
        programs.indexOfFirst { it?.name == NAME }.takeIf { it >= 0 } ?: programs.indexOf(null).takeIf { it >= 0 }

    /** Bit du jour (0 = lundi … 6 = dimanche) d'une date. */
    fun dayBit(d: LocalDateTime) = 1 shl (d.dayOfWeek.value - 1)

    /**
     * Programmes actifs (autres que la minuterie) dans lesquels tombe l'échéance, sauf celui qui tourne
     * déjà ([now], même jour) : écourter la chauffe en cours est permis, couper un programme à venir non.
     * Intervalle actif : [début, arrêt[ ; un programme sans arrêt est actif du début à minuit ;
     * un programme sans démarrage n'a pas d'intervalle actif. Heures du poêle.
     */
    fun conflicts(programs: List<Program?>, timerSlot: Int?, deadline: LocalDateTime, now: LocalDateTime): List<String> {
        val bit = dayBit(deadline)
        val t = deadline.hour * 60 + deadline.minute
        val sameDay = now.toLocalDate() == deadline.toLocalDate()
        val n = now.hour * 60 + now.minute
        return programs.mapIndexedNotNull { i, p ->
            if (p == null || i == timerSlot || !p.enabled || !p.startOn || p.days and bit == 0) return@mapIndexedNotNull null
            val s = ChronoRules.minutes(p.sh, p.sq)
            val e = if (p.stopOn) ChronoRules.minutes(p.eh, p.eq) else 24 * 60
            val running = sameDay && n in s until e
            if (t in s until e && !running) "« ${p.name} » (${ChronoRules.time(p.sh, p.sq)} → ${if (p.stopOn) ChronoRules.time(p.eh, p.eq) else "—"})" else null
        }
    }

    fun program(deadline: LocalDateTime, power: Int) = Program(
        name = NAME, enabled = true, startOn = false, sh = 0, sq = 0,
        stopOn = true, eh = deadline.hour, eq = deadline.minute / 15,
        temp = 20, power = power.coerceIn(1, 5), days = dayBit(deadline),
    )
}
