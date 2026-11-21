package dev.ppp0.poele.net

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * Minuterie d'arrêt (expérimental, SPEC §11) : un programme chrono temporaire « AUTO-OFF jj/mm »,
 * qui n'a qu'un arrêt, à l'échéance, le seul jour de l'échéance. Rien n'est mémorisé côté téléphone :
 * dès que l'arrêt est passé d'après la ligne du programme, on le désactive (sinon il se répéterait
 * chaque semaine). Fonctionne donc avec plusieurs téléphones.
 */
object OffTimer {
    /** Préfixe du nom ; le nom complet porte la date de l'échéance (heure du poêle) : « AUTO-OFF 21/11 ». */
    const val NAME = "AUTO-OFF"
    const val MIN_MINUTES = 15
    private val NAME_DATE = Regex("^$NAME (\\d{2})/(\\d{2})$")

    fun name(deadline: LocalDateTime) = "%s %02d/%02d".format(NAME, deadline.dayOfMonth, deadline.monthValue)

    fun isTimer(p: Program?) = p != null && (p.name == NAME || p.name.startsWith("$NAME "))

    /**
     * Date d'échéance lue dans le nom (jj/mm), rattachée à l'année qui la place au plus près de [today]
     * (le 31/12, « 01/01 » est le lendemain, pas le 1er janvier passé). Null si le nom n'en porte pas.
     */
    fun nameDate(p: Program, today: LocalDate): LocalDate? {
        val (d, m) = NAME_DATE.matchEntire(p.name)?.destructured ?: return null
        return (today.year - 1..today.year + 1)
            .mapNotNull { y -> runCatching { LocalDate.of(y, m.toInt(), d.toInt()) }.getOrNull() }
            .minByOrNull { kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(today, it)) }
    }
    const val MAX_MINUTES = 23 * 60 + 45

    /** Échéance : maintenant + [minutes], arrondie au quart d'heure supérieur. */
    fun deadline(now: LocalDateTime, minutes: Int): LocalDateTime {
        val target = now.plusMinutes(minutes.toLong())
        val hour = target.truncatedTo(ChronoUnit.HOURS)
        val quarters = Math.ceilDiv(ChronoUnit.SECONDS.between(hour, target), 900L)
        return hour.plusMinutes(quarters * 15)
    }

    /** Emplacement à utiliser : celui d'une minuterie existante (nom en [NAME]…), sinon le premier libre ; null si aucun. */
    fun slot(programs: List<Program?>): Int? =
        programs.indexOfFirst(::isTimer).takeIf { it >= 0 } ?: programs.indexOf(null).takeIf { it >= 0 }

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

    /**
     * Le programme de minuterie [p] a-t-il déjà joué son arrêt (marge de 2 min), d'après son **contenu** :
     * date du nom (jj/mm) + heure d'arrêt, comparées à l'horloge du poêle [stoveNow]. Indépendant de ce que
     * le téléphone a mémorisé : vaut quelle que soit l'appli qui l'a écrit, même modifié depuis.
     * Ancien nom sans date : seulement si son jour est aujourd'hui (sinon on ne peut pas savoir).
     */
    fun expired(p: Program?, stoveNow: LocalDateTime): Boolean {
        if (p == null || !isTimer(p) || !p.enabled) return false
        val stop = deadlineOf(p, stoveNow) ?: return false
        return !stoveNow.isBefore(stop.plusMinutes(2))
    }

    /** Arrêt d'un programme de minuterie (date du nom + heure d'arrêt) ; ancien nom sans date : aujourd'hui si c'est son jour. */
    fun deadlineOf(p: Program, stoveNow: LocalDateTime): LocalDateTime? {
        if (!p.stopOn) return null
        val date = nameDate(p, stoveNow.toLocalDate())
            ?: stoveNow.toLocalDate().takeIf { p.days and dayBit(stoveNow) != 0 }
            ?: return null
        return date.atTime(p.eh, p.eq * 15)
    }

    fun program(deadline: LocalDateTime, power: Int) = Program(
        name = name(deadline), enabled = true, startOn = false, sh = 0, sq = 0,
        stopOn = true, eh = deadline.hour, eq = deadline.minute / 15,
        temp = 20, power = power.coerceIn(1, 5), days = dayBit(deadline),
    )
}
