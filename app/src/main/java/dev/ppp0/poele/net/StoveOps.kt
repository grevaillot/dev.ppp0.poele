package dev.ppp0.poele.net

import android.content.Context
import android.content.SharedPreferences
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId

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
 * Minuterie en cours, mémorisée côté téléphone (le poêle ne stocke pas de date).
 * [stoveDeadline] : heure programmée, sur l'horloge du poêle ; [deadline] : la même échéance sur l'horloge du téléphone.
 */
data class TimerInfo(val deadline: LocalDateTime, val stoveDeadline: LocalDateTime, val slot: Int)

/**
 * Opérations partagées entre l'appli et le service de surveillance : lecture d'état,
 * programmes chrono, minuterie d'arrêt (SPEC §11).
 */
class StoveOps(private val client: StoveClient, private val prefs: SharedPreferences) {

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

    fun timer(): TimerInfo? {
        val at = prefs.getLong(KEY_DEADLINE, 0L).takeIf { it > 0 } ?: return null
        val dl = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(at), ZoneId.systemDefault())
        val stove = prefs.getString(KEY_STOVE_DEADLINE, null)?.let(LocalDateTime::parse) ?: dl
        return TimerInfo(dl, stove, prefs.getInt(KEY_SLOT, -1))
    }

    private fun saveTimer(t: TimerInfo?) {
        prefs.edit().apply {
            if (t == null) remove(KEY_DEADLINE).remove(KEY_STOVE_DEADLINE).remove(KEY_SLOT)
            else putLong(KEY_DEADLINE, t.deadline.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                .putString(KEY_STOVE_DEADLINE, t.stoveDeadline.toString()).putInt(KEY_SLOT, t.slot)
        }.apply()
    }

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
        return TimerInfo(dl.minus(skew), dl, slot).also(::saveTimer)
    }

    /** Désactive le programme de la minuterie (emplacement gardé, nom conservé) et oublie l'échéance. */
    suspend fun disableTimer() {
        val c = readChrono()
        val i = c.programs.indexOfFirst { it?.name == OffTimer.NAME }
        if (i >= 0 && c.programs[i]!!.enabled) {
            writeChrono(c.copy(programs = c.programs.toMutableList().also { it[i] = it[i]!!.copy(enabled = false) }))
        }
        saveTimer(null)
    }

    /** Désactive la minuterie si son échéance est passée (marge de 2 min). Renvoie true si c'est fait. */
    suspend fun cleanupTimerIfDue(now: LocalDateTime = LocalDateTime.now()): Boolean {
        val t = timer() ?: return false
        if (now.isBefore(t.deadline.plusMinutes(2))) return false
        disableTimer()
        return true
    }

    companion object {
        private const val KEY_DEADLINE = "timer_deadline"
        private const val KEY_SLOT = "timer_slot"
        private const val KEY_STOVE_DEADLINE = "timer_stove_deadline"

        fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences("poele", Context.MODE_PRIVATE)

        fun stoveClock(regs: Map<Int, Int>): LocalDateTime? = runCatching {
            LocalDateTime.of(2000 + Bcd.decode(regs[64]!!), Bcd.decode(regs[63]!!), Bcd.decode(regs[62]!!),
                Bcd.decode(regs[60]!!), Bcd.decode(regs[61]!!))
        }.getOrNull()
    }
}
