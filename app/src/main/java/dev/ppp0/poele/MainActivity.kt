package dev.ppp0.poele

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.ppp0.poele.monitor.MonitorWorker
import dev.ppp0.poele.ui.App

class MainActivity : ComponentActivity() {
    private val vm: StoveViewModel by viewModels()
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = vm.setForeground(true)
            override fun onStop(owner: LifecycleOwner) = vm.setForeground(false)
        })
        // Surveillance en arrière-plan (SPEC §12) : notifications d'alarme, puis contrôle toutes les 15 min.
        if (MonitorWorker.enabled(this) &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        MonitorWorker.schedule(this)
        // Thème Material par défaut, clair, sans couleurs dynamiques (comme NGV BLE Tool).
        setContent { MaterialTheme { App(vm) } }
    }
}
