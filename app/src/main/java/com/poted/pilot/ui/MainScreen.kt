package com.poted.pilot.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.poted.pilot.ble.CameraController
import com.poted.pilot.ble.Connection
import com.poted.pilot.ble.Phase
import com.poted.pilot.protocol.CameraStatus
import com.poted.pilot.protocol.Commands
import com.poted.pilot.service.CameraService
import kotlinx.coroutines.delay

private val Card = RoundedCornerShape(16.dp)

@Composable
fun MainScreen(onShowLog: () -> Unit) {
    val ctx = LocalContext.current
    val controller = remember { CameraController.get(ctx) }
    val conn by controller.connection.collectAsState()
    val status by controller.status.collectAsState()
    val modeName by controller.modeName.collectAsState()
    val busy by controller.busy.collectAsState()
    val asleep by controller.asleep.collectAsState()
    val notice by controller.notice.collectAsState()

    val connected = conn.phase == Phase.CONNECTED

    // Notices fade out after a while.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    // Errors stay 6 s, hints 4 s; tapping the banner closes it.
    val visibleNotice = notice?.takeIf { now - it.at < if (it.error) 6_000 else 4_000 }

    Column(Modifier.fillMaxSize()) {
        Header(conn, asleep, onShowLog)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            visibleNotice?.let { NoticeBanner(it.text, it.error) { controller.dismissNotice() } }
            if (connected) {
                status?.let { st ->
                    ParameterTiles(st, modeName, asleep)
                    Spacer(Modifier.height(12.dp))
                    ModeSelector(st, asleep, controller)
                } ?: Text(
                    "Czekam na status kamery…",
                    color = Palette.Label, modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                ConnectPanel(conn, controller)
            }
            Spacer(Modifier.height(16.dp))
            GpsPanel(controller)
            Spacer(Modifier.height(8.dp))
            Footer(conn, controller)
        }
        RecordBar(connected, status, asleep, busy) { controller.toggleRecord() }
    }
}

@Composable
private fun Header(conn: Connection, asleep: Boolean, onShowLog: () -> Unit) {
    val (dot, label) = when {
        conn.phase == Phase.CONNECTED && asleep -> Palette.Sleep to "Uśpiona"
        conn.phase == Phase.CONNECTED -> Palette.Ready to "Połączono"
        conn.phase == Phase.FAILED -> Palette.Rec to conn.phase.label
        conn.phase == Phase.IDLE -> Palette.Label to conn.phase.label
        else -> Palette.Warn to conn.phase.label
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                conn.name ?: "Osmo Pilot",
                fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                Spacer(Modifier.width(6.dp))
                Text(label, fontSize = 13.sp, color = Palette.Label)
                conn.message?.takeIf { conn.phase != Phase.CONNECTED }?.let {
                    Text(" · $it", fontSize = 13.sp, color = Palette.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        TextButton(onClick = onShowLog) { Text("Log") }
    }
}

@Composable
private fun NoticeBanner(text: String, error: Boolean, onDismiss: () -> Unit) {
    val color = if (error) Palette.Rec else Palette.Accent
    Box(
        Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(Card)
            .background(color.copy(alpha = 0.15f)).border(1.dp, color.copy(alpha = 0.5f), Card)
            .clickable(onClick = onDismiss)
            .padding(12.dp),
    ) {
        Text(text, color = Palette.Text, fontSize = 14.sp)
    }
}

// ------------------------------------------------------------------ parameters

@Composable
private fun ParameterTiles(st: CameraStatus, modeName: Commands.ModeName?, asleep: Boolean) {
    val ctx = LocalContext.current
    var phoneBattery by remember { mutableIntStateOf(-1) }
    LaunchedEffect(Unit) {
        while (true) {
            phoneBattery = readPhoneBattery(ctx)
            delay(30_000)
        }
    }
    val photoMode = st.cameraMode == 0x05
    val mode = modeName?.name?.takeIf { it.isNotBlank() } ?: Commands.modeName(st.cameraMode)
    val params = modeName?.param?.takeIf { it.isNotBlank() }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile("Tryb", mode.uppercase(), Modifier.weight(1.2f), big = true)
            Tile("Ustawienia", params ?: "—", Modifier.weight(1f), big = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile(if (photoMode) "Rozmiar" else "Rozdzielczość", resolutionLabel(st, params), Modifier.weight(1f))
            if (photoMode) {
                Tile("Seria", st.fpsIdx?.let { if (it > 1) "$it zdjęć" else "pojedyncze" } ?: "—", Modifier.weight(1f))
            } else {
                Tile("Klatki", CameraStatus.fpsName(st.fpsIdx)?.let { "$it fps" } ?: "—", Modifier.weight(1f))
            }
            Tile("Stabilizacja", CameraStatus.eisName(st.eisMode) ?: "—", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val bat = st.batteryPercent
            Tile(
                "Bateria kamery", bat?.let { "$it%" } ?: "—", Modifier.weight(1f),
                valueColor = if (bat != null && bat <= 15) Palette.Rec else Palette.Text,
            )
            Tile("Bateria telefonu", if (phoneBattery >= 0) "$phoneBattery%" else "—", Modifier.weight(1f))
            Tile("Proporcje", CameraStatus.ratioName(st.photoRatio) ?: "—", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile("Wolne na karcie", formatSize(st.remainCapacityMb), Modifier.weight(1f))
            Tile("Zostało nagrania", formatDuration(st.remainTimeSec), Modifier.weight(1f))
            Tile("Zostało zdjęć", st.remainPhotoNum?.toString() ?: "—", Modifier.weight(1f))
        }
        val extra = buildList {
            when (st.tempOver) {
                1 -> add(Triple("Temperatura", "wysoka", Palette.Warn))
                2 -> add(Triple("Temperatura", "za wysoka — nie nagra", Palette.Rec))
                3 -> add(Triple("Temperatura", "przegrzanie", Palette.Rec))
            }
            if (st.cameraMode == 0x02 && (st.timelapseInterval ?: 0) > 0) {
                add(Triple("Interwał", String.format(java.util.Locale.US, "%.1f s", st.timelapseInterval!! / 10.0), Palette.Text))
            }
            if ((st.timelapseDurationSec ?: 0) > 0) add(Triple("Czas timelapse", formatDuration(st.timelapseDurationSec!!.toLong()), Palette.Text))
            if ((st.countdownSec ?: 0) > 0) add(Triple("Odliczanie", "${st.countdownSec} s", Palette.Warn))
            st.loopRecordSec?.takeIf { it != 0 }?.let {
                add(Triple("Pętla", if (it == 65535) "max" else formatDuration(it.toLong()), Palette.Text))
            }
            if ((st.userMode ?: 0) in 1..5) add(Triple("Profil", "Custom ${st.userMode}", Palette.Text))
            if (asleep) add(Triple("Zasilanie", "uśpiona", Palette.Sleep))
        }
        extra.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (l, v, c) -> Tile(l, v, Modifier.weight(1f), valueColor = c) }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Tile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    big: Boolean = false,
    valueColor: Color = Palette.Text,
) {
    Column(
        modifier.clip(Card).background(Palette.Surface).padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(label, fontSize = 12.sp, color = Palette.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text(
            value,
            fontSize = if (big) 20.sp else 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Resolution code, falling back to the 1D06 string ("4K30 HB" -> "4K") for codes the docs don't list. */
private fun resolutionLabel(st: CameraStatus, params: String?): String {
    if (st.cameraMode == 0x05) {
        return when (st.videoResolution) {
            4 -> "L"
            3 -> "M"
            2 -> "S"
            else -> params?.substringBefore(' ') ?: "—"
        }
    }
    val known = CameraStatus.resolutionName(st.videoResolution)
    if (known != null && !known.startsWith("#")) return known
    val fromParams = params?.let { Regex("""^(\d+(?:\.\d+)?K|\d{3,4}[pP])""").find(it)?.value }
    return fromParams ?: known ?: "—"
}

private fun readPhoneBattery(ctx: Context): Int {
    val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return -1
    val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
    return if (level < 0 || scale <= 0) -1 else level * 100 / scale
}

// ------------------------------------------------------------------ modes

@Composable
private fun ModeSelector(st: CameraStatus, asleep: Boolean, controller: CameraController) {
    Text("Tryb", fontSize = 12.sp, color = Palette.Label, modifier = Modifier.padding(bottom = 6.dp))
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Commands.switchableModes.forEach { m ->
            FilterChip(
                selected = st.cameraMode == m,
                onClick = { if (st.cameraMode != m) controller.switchMode(m) },
                enabled = !asleep && !st.isRecording,
                label = { Text(Commands.modeName(m)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Palette.Accent.copy(alpha = 0.2f),
                    selectedLabelColor = Palette.Accent,
                ),
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondaryButton("QS", Modifier.weight(1f), enabled = !asleep && !st.isRecording) { controller.quickSwitch() }
        SecondaryButton("Uśpij", Modifier.weight(1f), enabled = !asleep && !st.isRecording) { controller.sleep() }
    }
}

@Composable
private fun SecondaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Palette.Outline),
    ) { Text(text, color = if (enabled) Palette.Text else Palette.Label) }
}

// ------------------------------------------------------------------ GPS

@Composable
private fun GpsPanel(controller: CameraController) {
    val ctx = LocalContext.current
    val activity = ctx as MainActivity
    val gps by controller.gps.state.collectAsState()
    var enabled by remember { mutableStateOf(controller.prefs.gpsEnabled) }

    Column(Modifier.fillMaxWidth().clip(Card).background(Palette.Surface).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("GPS z telefonu", color = Palette.Text, fontWeight = FontWeight.SemiBold)
                Text(
                    "Lokalizacja i prędkość zapisywane przez kamerę w nagraniu",
                    fontSize = 12.sp, color = Palette.Label,
                )
            }
            Switch(checked = enabled, onCheckedChange = { on ->
                if (on) {
                    activity.requestLocation { granted ->
                        enabled = granted
                        CameraService.setGps(ctx, granted)
                    }
                } else {
                    enabled = false
                    CameraService.setGps(ctx, false)
                }
            })
        }
        if (enabled) {
            val loc = gps.location
            val line = when {
                !gps.running -> "Włączy się po połączeniu z kamerą"
                loc == null -> "Szukam satelitów… (widoczne: ${gps.satellitesVisible})"
                else -> "±${loc.accuracy.toInt()} m · satelity ${gps.satellitesUsed}/${gps.satellitesVisible}" +
                    (if (loc.hasSpeed()) " · ${(loc.speed * 3.6).toInt()} km/h" else "") +
                    " · wysłano ${gps.sentCount}"
            }
            Text(line, fontSize = 12.sp, color = if (loc != null) Palette.Ready else Palette.Warn,
                modifier = Modifier.padding(top = 4.dp))
        }
    }
}

// ------------------------------------------------------------------ connection

@Composable
private fun ConnectPanel(conn: Connection, controller: CameraController) {
    val ctx = LocalContext.current
    val activity = ctx as MainActivity
    val scanning by controller.scanner.scanning.collectAsState()
    val devices by controller.scanner.devices.collectAsState()
    var showAll by remember { mutableStateOf(false) }
    val idle = conn.phase == Phase.IDLE || conn.phase == Phase.FAILED

    if (conn.phase == Phase.WAITING_APPROVAL) {
        Column(
            Modifier.fillMaxWidth().clip(Card).background(Palette.Surface).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Zaakceptuj połączenie na ekranie kamery", color = Palette.Text, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text("${conn.verifyCode}", fontSize = 36.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(12.dp))
    }

    if (!idle) {
        SecondaryButton("Anuluj", Modifier.fillMaxWidth()) { CameraService.disconnect(ctx) }
        return
    }

    val last = controller.prefs.lastAddress
    if (last != null) {
        Button(
            onClick = {
                if (activity.hasBtPermissions()) CameraService.connect(ctx, last, controller.prefs.lastName)
                else activity.requestPermissions()
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(12.dp),
        ) { Text("Połącz z ${controller.prefs.lastName ?: last}") }
        Spacer(Modifier.height(8.dp))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondaryButton(if (scanning) "Zatrzymaj skan" else "Szukaj kamer", Modifier.weight(1f)) {
            if (activity.hasBtPermissions()) {
                if (scanning) controller.scanner.stop() else controller.scanner.start()
            } else activity.requestPermissions()
        }
    }

    if (scanning || devices.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Znalezione urządzenia", color = Palette.Label, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Checkbox(checked = showAll, onCheckedChange = { showAll = it })
            Text("wszystkie", fontSize = 13.sp, color = Palette.Label)
        }
        val shown = if (showAll) devices else devices.filter { it.isDjiCamera }
        Column(Modifier.fillMaxWidth().clip(Card).background(Palette.Surface)) {
            if (shown.isEmpty()) {
                Text(
                    if (scanning) "Szukam… upewnij się, że kamera jest włączona." else "Brak kamer DJI w zasięgu.",
                    color = Palette.Label, fontSize = 14.sp, modifier = Modifier.padding(16.dp),
                )
            }
            shown.forEachIndexed { i, d ->
                if (i > 0) HorizontalDivider(color = Palette.Outline)
                Row(
                    Modifier.fillMaxWidth().clickable { CameraService.connect(ctx, d.address, d.name) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            d.name ?: "(bez nazwy)", color = Palette.Text,
                            fontWeight = if (d.isDjiCamera) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        Text(
                            d.address + if (d.isDjiCamera) " · DJI" else "",
                            fontSize = 12.sp, color = Palette.Label,
                        )
                    }
                    Text("${d.rssi} dBm", fontSize = 12.sp, color = Palette.Label)
                }
            }
        }
    }
}

@SuppressLint("BatteryLife")
@Composable
private fun Footer(conn: Connection, controller: CameraController) {
    val ctx = LocalContext.current
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (conn.phase == Phase.CONNECTED) {
            TextButton(onClick = { CameraService.disconnect(ctx) }) { Text("Rozłącz", color = Palette.Label) }
        }
        if (!pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
            TextButton(onClick = {
                ctx.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                )
            }) { Text("Wyłącz optymalizację baterii (zalecane)", fontSize = 13.sp) }
        }
        if ((conn.phase == Phase.IDLE || conn.phase == Phase.FAILED) && controller.prefs.lastAddress != null) {
            TextButton(onClick = { controller.forgetPairing() }) {
                Text("Zapomnij parowanie", fontSize = 12.sp, color = Palette.Label)
            }
        }
        conn.version?.let { Text(it, fontSize = 11.sp, color = Palette.Label, textAlign = TextAlign.Center) }
        Spacer(Modifier.height(8.dp))
    }
}

// ------------------------------------------------------------------ record

@Composable
private fun RecordBar(connected: Boolean, st: CameraStatus?, asleep: Boolean, busy: Boolean, onClick: () -> Unit) {
    val recording = st?.isRecording == true
    val enabled = connected && !busy && !asleep
    val ring = when {
        !connected -> Palette.Outline
        asleep -> Palette.Sleep
        recording -> Palette.Rec
        else -> Palette.Text
    }
    val caption = when {
        busy -> "Czekam na kamerę…"
        !connected -> "Brak połączenia"
        asleep -> "Uśpiona — włącz kamerę jej przyciskiem"
        recording -> "Nagrywanie"
        st?.cameraMode == 0x05 -> "Zrób zdjęcie"
        else -> "Gotowa"
    }
    Column(
        Modifier.fillMaxWidth().background(Palette.Surface).padding(top = 12.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (recording) formatDuration(st?.recordTimeSec?.toLong()) else caption,
            fontSize = if (recording) 28.sp else 14.sp,
            fontWeight = if (recording) FontWeight.Bold else FontWeight.Normal,
            fontFamily = if (recording) FontFamily.Monospace else FontFamily.Default,
            color = if (recording) Palette.Rec else Palette.Label,
        )
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier.size(104.dp).clip(CircleShape).border(5.dp, ring, CircleShape)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            val inner = when {
                !connected -> Palette.Outline
                busy -> Palette.Rec.copy(alpha = 0.4f)
                asleep -> Palette.Sleep
                else -> Palette.Rec
            }
            if (recording && !busy) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(8.dp)).background(inner))
            } else {
                Box(Modifier.size(78.dp).clip(CircleShape).background(inner))
            }
        }
    }
}

private fun formatSize(mb: Long?): String {
    if (mb == null) return "—"
    return if (mb >= 1024) String.format(java.util.Locale.US, "%.1f GB", mb / 1024.0) else "$mb MB"
}
