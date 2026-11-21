package dev.ppp0.poele.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ppp0.poele.StoveViewModel
import dev.ppp0.poele.net.ChronoRules
import dev.ppp0.poele.net.Program

private val DAY_LETTERS = listOf("L", "M", "M", "J", "V", "S", "D")

@Composable
fun ChronoScreen(vm: StoveViewModel) {
    LaunchedEffect(Unit) { if (!vm.chronoDirty.value) vm.loadChrono() }
    val chrono by vm.chrono.collectAsStateWithLifecycle()
    val dirty by vm.chronoDirty.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<Int?>(null) }
    var askSave by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf<Int?>(null) }
    val c = chrono ?: run { Text("Lecture des programmes…", Modifier.padding(16.dp)); return }
    fun set(i: Int, p: Program?) = vm.editChrono(c.copy(programs = c.programs.toMutableList().also { it[i] = p }))
    val invalid = c.programs.filterNotNull().any { ChronoRules.validate(it).isNotEmpty() }
    val overlaps = ChronoRules.overlaps(c.programs)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Section(null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Programmation horaire", style = MaterialTheme.typography.titleMedium)
                        Text(if (c.enabled) "Le poêle suit les programmes actifs" else "Programmes ignorés",
                            style = MaterialTheme.typography.bodySmall, color = if (c.enabled) StatusColors.ok else StatusColors.off)
                    }
                    Switch(c.enabled, { vm.editChrono(c.copy(enabled = it)) })
                }
            }
        }
        itemsIndexed(c.programs) { i, p ->
            if (p != null) ProgramCard(p, expanded = open == i, onToggle = { open = if (open == i) null else i },
                onChange = { set(i, it) }, onDelete = { askDelete = i })
        }
        if (overlaps.isNotEmpty()) item {
            Text("Chevauchements : " + overlaps.joinToString(" ; "), color = StatusColors.warning, style = MaterialTheme.typography.bodySmall)
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val free = c.programs.indexOf(null)
                OutlinedButton(onClick = { set(free, Program()); open = free }, enabled = free >= 0) { Text("Ajouter") }
                TextButton(onClick = { open = null; vm.loadChrono() }) { Text("Annuler") }
                Button(onClick = { askSave = true }, enabled = dirty && !invalid, modifier = Modifier.weight(1f)) { Text("Enregistrer") }
            }
            if (dirty) Text(if (invalid) "Des programmes sont à corriger." else "Modifications non enregistrées.",
                style = MaterialTheme.typography.bodySmall, color = if (invalid) StatusColors.error else StatusColors.warning)
        }
    }
    if (askSave) ConfirmDialog("Enregistrer les programmes",
        "Les programmes seront envoyés au poêle." + if (overlaps.isEmpty()) "" else "\n\nChevauchements : " + overlaps.joinToString(" ; "),
        "Enregistrer", { open = null; vm.saveChrono() }) { askSave = false }
    askDelete?.let { i ->
        ConfirmDialog("Supprimer le programme", "« ${c.programs[i]?.name} » sera supprimé du poêle.", "Supprimer",
            { open = null; vm.deleteProgram(i) }) { askDelete = null }
    }
}

/** Pastilles des 7 jours : vertes si actives (grises si le programme est désactivé). Cliquables en édition. */
@Composable
private fun DayDots(days: Int, active: Boolean = true, onToggle: ((Int) -> Unit)? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DAY_LETTERS.forEachIndexed { k, d ->
            val on = days shr k and 1 == 1
            val size = if (onToggle != null) 34.dp else 24.dp
            Box(
                Modifier.size(size).clip(CircleShape).background(if (!on) StatusColors.dayOff else if (active) StatusColors.ok else StatusColors.off)
                    .then(if (onToggle != null) Modifier.clickable { onToggle(k) } else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text(d, color = if (on) Color.White else StatusColors.off, fontSize = if (onToggle != null) 14.sp else 11.sp,
                    fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private fun summary(p: Program): String {
    val start = if (p.startOn) ChronoRules.time(p.sh, p.sq) else "—"
    val stop = if (p.stopOn) ChronoRules.time(p.eh, p.eq) else "—"
    val temp = if (p.temp == ChronoRules.TEMP_MANUAL) "MAN" else "${p.temp} °C"
    return "$start → $stop · $temp · P${p.power}"
}

@Composable
private fun ProgramCard(p: Program, expanded: Boolean, onToggle: () -> Unit, onChange: (Program) -> Unit, onDelete: () -> Unit) {
    val errors = ChronoRules.validate(p)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.name.ifBlank { "(sans nom)" }, style = MaterialTheme.typography.titleSmall,
                        color = if (p.enabled) Color.Unspecified else StatusColors.off)
                    Text(summary(p), style = MaterialTheme.typography.bodyMedium, color = if (p.enabled) Color.Unspecified else StatusColors.off)
                }
                Switch(p.enabled, { onChange(p.copy(enabled = it)) })
            }
            if (!expanded) DayDots(p.days, active = p.enabled)
            if (errors.isNotEmpty()) Text(errors.joinToString(" · "), color = StatusColors.error, style = MaterialTheme.typography.bodySmall)
        }
        if (expanded) {
            HorizontalDivider()
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(p.name, { onChange(p.copy(name = ChronoRules.sanitizeName(it))) }, Modifier.fillMaxWidth(),
                    label = { Text("Nom") }, singleLine = true)
                TimeRow("Démarrage", p.startOn, p.sh, p.sq, { onChange(p.copy(startOn = it)) }) { h, q -> onChange(p.copy(sh = h, sq = q)) }
                TimeRow("Arrêt", p.stopOn, p.eh, p.eq, { onChange(p.copy(stopOn = it)) }) { h, q -> onChange(p.copy(eh = h, eq = q)) }
                StepRow("Température", if (p.temp == ChronoRules.TEMP_MANUAL) "MAN" else "${p.temp} °C", true,
                    onMinus = { onChange(p.copy(temp = (p.temp - 1).coerceIn(ChronoRules.TEMP_MIN, ChronoRules.TEMP_MANUAL))) },
                    onPlus = { onChange(p.copy(temp = (p.temp + 1).coerceIn(ChronoRules.TEMP_MIN, ChronoRules.TEMP_MANUAL))) })
                StepRow("Puissance", "P${p.power}", true,
                    onMinus = { onChange(p.copy(power = (p.power - 1).coerceIn(1, 5))) },
                    onPlus = { onChange(p.copy(power = (p.power + 1).coerceIn(1, 5))) })
                DayDots(p.days) { k -> onChange(p.copy(days = p.days xor (1 shl k))) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDelete) { Text("Supprimer", color = StatusColors.error) }
                    Box(Modifier.weight(1f))
                    TextButton(onClick = onToggle) { Text("Fermer") }
                }
            }
        }
    }
}

/** Heure au quart d'heure (le poêle ne connaît que les quarts). */
@Composable
private fun TimeRow(label: String, on: Boolean, h: Int, q: Int, onEnable: (Boolean) -> Unit, onTime: (Int, Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(on, onEnable)
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        StepButton("−", on, 34.dp) { onTime((h + 23) % 24, q) }
        Text("%02d".format(h), Modifier.width(30.dp), textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        StepButton("+", on, 34.dp) { onTime((h + 1) % 24, q) }
        Text(":", fontSize = 18.sp)
        StepButton("−", on, 34.dp) { onTime(h, (q + 3) % 4) }
        Text("%02d".format(q * 15), Modifier.width(30.dp), textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        StepButton("+", on, 34.dp) { onTime(h, (q + 1) % 4) }
    }
}
