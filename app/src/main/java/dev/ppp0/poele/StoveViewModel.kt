package dev.ppp0.poele

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.ppp0.poele.net.Bcd
import dev.ppp0.poele.net.Chrono
import dev.ppp0.poele.net.ChronoRules
import dev.ppp0.poele.net.Discovery
import dev.ppp0.poele.net.DiscoveryEvent
import dev.ppp0.poele.net.Found
import dev.ppp0.poele.net.LogEntry
import dev.ppp0.poele.net.Registers
import dev.ppp0.poele.net.StoveClient
import dev.ppp0.poele.net.Thermal
import dev.ppp0.poele.net.WifiInfo
import dev.ppp0.poele.net.WifiNet
import dev.ppp0.poele.update.AppUpdater
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

sealed interface Conn {
    data class Searching(val steps: List<String>) : Conn
    data class Failed(val steps: List<String>, val reason: String) : Conn
    data class Connected(val found: Found, val model: Int?) : Conn
}

data class StoveState(
    val regs: Map<Int, Int> = emptyMap(),
    val changed: Set<Int> = emptySet(),
    val alarmText: String = "",
    val name: String? = null,
    val online: Boolean = false,
    val lastError: String? = null,
    val module: JSONObject? = null,
)

class StoveViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("poele", Context.MODE_PRIVATE)
    private val client = StoveClient(prefs.getString("host", null) ?: "", null)

    private val _conn = MutableStateFlow<Conn>(Conn.Searching(emptyList()))
    val conn: StateFlow<Conn> = _conn
    private val _state = MutableStateFlow(StoveState())
    val state: StateFlow<StoveState> = _state
    private val _wifi = MutableStateFlow<WifiInfo?>(null)
    val wifi: StateFlow<WifiInfo?> = _wifi
    val log: StateFlow<List<LogEntry>> = client.log

    private var discoveryJob: Job? = null
    private var pollJob: Job? = null
    private var foreground = false
    var moduleScreenVisible = false
    var clockScreenVisible = false
    @Volatile private var forceSlow = false

    /** Mise à jour depuis les releases GitHub (SPEC §10) ; jamais proposée sur les builds de dev. */
    val updater = AppUpdater(
        viewModelScope, BuildConfig.UPDATE_REPO, java.io.File(app.cacheDir, "updates"),
        BuildConfig.VERSION_CODE, offerUpdates = !BuildConfig.DEBUG,
    )

    init {
        discover()
        updater.check()
    }

    fun discover() {
        discoveryJob?.cancel()
        pollJob?.cancel()
        discoveryJob = viewModelScope.launch {
            val ctx = getApplication<Application>()
            _wifi.value = WifiNet.current(ctx)
            val steps = mutableListOf<String>()
            _conn.value = Conn.Searching(steps.toList())
            Discovery.run(ctx, prefs.getString("host", null)).collect { ev ->
                when (ev) {
                    is DiscoveryEvent.Step -> { steps += ev.text; _conn.value = Conn.Searching(steps.toList()) }
                    is DiscoveryEvent.Failure -> _conn.value = Conn.Failed(steps.toList(), ev.text)
                    is DiscoveryEvent.Success -> connect(ev.found, steps)
                }
            }
        }
    }

    /** Saisie manuelle : validée par key=019 (SPEC §4.1 point 6). */
    fun manual(ip: String) {
        discoveryJob?.cancel()
        discoveryJob = viewModelScope.launch {
            val steps = listOf("Test de $ip…")
            _conn.value = Conn.Searching(steps)
            connect(Found(ip.trim(), prefs.getString("id", null) ?: "?", null, "saisie manuelle"), steps)
        }
    }

    private suspend fun connect(found: Found, steps: List<String>) {
        client.host = found.ip
        client.network = WifiNet.current(getApplication())?.network
        try {
            val cfg = client.post("/ajax/get-registers", mapOf("key" to "019"))
            val model = cfg.optInt("model", -1).takeIf { it >= 0 }
            prefs.edit().putString("host", found.ip).putString("id", found.id).apply()
            _conn.value = Conn.Connected(found, model)
            if (model != Registers.SUPPORTED_MODEL) {
                _state.update { it.copy(lastError = "Modèle $model : l'appli n'est validée que pour le modèle 8 (KTR-AIR BLDC).") }
            }
            if (foreground) startPolling()
        } catch (e: Exception) {
            _conn.value = Conn.Failed(steps + "Réponse de ${found.ip} : ${e.message}", "Le module trouvé ne répond pas en HTTP.")
        }
    }

    /** Polling uniquement au premier plan (SPEC §3). */
    fun setForeground(fg: Boolean) {
        foreground = fg
        if (fg && _conn.value is Conn.Connected) startPolling() else if (!fg) pollJob?.cancel()
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            var slow = 0L
            var moduleAt = 0L
            while (isActive) {
                val t0 = System.currentTimeMillis()
                try {
                    val cats = mutableListOf(2, 6)
                    if (forceSlow || t0 - slow > (if (clockScreenVisible) 5_000 else 30_000)) { cats += 4; slow = t0; forceSlow = false }
                    var meta: JSONObject? = null
                    val fresh = mutableMapOf<Int, Int>()
                    for (c in cats) {
                        val j = client.post("/ajax/get-registers", mapOf("key" to "020", "category" to c))
                        val p = j.optJSONArray("params") ?: continue
                        for (i in 0 until p.length()) p.getJSONArray(i).let { fresh[it.getInt(0)] = it.getInt(1) }
                        meta = j
                    }
                    if (moduleScreenVisible && t0 - moduleAt > 15_000) {
                        moduleAt = t0
                        val m = client.post("/ajax/get-status")
                        _state.update { it.copy(module = m) }
                    }
                    settlePending(fresh)
                    _state.update { s ->
                        val merged = s.regs + fresh
                        s.copy(
                            regs = merged,
                            changed = fresh.filter { (k, v) -> s.regs[k] != null && s.regs[k] != v }.keys,
                            alarmText = meta?.optString("alr", "")?.trim() ?: s.alarmText,
                            name = meta?.optString("name")?.takeIf { it.isNotBlank() && it != "NO NAME" } ?: s.name,
                            online = true,
                            lastError = if (s.lastError?.startsWith("Modèle") == true) s.lastError else null,
                        )
                    }
                } catch (e: Exception) {
                    _state.update { it.copy(online = false, lastError = "Hors ligne : ${e.message}") }
                    delay(3000)
                }
                delay((1000 - (System.currentTimeMillis() - t0)).coerceAtLeast(0))
            }
        }
    }

    // ------------------------------------------------------------ commandes (jalon 2)

    /** Message à afficher (snackbar). */
    val events = MutableSharedFlow<String>(extraBufferCapacity = 8)

    enum class PendingState { Waiting, Sending, Sent, Error }
    data class Pending(val value: Int, val state: PendingState, val at: Long = System.currentTimeMillis())
    private val _pending = MutableStateFlow<Map<Int, Pending>>(emptyMap())
    val pending: StateFlow<Map<Int, Pending>> = _pending
    private val pendingJobs = mutableMapOf<Int, Job>()

    /** Bornes brutes des registres réglables (SPEC §4.3). */
    val bounds = mapOf(Registers.SETPOINT to 5..41, Registers.POWER to 1..5, Registers.FAN to 0..6)

    /** La ventilation n'est réglable que sur la configuration de canalisation où r187 a été vérifié (175 = 3). */
    fun canWrite(reg: Int): Boolean = when (reg) {
        Registers.FAN -> _state.value.regs[Registers.DUCT_CONFIG] == 3
        else -> reg in bounds
    }

    fun displayed(reg: Int): Int? = _pending.value[reg]?.value ?: _state.value.regs[reg]

    /** −/+ : valeur locale immédiate, envoi 700 ms après le dernier appui (SPEC §4.7). */
    fun step(reg: Int, delta: Int) {
        val range = bounds[reg] ?: return
        if (!canWrite(reg)) return
        val cur = displayed(reg) ?: return
        val next = (cur + delta).coerceIn(range)
        if (next == cur) return
        _pending.update { it + (reg to Pending(next, PendingState.Waiting)) }
        pendingJobs[reg]?.cancel()
        pendingJobs[reg] = viewModelScope.launch {
            delay(700)
            _pending.update { it + (reg to Pending(next, PendingState.Sending)) }
            try {
                val r = client.post("/ajax/set-register", mapOf("key" to "002", "memory" to 1, "regId" to reg, "value" to next))
                if (r.has("result") && !r.optBoolean("result")) throw IllegalStateException("refusé par le module")
                _pending.update { it + (reg to Pending(next, PendingState.Sent)) }
            } catch (e: Exception) {
                _pending.update { it + (reg to Pending(next, PendingState.Error)) }
                events.tryEmit("Échec de l'écriture (registre $reg) : ${e.message}")
                delay(2000)
                _pending.update { it - reg }
            }
        }
    }

    /** Libère les valeurs envoyées une fois relues sur le poêle (ou après 4 s). */
    private fun settlePending(fresh: Map<Int, Int>) {
        val now = System.currentTimeMillis()
        _pending.update { p ->
            p.filterNot { (reg, v) -> v.state == PendingState.Sent && (fresh[reg] == v.value || now - v.at > 4000) }
        }
    }

    /** Allumage uniquement depuis l'état 0 et sans alarme (SPEC §4.7). */
    fun powerAllowed(on: Boolean): String? {
        val r = _state.value.regs
        val st = r[Registers.STATUS] ?: return "État pas encore lu."
        return if (on) when {
            st != 0 -> "Le poêle n'est pas éteint (état $st)."
            r[Registers.ALARM] == null -> "Code d'alarme pas encore lu."
            r[Registers.ALARM] != 0 -> "Alarme active (code ${r[Registers.ALARM]}) : allumage refusé."
            else -> null
        } else if (st == 0) "Le poêle est déjà éteint." else null
    }

    fun power(on: Boolean) {
        powerAllowed(on)?.let { events.tryEmit(it); return }
        viewModelScope.launch {
            try {
                client.post("/ajax/get-registers", mapOf("key" to "022", "status" to if (on) 1 else 0))
                events.tryEmit(if (on) "Allumage demandé" else "Extinction demandée")
            } catch (e: Exception) {
                events.tryEmit("Échec : ${e.message}")
            }
        }
    }

    /** Règle l'horloge du poêle sur celle du téléphone (BCD, SPEC §4.3). */
    fun syncClock() {
        viewModelScope.launch {
            val n = java.time.LocalDateTime.now()
            val seq = listOf(
                64 to Bcd.encode(n.year - 2000), 63 to Bcd.encode(n.monthValue), 62 to Bcd.encode(n.dayOfMonth),
                59 to n.dayOfWeek.value, 60 to Bcd.encode(n.hour), 61 to Bcd.encode(n.minute),
            )
            try {
                for ((reg, v) in seq) client.post("/ajax/set-register", mapOf("key" to "002", "memory" to 1, "regId" to reg, "value" to v))
                forceSlow = true
                events.tryEmit("Horloge réglée")
            } catch (e: Exception) {
                events.tryEmit("Échec du réglage de l'horloge : ${e.message}")
            }
        }
    }

    // ------------------------------------------------------------ programmes chrono

    private val _chrono = MutableStateFlow<Chrono?>(null)
    val chrono: StateFlow<Chrono?> = _chrono
    private val _chronoDirty = MutableStateFlow(false)
    val chronoDirty: StateFlow<Boolean> = _chronoDirty

    fun loadChrono() {
        viewModelScope.launch {
            try {
                val j = client.post("/ajax/get-registers", mapOf("key" to "033"))
                val arr = j.getJSONArray("programs")
                val list = (0 until Chrono.SLOTS).map { i ->
                    if (i >= arr.length()) null else arr.getJSONArray(i).let { row -> ChronoRules.parse((0 until row.length()).map { row.get(it) }) }
                }
                _chrono.value = Chrono(j.optBoolean("enabled"), list)
                _chronoDirty.value = false
            } catch (e: Exception) {
                events.tryEmit("Lecture des programmes impossible : ${e.message}")
            }
        }
    }

    fun editChrono(c: Chrono) { _chrono.value = c; _chronoDirty.value = true }

    fun saveChrono() {
        val c = _chrono.value ?: return
        if (c.programs.filterNotNull().any { ChronoRules.validate(it).isNotEmpty() }) {
            events.tryEmit("Des programmes sont à corriger avant l'enregistrement."); return
        }
        viewModelScope.launch {
            try {
                client.post("/ajax/get-registers", ChronoRules.encode(c))
                events.tryEmit("Programmes enregistrés")
                loadChrono()
            } catch (e: Exception) {
                events.tryEmit("Échec de l'enregistrement : ${e.message}")
            }
        }
    }

    fun deleteProgram(i: Int) {
        viewModelScope.launch {
            try {
                client.post("/ajax/get-registers", mapOf("key" to "034", "index" to i))
                _chrono.update { c -> c?.copy(programs = c.programs.toMutableList().also { it[i] = null }) }
                events.tryEmit("Programme supprimé")
            } catch (e: Exception) {
                events.tryEmit("Échec de la suppression : ${e.message}")
            }
        }
    }

    // ------------------------------------------------------------ courbe thermique (key=035)

    private val _thermal = MutableStateFlow<Thermal?>(null)
    val thermal: StateFlow<Thermal?> = _thermal

    fun loadThermal() {
        viewModelScope.launch {
            runCatching {
                val j = client.post("/ajax/get-registers", mapOf("key" to "035"))
                fun ints(k: String) = j.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optInt(it) } }.orEmpty()
                _thermal.value = Thermal.parse(ints("airTemp"), ints("setAirTemp"))
            }
        }
    }

    fun refreshModule() {
        viewModelScope.launch {
            runCatching { client.post("/ajax/get-status") }.onSuccess { m -> _state.update { it.copy(module = m) } }
        }
        _wifi.value = WifiNet.current(getApplication())
    }
}
