package dev.ppp0.poele.monitor

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.ppp0.poele.MainActivity
import dev.ppp0.poele.R
import dev.ppp0.poele.net.OffTimer
import dev.ppp0.poele.net.Registers
import dev.ppp0.poele.net.StoveClient
import dev.ppp0.poele.net.StoveOps
import dev.ppp0.poele.net.WifiNet
import java.util.concurrent.TimeUnit

/**
 * Surveillance en arrière-plan (SPEC §12) : toutes les 15 min (minimum de WorkManager), lit l'état
 * et l'alarme, désactive la minuterie d'arrêt échue, notifie l'apparition d'une alarme.
 * Android choisit le moment exact et peut espacer les contrôles en veille.
 */
class MonitorWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = StoveOps.prefs(ctx)
        val host = prefs.getString("host", null) ?: return Result.success() // module jamais trouvé
        val wifi = WifiNet.current(ctx) ?: return Result.success()          // hors Wi-Fi : rien à faire
        return try {
            val ops = StoveOps(StoveClient(host, wifi.network))
            val s = ops.snapshot()
            val stoveNow = ops.stoveNow()
            if (stoveNow != null && ops.cleanupTimer(stoveNow).disabled) {
                Notifier.event(ctx, "Minuterie terminée", "Programme ${OffTimer.NAME} désactivé. État : ${Registers.status(s.status)}")
            }
            val alarm = s.alarm
            val last = prefs.getString(KEY_LAST_ALARM, "")
            if (alarm.isNotBlank() && alarm != last) Notifier.alarm(ctx, alarm)
            if (alarm != last) prefs.edit().putString(KEY_LAST_ALARM, alarm).apply()
            prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
            Result.success()
        } catch (_: Exception) {
            Result.success() // poêle injoignable : on réessaiera au prochain créneau
        }
    }

    companion object {
        private const val WORK = "poele-monitor"
        private const val KEY_LAST_ALARM = "last_alarm"
        const val KEY_LAST_CHECK = "last_check"
        private const val PREF_ENABLED = "monitor_enabled"

        fun enabled(ctx: Context) = StoveOps.prefs(ctx).getBoolean(PREF_ENABLED, true)

        fun setEnabled(ctx: Context, on: Boolean) {
            StoveOps.prefs(ctx).edit().putBoolean(PREF_ENABLED, on).apply()
            if (on) schedule(ctx) else WorkManager.getInstance(ctx).cancelUniqueWork(WORK)
        }

        /** Planifie le contrôle périodique s'il est activé (sans le redémarrer s'il existe déjà). */
        fun schedule(ctx: Context) {
            if (!enabled(ctx)) return
            Notifier.channels(ctx)
            val req = PeriodicWorkRequestBuilder<MonitorWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}

object Notifier {
    private const val CH_ALARM = "alarmes"
    private const val CH_EVENT = "evenements"
    private const val ALARM_ID = 2
    private const val EVENT_ID = 3

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_ALARM, "Alarmes du poêle", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_EVENT, "Minuterie", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun base(ctx: Context, ch: String, title: String, text: String) = NotificationCompat.Builder(ctx, ch)
        .setSmallIcon(R.drawable.ic_launcher_monochrome)
        .setContentTitle(title).setContentText(text).setAutoCancel(true)
        .setContentIntent(PendingIntent.getActivity(ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE))

    private fun notify(ctx: Context, id: Int, b: NotificationCompat.Builder) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            NotificationManagerCompat.from(ctx).notify(id, b.build())
        }
    }

    fun alarm(ctx: Context, text: String) = notify(ctx, ALARM_ID, base(ctx, CH_ALARM, "Alarme du poêle", text))
    fun event(ctx: Context, title: String, text: String) = notify(ctx, EVENT_ID, base(ctx, CH_EVENT, title, text))
}
