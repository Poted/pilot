package com.poted.pilot.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.poted.pilot.log.AppLog

class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            val denied = res.filterValues { !it }.keys
            if (denied.isNotEmpty()) AppLog.w("UI", "permissions denied: $denied")
        }

    private var onLocationResult: ((Boolean) -> Unit)? = null
    private val locationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            val granted = res[Manifest.permission.ACCESS_FINE_LOCATION] == true
            if (!granted) AppLog.w("UI", "precise location denied: $res")
            onLocationResult?.invoke(granted)
            onLocationResult = null
        }

    /** Precise location is needed for GPS forwarding; asked only when the user enables it. */
    fun requestLocation(onResult: (Boolean) -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            onResult(true)
            return
        }
        onLocationResult = onResult
        locationLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(this)
        ThemeSetting.load(this)
        requestPermissions()
        setContent {
            val themeMode by ThemeSetting.mode.collectAsState()
            PilotTheme(themeMode) {
                Surface(Modifier.fillMaxSize(), color = Palette.Background) {
                    var screen by remember { mutableStateOf("main") }
                    Box(Modifier.safeDrawingPadding()) {
                        when (screen) {
                            "log" -> {
                                BackHandler { screen = "main" }
                                LogScreen(onBack = { screen = "main" })
                            }
                            else -> MainScreen(onShowLog = { screen = "log" })
                        }
                    }
                }
            }
        }
    }

    private fun requiredPermissions(): Array<String> {
        val p = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            p += Manifest.permission.BLUETOOTH_SCAN
            p += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            p += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) p += Manifest.permission.POST_NOTIFICATIONS
        return p.toTypedArray()
    }

    fun requestPermissions() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    fun hasBtPermissions(): Boolean = requiredPermissions()
        .filter { it != Manifest.permission.POST_NOTIFICATIONS }
        .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
}
