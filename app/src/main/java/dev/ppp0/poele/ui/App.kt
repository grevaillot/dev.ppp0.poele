package dev.ppp0.poele.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ppp0.poele.BuildConfig
import dev.ppp0.poele.Conn
import dev.ppp0.poele.R
import dev.ppp0.poele.StoveState
import dev.ppp0.poele.StoveViewModel
import dev.ppp0.poele.StoveViewModel.PendingState
import dev.ppp0.poele.net.Bcd
import dev.ppp0.poele.net.ChronoRules
import dev.ppp0.poele.net.ModuleCodes
import dev.ppp0.poele.net.Registers
import dev.ppp0.poele.update.FeedStatus
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private enum class Tab(val label: String, val icon: Int) {
    Home("Accueil", R.drawable.ic_home), Chrono("Programmes", R.drawable.ic_calendar),
    Device("Appareil", R.drawable.ic_wifi), Diag("Diagnostic", R.drawable.ic_list),
}

private val PAGE = PaddingValues(16.dp)
private val GAP = Arrangement.spacedBy(12.dp)

/** Couleur de l'état : gris éteint, bleu allumage, vert travail, orange nettoyage/eco, rouge alarme. */
internal fun statusColor(st: Int?, alarm: Boolean): Color = when {
    alarm || st == 10 || st == 11 -> StatusColors.error
    st == null || st == 0 -> StatusColors.off
    st in 1..4 -> StatusColors.running
    st == 5 -> StatusColors.ok
    else -> StatusColors.warning
}

private fun StoveState.alarm(): String =
    alarmText.ifBlank { regs[Registers.ALARM]?.takeIf { it != 0 }?.let { "code $it" } ?: "" }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: StoveViewModel) {
    val conn by vm.conn.collectAsStateWithLifecycle()
    val s by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.events.collect { snack.showSnackbar(it) } }
    Scaffold(
        topBar = {
            TopAppBar(title = {
                Column {
                    Text(s.name ?: "Poêle", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val sub = when (val c = conn) {
                        is Conn.Connected -> (if (s.online) Registers.status(s.regs[Registers.STATUS]) else "Hors ligne") + " · ${c.found.ip}"
                        is Conn.Searching -> "Recherche du module…"
                        is Conn.Failed -> "Module introuvable"
                    }
                    Text(sub, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            })
        },
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            if (conn is Conn.Connected) NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(selected = tab == t, onClick = { tab = t },
                        icon = { Icon(painterResource(t.icon), contentDescription = null) }, label = { Text(t.label, maxLines = 1) })
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            when (val c = conn) {
                is Conn.Connected -> when (tab) {
                    Tab.Home -> HomeScreen(vm)
                    Tab.Chrono -> ChronoScreen(vm)
                    Tab.Device -> DeviceScreen(vm, c)
                    Tab.Diag -> DiagScreen(vm)
                }
                else -> ConnectionScreen(vm, c)
            }
        }
    }
}

@Composable
private fun ConnectionScreen(vm: StoveViewModel, conn: Conn) {
    var ip by rememberSaveable { mutableStateOf("") }
    val steps = when (conn) { is Conn.Searching -> conn.steps; is Conn.Failed -> conn.steps; else -> emptyList() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PAGE, verticalArrangement = GAP) {
        item {
            Section("Recherche du module") {
                if (conn is Conn.Searching) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(end = 12.dp))
                    Text("Recherche sur le Wi-Fi…")
                }
                steps.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (conn is Conn.Failed) {
                    Text(conn.reason, color = StatusColors.error)
                    Button(onClick = vm::discover) { Text("Réessayer") }
                }
            }
        }
        item {
            Section("Adresse manuelle") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(ip, { ip = it }, Modifier.weight(1f), placeholder = { Text("192.168.1.50") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    OutlinedButton(onClick = { vm.manual(ip) }, enabled = ip.isNotBlank()) { Text("Valider") }
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(vm: StoveViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val upd by vm.updater.state.collectAsStateWithLifecycle()
    val thermal by vm.thermal.collectAsStateWithLifecycle()
    // Courbe : à l'affichage de l'accueil, puis toutes les 5 min (un point toutes les 20 min côté poêle).
    LaunchedEffect(Unit) { while (true) { vm.loadThermal(); kotlinx.coroutines.delay(300_000) } }
    val r = s.regs
    val st = r[Registers.STATUS]
    val alarm = s.alarm()
    var ask by remember { mutableStateOf<Boolean?>(null) }

    @Composable
    fun step(reg: Int, label: String, fmt: (Int?) -> String, note: String? = null) {
        val p = pending[reg]
        val color = when (p?.state) {
            PendingState.Error -> StatusColors.error
            null -> Color.Unspecified
            else -> StatusColors.running
        }
        StepRow(label, fmt(p?.value ?: r[reg]), vm.canWrite(reg) && r[reg] != null, color, note,
            onMinus = { vm.step(reg, -1) }, onPlus = { vm.step(reg, +1) })
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PAGE, verticalArrangement = GAP) {
        if (upd.available != null) item { UpdateBanner(upd, vm.updater::download) }
        item {
            Section(null) {
                // Température ambiante en vedette, consigne à côté pour comparer d'un coup d'œil.
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(Registers.ambient(r[Registers.AMBIENT]), fontSize = 56.sp, fontWeight = FontWeight.Bold, lineHeight = 56.sp)
                    Column(Modifier.padding(start = 12.dp, bottom = 8.dp)) {
                        Text("ambiante", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("consigne ${Registers.setpoint(pending[Registers.SETPOINT]?.value ?: r[Registers.SETPOINT])}",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(Registers.status(st), color = statusColor(st, alarm.isNotBlank()), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                if (alarm.isNotBlank()) Text("Alarme : $alarm", color = StatusColors.error)
                if (!s.online) Text(s.lastError ?: "Connexion…", color = StatusColors.error, style = MaterialTheme.typography.bodySmall)
                if (st != null) {
                    if (st == 0) Button(onClick = { ask = true }, Modifier.fillMaxWidth()) { Text("Allumer") }
                    else OutlinedButton(onClick = { ask = false }, Modifier.fillMaxWidth()) { Text("Éteindre") }
                }
            }
        }
        item {
            Section("Réglages") {
                step(Registers.SETPOINT, "Consigne", Registers::setpoint)
                step(Registers.POWER, "Puissance", Registers::power)
                step(Registers.FAN, "Ventilation", Registers::fan,
                    note = if (vm.canWrite(Registers.FAN) || r[Registers.DUCT_CONFIG] == null) null else "Lecture seule sur cette canalisation")
            }
        }
        item {
            Section("Dernières 24 h") {
                val t = thermal
                if (t == null) Text("Lecture…", style = MaterialTheme.typography.bodySmall)
                else ThermalChart(t, Modifier.fillMaxWidth())
            }
        }
    }
    ask?.let { on ->
        val refuse = vm.powerAllowed(on)
        if (refuse != null) AlertDialog(onDismissRequest = { ask = null }, title = { Text(if (on) "Allumage impossible" else "Extinction impossible") },
            text = { Text(refuse) }, confirmButton = { TextButton(onClick = { ask = null }) { Text("OK") } })
        else ConfirmDialog(if (on) "Allumer le poêle" else "Éteindre le poêle",
            if (on) "Le poêle va démarrer son cycle d'allumage." else "Le poêle va passer en nettoyage final puis s'arrêter.",
            if (on) "Allumer" else "Éteindre", { vm.power(on) }) { ask = null }
    }
}

@Composable
private fun DeviceScreen(vm: StoveViewModel, conn: Conn.Connected) {
    DisposableEffect(Unit) {
        vm.moduleScreenVisible = true; vm.clockScreenVisible = true
        vm.refreshModule()
        onDispose { vm.moduleScreenVisible = false; vm.clockScreenVisible = false }
    }
    val s by vm.state.collectAsStateWithLifecycle()
    val wifi by vm.wifi.collectAsStateWithLifecycle()
    val upd by vm.updater.state.collectAsStateWithLifecycle()
    val m = s.module
    val r = s.regs
    var askClock by remember { mutableStateOf(false) }
    fun i(k: String) = m?.takeIf { it.has(k) }?.optInt(k)
    fun t(k: String) = m?.optString(k)?.takeIf { it.isNotBlank() } ?: "—"
    val stove = runCatching {
        LocalDateTime.of(2000 + Bcd.decode(r[64]!!), Bcd.decode(r[63]!!), Bcd.decode(r[62]!!), Bcd.decode(r[60]!!), Bcd.decode(r[61]!!))
    }.getOrNull()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PAGE, verticalArrangement = GAP) {
        item {
            Section("Horloge du poêle") {
                if (stove == null) Text("Lecture…") else {
                    val day = r[59]?.let { ChronoRules.DAYS.getOrNull(it - 1) } ?: "?"
                    val diff = Duration.between(LocalDateTime.now(), stove).toMinutes()
                    LabeledValue("$day ${stove.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))}",
                        if (kotlin.math.abs(diff) <= 1) "à l'heure" else "écart ${if (diff > 0) "+" else ""}$diff min",
                        if (kotlin.math.abs(diff) <= 1) StatusColors.ok else StatusColors.warning)
                }
                TextButton(onClick = { askClock = true }) { Text("Régler sur l'heure du téléphone") }
            }
        }
        item {
            Section("Module") {
                LabeledValue("Identifiant", conn.found.id)
                LabeledValue("Adresse", conn.found.ip)
                LabeledValue("Trouvé par", conn.found.method)
                LabeledValue("Carte", conn.model?.let { if (it == 8) "KTR-AIR BLDC" else "modèle $it" } ?: "—")
                LabeledValue("Firmware", t("fwVer") + when (m?.optBoolean("fwUpdate")) { true -> " (mise à jour dispo.)"; else -> "" })
                LabeledValue("Wi-Fi", ModuleCodes.WIFI[i("status")] ?: "—")
                LabeledValue("Réseau", t("network"))
                LabeledValue("Signal", i("rssi")?.let { "$it dBm" } ?: "—")
                LabeledValue("Masque / passerelle", "${t("currentMask")} / ${t("currentGw")}")
                LabeledValue("Dernière déconnexion", ModuleCodes.DISCONNECT[i("lastDisconnectReason")] ?: i("lastDisconnectReason")?.toString() ?: "—")
                LabeledValue("Cloud", ModuleCodes.CLOUD[i("client")] ?: "—")
            }
        }
        item {
            Section("Téléphone") {
                val w = wifi
                if (w == null) LabeledValue("Wi-Fi", "non connecté") else {
                    LabeledValue("Adresse", "${w.ip.hostAddress}/${w.prefix}")
                    LabeledValue("VPN", if (w.vpnActive) "actif (contourné)" else "non")
                }
            }
        }
        item {
            Section("Application") {
                LabeledValue("Version", BuildConfig.VERSION_NAME + if (BuildConfig.DEBUG) " (dev)" else "")
                LabeledValue("Mises à jour", when (val f = upd.feed) {
                    FeedStatus.Disabled -> "désactivées"
                    FeedStatus.Unknown, FeedStatus.Checking -> "vérification…"
                    FeedStatus.NoRelease -> "aucune publiée"
                    is FeedStatus.Latest -> if (upd.available != null) "${f.release.versionName} disponible" else "à jour (${f.release.versionName})"
                    is FeedStatus.Unreachable -> "dépôt injoignable"
                })
                Row {
                    TextButton(onClick = vm.updater::check) { Text("Vérifier") }
                    TextButton(onClick = vm::discover) { Text("Relancer la découverte") }
                }
            }
        }
    }
    if (askClock) ConfirmDialog("Régler l'horloge", "L'horloge du poêle sera réglée sur l'heure du téléphone.", "Régler",
        vm::syncClock) { askClock = false }
}

@Composable
private fun DiagScreen(vm: StoveViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    val log by vm.log.collectAsStateWithLifecycle()
    val fmt = DateTimeFormatter.ofPattern("HH:mm:ss")
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PAGE, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item { Text("Registres", style = MaterialTheme.typography.titleMedium) }
        items(s.regs.keys.sorted()) { k ->
            Row(Modifier.fillMaxWidth()) {
                Text("$k", Modifier.weight(0.5f), style = mono)
                Text("${s.regs[k]}", Modifier.weight(0.5f), style = mono, color = if (k in s.changed) StatusColors.running else Color.Unspecified)
                Text(Registers.RAW_NAMES[k] ?: "", Modifier.weight(2f), style = MaterialTheme.typography.bodySmall)
            }
        }
        logItems(log.take(40).map { e ->
            "${e.time.format(fmt)} ${if (e.ok) "ok" else "ERR"} ${e.path.removePrefix("/ajax/")} ${e.params} ${e.ms}ms${if (e.ok) "" else " ${e.info}"}"
        }, mono)
    }
}

private fun LazyListScope.logItems(lines: List<String>, style: androidx.compose.ui.text.TextStyle) {
    item { Text("Requêtes", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp)) }
    items(lines) { Text(it, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}
