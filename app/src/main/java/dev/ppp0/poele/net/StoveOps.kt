package dev.ppp0.poele.net

import android.content.Context
import android.content.SharedPreferences
import java.time.Duration
import java.time.LocalDateTime

/** État lu lors d'un contrôle : de quoi décider d'une notification. */
data class Snapshot(val regs: Map<Int, Int>, val alarmText: String) {
    val status get() = regs[Registers.STATUS]
    /** Texte d'alarme, ou vide s'il n'y en a pas. */
    val alarm: String get() = alarmText.ifBlank {
        regs[Registers.ALARM]?.takeIf { it != 0 }?.let { "code $it" }
            ?: regs[Registers.STATUS]?.takeIf { it == 10 || it == 11 }?.let { Registers.status(it) } ?: ""
    }
}

/**
 * Minuterie en cours, **déduite du programme** AUTO-OFF du poêle (rien n'est mémorisé côté téléphone :
 * plusieurs téléphones voient et nettoient la même). [stoveDeadline] : arrêt, sur l'horloge du poêle.
 */
data class TimerInfo(val stoveDeadline: LocalDateTime, val slot: Int)

/** Résultat d'un nettoyage : une minuterie échue a-t-elle été désactivée, et laquelle reste active. */
data class TimerCheck(val disabled: Boolean, val active: TimerInfo?)

/**
 * Opérations partagées entre l'appli et le service de surveillance : lecture d'état,
 * programmes chrono, minuterie d'arrêt (SPEC §11).
 */
class StoveOps(private val client: StoveClient) {

    suspend fun snapshot(): Snapshot {
        val j = client.post("/ajax/get-registers", mapOf("key" to "020", "category" to 2))
        val p = j.getJSONArray("params")
        val regs = (0 until p.length()).associate { p.getJSONArray(it).let { a -> a.getInt(0) to a.getInt(1) } }
        return Snapshot(regs, j.optString("alr", "").trim())
    }

    suspend fun readChrono(): Chrono {
        val j = client.post("/ajax/get-registers", mapOf("key" to "033"))
        val arr = j.getJSONArray("programs")
        val list = (0 until Chrono.SLOTS).map { i ->
            if (i >= arr.length()) null else arr.getJSONArray(i).let { row -> ChronoRules.parse((0 until row.length()).map { row.get(it) }) }
        }
        return Chrono(j.optBoolean("enabled"), list)
    }

    suspend fun writeChrono(c: Chrono) { client.post("/ajax/get-registers", ChronoRules.encode(c)) }

    // ------------------------------------------------------------ minuterie

    /**
     * Programme l'arrêt dans [minutes], compté sur l'horloge du poêle (c'est elle qui exécute l'arrêt).
     * [regs] : derniers registres connus (état, puissance, horloge). Renvoie la minuterie créée ;
     * lève IllegalStateException avec le motif du refus.
     */
    suspend fun scheduleOff(minutes: Int, regs: Map<Int, Int>, now: LocalDateTime = LocalDateTime.now()): TimerInfo {
        check(minutes in OffTimer.MIN_MINUTES..OffTimer.MAX_MINUTES) { "Durée hors limites (15 min à 23 h 45)." }
        val st = regs[Registers.STATUS] ?: error("État du poêle pas encore lu.")
        check(st != 0) { "Le poêle est déjà éteint." }
        val stoveNow = stoveClock(regs) ?: error("Horloge du poêle pas encore lue, réessaie dans un instant.")
        val skew = Duration.between(now.withSecond(0).withNano(0), stoveNow) // écart poêle − téléphone, à la minute
        val c = readChrono()
        check(c.enabled) { "La programmation horaire est désactivée : le poêle n'exécuterait pas l'arrêt. L'activer réveillerait aussi tes autres programmes." }
        val slot = OffTimer.slot(c.programs) ?: error("Aucun emplacement de programme libre (6 utilisés).")
        val stoveNowExact = now.plus(skew)
        val dl = OffTimer.deadline(stoveNowExact, minutes) // échéance sur l'horloge du poêle
        val conflicts = OffTimer.conflicts(c.programs, slot, dl, stoveNowExact)
        check(conflicts.isEmpty()) { "L'échéance (${ChronoRules.time(dl.hour, dl.minute / 15)}, heure du poêle) tombe dans un programme à venir : ${conflicts.joinToString(", ")}." }
        val p = OffTimer.program(dl, regs[Registers.POWER] ?: 3)
        check(ChronoRules.validate(p).isEmpty()) { "Programme invalide : ${ChronoRules.validate(p)}" }
        writeChrono(c.copy(programs = c.programs.toMutableList().also { it[slot] = p }))
        return TimerInfo(dl, slot)
    }

    /** Désactive les programmes de minuterie actifs (emplacement et nom gardés). */
    suspend fun disableTimer() {
        disableTimers(readChrono()) { true }
    }

    private suspend fun disableTimers(c: Chrono, which: (Program) -> Boolean): Boolean {
        val idx = c.programs.indices.filter { i -> c.programs[i]?.let { OffTimer.isTimer(it) && it.enabled && which(it) } == true }
        if (idx.isEmpty()) return false
        writeChrono(c.copy(programs = c.programs.toMutableList().also { l -> idx.forEach { l[it] = l[it]!!.copy(enabled = false) } }))
        return true
    }

    /**
     * Désactive tout programme AUTO-OFF dont l'arrêt est passé d'après sa ligne de programme
     * (date du nom + heure d'arrêt, horloge du poêle [stoveNow], [OffTimer.expired]), et renvoie
     * la minuterie qui reste active, s'il y en a une.
     */
    suspend fun cleanupTimer(stoveNow: LocalDateTime): TimerCheck {
        val c = readChrono()
        val disabled = disableTimers(c) { p -> OffTimer.expired(p, stoveNow) }
        val active = c.programs.withIndex().firstNotNullOfOrNull { (i, p) ->
            if (p == null || !OffTimer.isTimer(p) || !p.enabled || OffTimer.expired(p, stoveNow)) null
            else OffTimer.deadlineOf(p, stoveNow)?.let { TimerInfo(it, i) }
        }
        return TimerCheck(disabled, active)
    }

    /** Horloge du poêle (catégorie 4), ou null si illisible. */
    suspend fun stoveNow(): LocalDateTime? {
        val j = client.post("/ajax/get-registers", mapOf("key" to "020", "category" to 4))
        val p = j.getJSONArray("params")
        return stoveClock((0 until p.length()).associate { p.getJSONArray(it).let { a -> a.getInt(0) to a.getInt(1) } })
    }

    companion object {
        fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences("poele", Context.MODE_PRIVATE)

        fun stoveClock(regs: Map<Int, Int>): LocalDateTime? = runCatching {
            LocalDateTime.of(2000 + Bcd.decode(regs[64]!!), Bcd.decode(regs[63]!!), Bcd.decode(regs[62]!!),
                Bcd.decode(regs[60]!!), Bcd.decode(regs[61]!!))
        }.getOrNull()
    }
}
