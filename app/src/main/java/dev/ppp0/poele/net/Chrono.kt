package dev.ppp0.poele.net

import java.text.Normalizer

/** Programme chrono (SPEC §4.5). Heures 0–23, quarts d'heure 0–3, jours : bit 0 = lundi … bit 6 = dimanche. */
data class Program(
    val name: String = "Programme",
    val enabled: Boolean = true,
    val startOn: Boolean = true,
    val sh: Int = 7,
    val sq: Int = 0,
    val stopOn: Boolean = true,
    val eh: Int = 9,
    val eq: Int = 0,
    val temp: Int = 20,
    val power: Int = 3,
    val days: Int = 0x1f,
)

data class Chrono(val enabled: Boolean, val programs: List<Program?>) {
    companion object { const val SLOTS = 6 }
}

object ChronoRules {
    val DAYS = listOf("Lun", "Mar", "Mer", "Jeu", "Ven", "Sam", "Dim")
    const val TEMP_MIN = 7
    const val TEMP_MAX = 40
    const val TEMP_MANUAL = 41
    const val NAME_MAX = 15

    /** ASCII imprimable uniquement, par prudence : accents retirés (é → e), le reste supprimé (SPEC §4.5). */
    fun sanitizeName(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .filter { it.code in 0x20..0x7e }
            .take(NAME_MAX)

    fun minutes(h: Int, q: Int) = h * 60 + q * 15
    fun time(h: Int, q: Int) = "%02d:%02d".format(h, q * 15)

    /** Règles de l'interface d'origine + bornes de l'encodage. Liste vide = valide. */
    fun validate(p: Program): List<String> {
        val e = mutableListOf<String>()
        val name = p.name.trim()
        when {
            name.isEmpty() -> e += "Nom vide"
            name.length > NAME_MAX -> e += "Nom > $NAME_MAX caractères"
            sanitizeName(name) != name -> e += "Nom : caractères ASCII uniquement"
        }
        if (p.sh !in 0..23 || p.sq !in 0..3) e += "Heure de démarrage invalide"
        if (p.eh !in 0..23 || p.eq !in 0..3) e += "Heure d'arrêt invalide"
        if (p.enabled && !p.startOn && !p.stopOn) e += "Activez au moins le démarrage ou l'arrêt"
        if (p.startOn && p.stopOn) {
            val s = minutes(p.sh, p.sq); val t = minutes(p.eh, p.eq)
            if (t == s) e += "Démarrage et arrêt identiques"
            else if (t < s) e += "L'arrêt doit être après le démarrage (pas de passage par minuit)"
        }
        if (p.days and 0x7f == 0) e += "Cochez au moins un jour"
        if (p.temp != TEMP_MANUAL && p.temp !in TEMP_MIN..TEMP_MAX) e += "Température invalide"
        if (p.power !in 1..5) e += "Puissance invalide"
        return e
    }

    /** Avertissement non bloquant : programmes actifs qui se chevauchent un même jour. */
    fun overlaps(programs: List<Program?>): List<String> {
        val act = programs.filterNotNull().filter { it.enabled && it.startOn && it.stopOn }
        val out = mutableListOf<String>()
        for (a in act.indices) for (b in a + 1 until act.size) {
            val p = act[a]; val q = act[b]
            val common = p.days and q.days
            if (common != 0 && minutes(p.sh, p.sq) < minutes(q.eh, q.eq) && minutes(q.sh, q.sq) < minutes(p.eh, p.eq)) {
                out += "« ${p.name} » et « ${q.name} » (" + DAYS.filterIndexed { i, _ -> common shr i and 1 == 1 }.joinToString(", ") + ")"
            }
        }
        return out
    }

    /** Lecture key=033 : [actif, startOn, hD, qD, stopOn, hF, qF, temp, puissance, jours, nom]. Slot libre = nom vide. */
    fun parse(row: List<Any?>): Program? {
        val name = row.getOrNull(10)?.toString().orEmpty()
        if (name.isEmpty()) return null
        fun i(k: Int) = (row.getOrNull(k) as? Number)?.toInt() ?: 0
        return Program(name, i(0) == 1, i(1) == 1, i(2), i(3), i(4) == 1, i(5), i(6), i(7), i(8), i(9))
    }

    /** Écriture key=032 (SPEC §4.5) : enabled + p0n1…p0n7 pour chaque slot non vide. */
    fun encode(c: Chrono): Map<String, Any> {
        val m = linkedMapOf<String, Any>("key" to "032", "enabled" to if (c.enabled) 1 else 0)
        c.programs.forEachIndexed { i, p ->
            if (p == null) return@forEachIndexed
            val k = "p0${i + 1}"
            m[k + "1"] = if (p.enabled) 1 else 0
            m[k + "2"] = ((if (p.startOn) 1 else 0) shl 7) or (p.sh shl 2) or p.sq
            m[k + "3"] = ((if (p.stopOn) 1 else 0) shl 7) or (p.eh shl 2) or p.eq
            m[k + "4"] = p.temp
            m[k + "5"] = p.power
            m[k + "6"] = p.days
            m[k + "7"] = p.name.trim()
        }
        return m
    }
}

object Bcd {
    fun decode(v: Int) = (v shr 4) * 10 + (v and 0x0f)
    fun encode(n: Int) = ((n / 10 % 10) shl 4) or (n % 10)
}
