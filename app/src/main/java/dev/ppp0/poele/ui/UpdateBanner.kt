package dev.ppp0.poele.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import dev.ppp0.poele.update.AppUpdateState
import java.io.File

/** Nouvelle version disponible : téléchargement, vérification, puis installeur Android. */
@Composable
fun UpdateBanner(state: AppUpdateState, onDownload: () -> Unit, modifier: Modifier = Modifier) {
    val release = state.available ?: return
    val context = LocalContext.current
    LaunchedEffect(state.apk) { state.apk?.let { installApk(context, it) } }
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Mise à jour ${release.versionName} disponible", style = MaterialTheme.typography.titleMedium)
            if (release.notes.isNotBlank()) Text(release.notes.take(400), style = MaterialTheme.typography.bodySmall)
            val progress = state.progress
            when {
                progress != null -> {
                    Text("Téléchargement… $progress %")
                    LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                }
                state.apk != null -> {
                    Text("Si l'installation ne s'ouvre pas, autorise Poêle à installer des applications, puis touche Installer.",
                        style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { installApk(context, state.apk) }) { Text("Installer") }
                }
                else -> Button(onClick = onDownload) { Text("Télécharger et installer") }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

/** Ouvre l'installeur Android ; la première fois, Android exige d'autoriser l'appli à installer des applis. */
private fun installApk(context: Context, apk: File) {
    if (!context.packageManager.canRequestPackageInstalls()) {
        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()))
        return
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
    context.startActivity(
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
    )
}
