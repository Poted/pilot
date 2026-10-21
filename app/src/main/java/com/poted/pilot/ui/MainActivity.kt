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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import com.poted.pilot.log.AppLog

/** Colours shared by the screens. The app is dark-only: it is mostly used outdoors next to a camera. */
object Palette {
    val Background = Color(0xFF0D0E11)
    val Surface = Color(0xFF17191E)
    val SurfaceHigh = Color(0xFF22252C)
    val Outline = Color(0xFF2E323A)
    val Label = Color(0xFF8B93A1)
    val Text = Color(0xFFE9ECF1)
    val Accent = Color(0xFF6EA8FE)
    val Rec = Color(0xFFE5484D)
    val Ready = Color(0xFF3DD68C)
    val Warn = Color(0xFFF5A524)
    val Sleep = Color(0xFF5B6CFF)
}

private val scheme = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Color(0xFF0B1A33),
    secondary = Palette.Accent,
    background = Palette.Background,
    onBackground = Palette.Text,
    surface = Palette.Surface,
    onSurface = Palette.Text,
    surfaceVariant = Palette.SurfaceHigh,
    onSurfaceVariant = Palette.Label,
    outline = Palette.Outline,
    error = Palette.Rec,
)

class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            val denied = res.filterValues { !it }.keys
            if (denied.isNotEmpty()) AppLog.w("UI", "permissions denied: $denied")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(this)
        requestPermissions()
        setContent {
            MaterialTheme(colorScheme = scheme) {
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
            p += Manifest.permission.BLUETOOTH_ADVERTISE
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
