package dev.ppp0.poele.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ppp0.poele.StoveViewModel
import dev.ppp0.poele.net.OffTimer
import dev.ppp0.poele.net.Registers
import java.time.Duration

private fun hm(minutes: Int) = when {
    minutes < 60 -> "$minutes min"
    minutes % 60 == 0 -> "${minutes / 60} h"
    else -> "${minutes / 60} h %02d".format(minutes % 60)
}

/** Minuterie d'arrêt (expérimental, SPEC §11) : « éteindre dans X », via un programme chrono temporaire. */
@Composable
fun TimerSection(vm: StoveViewModel) {
    val timer by vm.timer.collectAsStateWithLifecycle()
    val busy by vm.timerBusy.collectAsStateWithLifecycle()
    val s by vm.state.collectAsStateWithLifecycle()
    var minutes by rememberSaveable { mutableIntStateOf(60) }
    var ask by rememberSaveable { mutableStateOf(false) }
    var askCancel by rememberSaveable { mutableStateOf(false) }
    val st = s.regs[Registers.STATUS]

    Section("Arrêt programmé") {
        val t = timer
        if (t != null) {
            val left = dev.ppp0.poele.net.StoveOps.stoveClock(s.regs)?.let { Duration.between(it, t.stoveDeadline).toMinutes().coerceAtLeast(0) } ?: 0L
            Text("Arrêt à %02d:%02d".format(t.stoveDeadline.hour, t.stoveDeadline.minute), fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleLarge, color = StatusColors.running)
            Text("dans ${hm(left.toInt())} · heure du poêle · programme ${OffTimer.name(t.stoveDeadline)}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { askCancel = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Annuler l'arrêt programmé") }
        } else {
            Text("Éteint le poêle après une durée, compté sur son horloge (arrondi au quart d'heure). Expérimental.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            StepRow("Durée", hm(minutes), !busy,
                onMinus = { minutes = (minutes - 15).coerceAtLeast(OffTimer.MIN_MINUTES) },
                onPlus = { minutes = (minutes + 15).coerceAtMost(OffTimer.MAX_MINUTES) })
            Button(onClick = { ask = true }, enabled = !busy && st != null && st != 0, modifier = Modifier.fillMaxWidth()) {
                Text(if (st == 0) "Poêle éteint" else "Éteindre dans ${hm(minutes)}")
            }
        }
    }
    if (ask) ConfirmDialog("Arrêt programmé",
        "Un programme ${OffTimer.NAME} va éteindre le poêle dans ${hm(minutes)} environ (horloge du poêle, arrondi au quart d'heure). " +
            "Il sera désactivé automatiquement une fois l'échéance passée.",
        "Programmer", { vm.scheduleOff(minutes) }) { ask = false }
    if (askCancel) ConfirmDialog("Annuler l'arrêt programmé", "Le programme ${timer?.let { OffTimer.name(it.stoveDeadline) } ?: OffTimer.NAME} sera désactivé.", "Annuler l'arrêt",
        vm::cancelTimer) { askCancel = false }
}

