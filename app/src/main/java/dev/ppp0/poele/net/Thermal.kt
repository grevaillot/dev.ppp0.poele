package dev.ppp0.poele.net

/**
 * Courbe thermique (key=035) : 72 points, **un toutes les 15 min** (≈ 18 h), le dernier = maintenant.
 * Intervalle de 15 min confirmé par la page cloud du fabricant (« Échantillon (15') ») et par l'heure d'une chauffe réelle ;
 * l'interface locale d'origine étiquetait à tort l'axe sur 24 h. airTemp en demi-degrés, setAirTemp en degrés.
 * Une valeur nulle ou hors bornes (MAN = 41…) est un trou.
 */
data class Thermal(val air: List<Float?>, val setpoint: List<Float?>, val loadedAt: Long) {
    val points get() = maxOf(air.size, setpoint.size)
    /** Durée couverte, du premier au dernier point. */
    val spanMinutes get() = (points - 1).coerceAtLeast(0) * MINUTES_PER_POINT

    /** Heure du dernier échantillon : le chargement, ramené au quart d'heure (horloge du téléphone). */
    fun lastSampleTime(zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): java.time.LocalDateTime {
        val at = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(loadedAt), zone).withSecond(0).withNano(0)
        return at.withMinute(at.minute / MINUTES_PER_POINT * MINUTES_PER_POINT)
    }

    /** Heure de l'échantillon [i] : 15 min par point en remontant depuis le dernier. */
    fun sampleTime(i: Int, last: java.time.LocalDateTime = lastSampleTime()) =
        last.minusMinutes(((points - 1 - i) * MINUTES_PER_POINT).toLong())

    /**
     * Repères de l'axe horizontal : échantillons tombant sur 00:00, 06:00, 12:00 et 18:00.
     * Libellé « HH:mm », ou la date « jj/mm » à minuit.
     */
    fun ticks(last: java.time.LocalDateTime = lastSampleTime()): List<Pair<Int, String>> =
        (0 until points).mapNotNull { i ->
            val s = sampleTime(i, last)
            if (s.minute != 0 || s.hour % 6 != 0) null
            else i to if (s.hour == 0) "%02d/%02d".format(s.dayOfMonth, s.monthValue) else "%02d:00".format(s.hour)
        }

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
        const val MINUTES_PER_POINT = 15

        fun parse(airRaw: List<Int>, setRaw: List<Int>, now: Long = System.currentTimeMillis()) = Thermal(
            air = airRaw.map { r -> (r / 2f).takeIf { r > 0 && it < 60f } },
            setpoint = setRaw.map { r -> r.toFloat().takeIf { r in 5..40 } },
            loadedAt = now,
        )
    }
}
