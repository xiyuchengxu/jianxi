package dev.rain.laundry.keepalive

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.rain.laundry.ui.LaundryTheme
import dev.rain.laundry.ui.ShizukuKeepAliveSettings
import dev.rain.laundry.ui.ThemeSettings

/** Local, DUMP-protected debug probe. Uses no session, account, order or network fixture. */
class ResidentGuardProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LaundryTheme(ThemeSettings(splashEnabled = false)) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(16.dp).windowInsetsPadding(WindowInsets.safeDrawing)) {
                        Text("Shizuku residency · local debug probe")
                        ShizukuKeepAliveSettings()
                        Button(onClick = { finishAndRemoveTask() }) { Text("Close probe") }
                    }
                }
            }
        }
        applyRequest(intent)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); applyRequest(intent) }
    private fun applyRequest(intent: Intent) {
        if (intent.hasExtra("enabled")) ShizukuKeepAliveManager.get(this)
            .setEnabled(intent.getBooleanExtra("enabled", false))
    }
}
