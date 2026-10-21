package com.poted.pilot.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.poted.pilot.log.AppLog

@Composable
fun LogScreen(onBack: () -> Unit) {
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
                        l.contains(" E/") -> Palette.Rec
                        l.contains(" W/") -> Palette.Warn
                        l.contains("/TX:") -> Palette.Accent
                        else -> Color.Unspecified
                    }
                    Text(l, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = c, lineHeight = 14.sp)
                }
            }
        }
    }
}
