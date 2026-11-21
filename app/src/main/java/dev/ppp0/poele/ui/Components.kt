package dev.ppp0.poele.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Couleurs d'état fixes, lisibles sur fond clair (comme NGV BLE Tool). */
object StatusColors {
    val ok = Color(0xFF2E7D32)
    val error = Color(0xFFC62828)
    val warning = Color(0xFFE65100)
    val running = Color(0xFF1565C0)
    val off = Color(0xFF757575)
    val dayOff = Color(0xFFE0E0E0)
}

/** Carte titrée regroupant des valeurs liées. */
@Composable
fun Section(title: String?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** Libellé à gauche, valeur à droite. */
@Composable
fun LabeledValue(label: String, value: String, color: Color? = null) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, color = color ?: Color.Unspecified, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
    }
}

/** Ligne réglable : libellé, puis − valeur +. */
@Composable
fun StepRow(label: String, value: String, enabled: Boolean, valueColor: Color = Color.Unspecified, note: String? = null,
            onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        StepButton("−", enabled, onClick = onMinus)
        Text(value, Modifier.width(76.dp), color = valueColor, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center, maxLines = 1)
        StepButton("+", enabled, onClick = onPlus)
    }
}

@Composable
fun StepButton(text: String, enabled: Boolean, size: Dp = 40.dp, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(size)) { Text(text, fontSize = 20.sp) }
}

/** Confirmation avant une action sur le poêle. */
@Composable
fun ConfirmDialog(title: String, text: String, action: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(action) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
