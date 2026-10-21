package com.poted.pilot.service

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.poted.pilot.ble.CameraController
import com.poted.pilot.ble.Phase
import com.poted.pilot.log.AppLog
import com.poted.pilot.ui.MainActivity
import com.poted.pilot.ui.formatDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Quick Settings tile: start/stop recording from the notification shade (also on the lock
 * screen). When there is no connection, tapping opens the app.
 */
class RecordTileService : TileService() {
    companion object {
        /** Ask the system to refresh the tile (it only listens while the shade is open). */
        fun refresh(context: Context) {
            try {
                requestListeningState(context, ComponentName(context, RecordTileService::class.java))
            } catch (_: Exception) {
            }
        }
    }

    private var scope: CoroutineScope? = null
    private val controller get() = CameraController.get(this)

    override fun onStartListening() {
        super.onStartListening()
        scope?.cancel()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = s
        combine(controller.connection, controller.status, controller.asleep, controller.busy) { _, _, _, _ -> }
            .onEach { render() }
            .launchIn(s)
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val phase = controller.connection.value.phase
        if (phase == Phase.CONNECTED) {
            AppLog.i("Tile", "record toggled from Quick Settings")
            controller.toggleRecord()
        } else {
            openApp()
        }
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    private fun render() {
        val tile = qsTile ?: return
        val c = controller.connection.value
        val st = controller.status.value
        val recording = st?.isRecording == true
        val (state, subtitle) = when {
            c.phase != Phase.CONNECTED -> Tile.STATE_INACTIVE to c.phase.label
            controller.busy.value -> Tile.STATE_ACTIVE to "…"
            controller.asleep.value -> Tile.STATE_UNAVAILABLE to "Uśpiona"
            recording -> Tile.STATE_ACTIVE to "REC ${formatDuration(st?.recordTimeSec?.toLong())}"
            else -> Tile.STATE_INACTIVE to "Gotowa — dotknij"
        }
        tile.state = state
        tile.label = if (recording) "Stop nagrywania" else "Nagrywanie Osmo"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = subtitle
        tile.updateTile()
    }
}
