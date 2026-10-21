package com.poted.pilot.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.poted.pilot.ble.CameraController
import com.poted.pilot.ble.Phase
import com.poted.pilot.log.AppLog
import com.poted.pilot.protocol.CameraStatus
import com.poted.pilot.protocol.Commands
import com.poted.pilot.service.CameraService

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
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
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

private val RecRed = Color(0xFFD32F2F)
private val ReadyGreen = Color(0xFF2E7D32)

@SuppressLint("BatteryLife")
@Composable
private fun MainScreen(onShowLog: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as MainActivity
    val controller = remember { CameraController.get(ctx) }
    val conn by controller.connection.collectAsState()
    val status by controller.status.collectAsState()
    val modeName by controller.modeName.collectAsState()
    val busy by controller.busy.collectAsState()
    val scanning by controller.scanner.scanning.collectAsState()
    val devices by controller.scanner.devices.collectAsState()
    var showAll by remember { mutableStateOf(false) }
    var modeMenu by remember { mutableStateOf(false) }

    val connected = conn.phase == Phase.CONNECTED
    val idle = conn.phase == Phase.IDLE || conn.phase == Phase.FAILED
    val recording = status?.isRecording == true

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Osmo Pilot", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onShowLog) { Text("Log") }
        }

        // Connection card
        Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Column(Modifier.padding(16.dp)) {
                val color = when (conn.phase) {
                    Phase.CONNECTED -> ReadyGreen
                    Phase.FAILED -> RecRed
                    else -> MaterialTheme.colorScheme.onSurface
                }
                Text(conn.phase.label, color = color, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                val who = listOfNotNull(conn.name, conn.address).joinToString(" · ")
                if (who.isNotEmpty()) Text(who, style = MaterialTheme.typography.bodySmall)
                conn.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (conn.phase == Phase.WAITING_APPROVAL) {
                    Text(
                        "Kod: ${conn.verifyCode}",
                        fontSize = 28.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                conn.version?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }

        // Big record button
        Spacer(Modifier.height(8.dp))
        val btnColor = when {
            !connected -> Color.Gray
            recording -> RecRed
            else -> ReadyGreen
        }
        Box(
            Modifier
                .size(220.dp)
                .clip(CircleShape)
                .background(btnColor)
                .clickable(enabled = connected && !busy) { controller.toggleRecord() },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    when {
                        busy -> "…"
                        recording -> "STOP"
                        else -> "START"
                    },
                    color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold,
                )
                if (recording) {
                    Text(formatDuration(status?.recordTimeSec?.toLong()), color = Color.White, fontSize = 24.sp)
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        // Status
        status?.let { st -> StatusCard(st, modeName) }

        // Mode / QS / power
        if (connected) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { controller.quickSwitch() }, modifier = Modifier.weight(1f)) { Text("QS") }
                Box(Modifier.weight(1f)) {
                    OutlinedButton(onClick = { modeMenu = true }, modifier = Modifier.fillMaxWidth()) { Text("Tryb") }
                    DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                        Commands.switchableModes.forEach { m ->
                            DropdownMenuItem(text = { Text(Commands.modeName(m)) }, onClick = {
                                modeMenu = false
                                controller.switchMode(m)
                            })
                        }
                    }
                }
                OutlinedButton(onClick = { controller.sleep() }, modifier = Modifier.weight(1f)) { Text("Uśpij") }
            }
        }

        // Connection controls
        if (idle) {
            val last = controller.prefs.lastAddress
            if (last != null) {
                Button(
                    onClick = {
                        if (activity.hasBtPermissions()) CameraService.connect(ctx, last, controller.prefs.lastName)
                        else activity.requestPermissions()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Połącz: ${controller.prefs.lastName ?: last}") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        if (activity.hasBtPermissions()) {
                            if (scanning) controller.scanner.stop() else controller.scanner.start()
                        } else activity.requestPermissions()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(if (scanning) "Zatrzymaj skan" else "Szukaj kamer") }
                if (last != null) {
                    OutlinedButton(onClick = { controller.wake() }, modifier = Modifier.weight(1f)) { Text("Wybudź") }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { CameraService.disconnect(ctx) }, modifier = Modifier.weight(1f)) {
                    Text("Rozłącz")
                }
                OutlinedButton(onClick = { controller.wake() }, modifier = Modifier.weight(1f)) { Text("Wybudź") }
            }
        }

        // Scan results
        if (idle && (scanning || devices.isNotEmpty())) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Znalezione urządzenia", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Checkbox(checked = showAll, onCheckedChange = { showAll = it })
                Text("wszystkie", style = MaterialTheme.typography.bodySmall)
            }
            val shown = if (showAll) devices else devices.filter { it.isDjiCamera }
            if (shown.isEmpty()) {
                Text(
                    if (scanning) "Szukam… Upewnij się, że kamera jest włączona." else "Brak kamer DJI w zasięgu.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            shown.forEach { d ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { CameraService.connect(ctx, d.address, d.name) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(d.name ?: "(bez nazwy)", fontWeight = if (d.isDjiCamera) FontWeight.Bold else FontWeight.Normal)
                        Text("${d.address}${if (d.isDjiCamera) " · DJI" else ""}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("${d.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
        }

        Spacer(Modifier.height(16.dp))
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
            TextButton(onClick = {
                ctx.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                )
            }) { Text("Wyłącz optymalizację baterii (zalecane)") }
        }
        if (idle && controller.prefs.lastAddress != null) {
            TextButton(onClick = { controller.forgetPairing() }) {
                Text("Zapomnij parowanie (wymuś potwierdzenie na kamerze)", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun StatusCard(st: CameraStatus, modeName: Commands.ModeName?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            val mode = modeName?.name?.takeIf { it.isNotBlank() } ?: Commands.modeName(st.cameraMode)
            StatRow("Tryb", mode)
            val params = modeName?.param?.takeIf { it.isNotBlank() }
                ?: listOfNotNull(CameraStatus.resolutionName(st.videoResolution), CameraStatus.fpsName(st.fpsIdx)?.let { "$it fps" })
                    .joinToString(" ")
            if (params.isNotBlank()) StatRow("Parametry", params)
            StatRow("Stan", st.statusName)
            StatRow("Bateria", st.batteryPercent?.let { "$it%" } ?: "—")
            StatRow("Czas nagrania", formatDuration(st.recordTimeSec?.toLong()))
            StatRow("Pozostało nagrania", formatDuration(st.remainTimeSec))
            StatRow("Karta", formatFree(st.remainCapacityMb))
            if (st.powerMode == 3) StatRow("Zasilanie", "uśpiona")
            when (st.tempOver) {
                1 -> StatRow("Temperatura", "ostrzeżenie", RecRed)
                2 -> StatRow("Temperatura", "za wysoka — nie można nagrywać", RecRed)
                3 -> StatRow("Temperatura", "przegrzanie — wyłączanie", RecRed)
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String, color: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, modifier = Modifier.width(150.dp), style = MaterialTheme.typography.bodyMedium)
        Text(value, color = color, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LogScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val version by AppLog.version.collectAsState()
    var verbose by remember { mutableStateOf(AppLog.verbose) }
    val lines = remember(version) { AppLog.snapshot() }
    val listState = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }

    LaunchedEffect(lines.size, follow) {
        if (follow && lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Wróć") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                val cm = ctx.getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("Osmo Pilot log", AppLog.text()))
                Toast.makeText(ctx, "Skopiowano ${lines.size} linii", Toast.LENGTH_SHORT).show()
            }) { Text("Kopiuj") }
            TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Osmo Pilot log")
                    .putExtra(Intent.EXTRA_TEXT, AppLog.text())
                ctx.startActivity(Intent.createChooser(send, "Udostępnij log"))
            }) { Text("Udostępnij") }
            TextButton(onClick = { AppLog.clear() }) { Text("Wyczyść") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Pełny log RX", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Switch(checked = verbose, onCheckedChange = { verbose = it; AppLog.verbose = it })
            Spacer(Modifier.width(16.dp))
            Text("Przewijaj", style = MaterialTheme.typography.bodySmall)
            Checkbox(checked = follow, onCheckedChange = { follow = it })
        }
        HorizontalDivider()
        SelectionContainer {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                items(lines) { l ->
                    val c = when {
                        l.contains(" E/") -> RecRed
                        l.contains(" W/") -> Color(0xFFF57C00)
                        l.contains("/TX:") -> Color(0xFF1976D2)
                        else -> Color.Unspecified
                    }
                    Text(l, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = c, lineHeight = 14.sp)
                }
            }
        }
    }
}
