package dev.ppp0.poele.net

/** Registres du modèle 8 (KTR-AIR BLDC), SPEC §4.3. Seule source de vérité de l'appli. */
object Registers {
    const val STATUS = 2
    const val ALARM = 3
    const val EXTRACTOR = 5
    const val AMBIENT = 6
    const val SETPOINT = 50
    const val POWER = 51
    const val FAN = 187
    const val DUCT_CONFIG = 175

    const val SETPOINT_MANUAL = 41
    const val SUPPORTED_MODEL = 8

    val STATUS_LABELS = mapOf(
        0 to "Éteint", 1 to "En attente de redémarrage", 2 to "Allumage", 3 to "Attente de flamme",
        4 to "Présence de flamme", 5 to "En fonctionnement", 6 to "Nettoyage brasier", 7 to "Nettoyage final",
        8 to "Nettoyage final (Eco stop)", 9 to "Eco stop", 10 to "Alarme", 11 to "Mémoire alarmes",
    )

    fun status(v: Int?) = v?.let { STATUS_LABELS[it] ?: "Code $it" } ?: "—"
    fun setpoint(v: Int?) = when (v) { null -> "—"; SETPOINT_MANUAL -> "MAN"; else -> "$v °C" }
    fun power(v: Int?) = v?.let { "P$it" } ?: "—"
    fun fan(v: Int?) = when (v) { null -> "—"; 0 -> "OFF"; in 1..5 -> "P$v"; 6 -> "AUTO"; else -> "Code $v" }
    fun ambient(v: Int?) = v?.takeIf { it > 0 }?.let { "$it °C" } ?: "—"
    /** Formule vérifiée par un tiers sur AIR-RDS (SPEC §4.3, 🔶). */
    fun extractor(v: Int?) = when (v) { null -> "—"; 0 -> "Arrêté"; else -> "${v * 10 + 250} tr/min" }

    val RAW_NAMES = mapOf(
        0 to "Sonde interne (×0,5 °C)", 2 to "État", 3 to "Code d'alarme", 5 to "Extracteur", 6 to "Ambiante (°C)",
        50 to "Consigne", 51 to "Puissance", 59 to "Jour semaine", 60 to "Heure (BCD)", 61 to "Minutes (BCD)",
        62 to "Jour (BCD)", 63 to "Mois (BCD)", 64 to "Année (BCD)", 175 to "Type de canalisation", 187 to "Ventilation",
    )
}

/** Tables de status.html du module (SPEC §4.6). */
object ModuleCodes {
    val WIFI = mapOf(0 to "Inactif", 1 to "Connexion en cours", 2 to "Mot de passe incorrect", 3 to "Point d'accès introuvable",
        4 to "Échec de connexion", 5 to "Connecté", 255 to "Pas en mode station")
    val CLOUD = mapOf(0 to "Inactif", 1 to "En pause", 2 to "Connecté", 3 to "Non connecté")
    val DISCONNECT = mapOf(0 to "Aucune", 200 to "Perte des balises", 201 to "Point d'accès introuvable",
        202 to "Échec d'authentification", 203 to "Échec d'association", 204 to "Délai de négociation dépassé")

    fun dec(table: Map<Int, String>, v: Int?) = v?.let { "${table[it] ?: "Code 802.11"} ($it)" } ?: "—"
}
