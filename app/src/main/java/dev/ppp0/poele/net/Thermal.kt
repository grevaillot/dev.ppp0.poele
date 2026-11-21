package dev.ppp0.poele.net

/**
 * Courbe thermique des dernières 24 h (key=035) : 72 points, un toutes les 20 min, le dernier = maintenant
 * (repères de l'interface d'origine : −24h … 0h). airTemp en demi-degrés, setAirTemp en degrés.
 * Une valeur nulle ou hors bornes (MAN = 41…) est un trou.
 */
data class Thermal(val air: List<Float?>, val setpoint: List<Float?>, val loadedAt: Long) {
    val minutesPerPoint get() = 24 * 60 / maxOf(1, maxOf(air.size, setpoint.size))

    /** Bornes de l'axe vertical, arrondies au degré, au moins 4 °C d'amplitude. */
    fun yRange(): ClosedFloatingPointRange<Float> {
        val all = (air + setpoint).filterNotNull()
        if (all.isEmpty()) return 15f..25f
        var lo = kotlin.math.floor(all.min()) - 1
        var hi = kotlin.math.ceil(all.max()) + 1
        if (hi - lo < 4) { val mid = kotlin.math.round((hi + lo) / 2); lo = mid - 2; hi = mid + 2 }
        if ((hi - lo).toInt() % 2 == 1) hi += 1 // amplitude paire : le repère du milieu tombe sur un degré entier
        return lo..hi
    }

    companion object {
        fun parse(airRaw: List<Int>, setRaw: List<Int>, now: Long = System.currentTimeMillis()) = Thermal(
            air = airRaw.map { r -> (r / 2f).takeIf { r > 0 && it < 60f } },
            setpoint = setRaw.map { r -> r.toFloat().takeIf { r in 5..40 } },
            loadedAt = now,
        )
    }
}
