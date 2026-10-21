package com.poted.pilot.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.poted.pilot.R
import com.poted.pilot.ble.CameraController
import com.poted.pilot.ble.Phase
import com.poted.pilot.log.AppLog
import com.poted.pilot.ui.MainActivity
import com.poted.pilot.ui.formatDuration

/**
 * Home-screen widgets. [RecordWidget] (1x1) is a single start/stop button; [PanelWidget] (4x1)
 * adds connect/disconnect, hand-over to Mimo and opening the app. The service pushes updates
 * on every state change via [Widgets.update].
 */
object Widgets {
    const val ACTION_TOGGLE = "com.poted.pilot.WIDGET_TOGGLE"
    @Volatile private var lastKey: String? = null

    private data class Look(val bg: Int, val symbol: String, val color: Int, val label: String)

    fun update(context: Context, force: Boolean = false) {
        val mgr = AppWidgetManager.getInstance(context)
        val smallIds = mgr.getAppWidgetIds(ComponentName(context, RecordWidget::class.java))
        val panelIds = mgr.getAppWidgetIds(ComponentName(context, PanelWidget::class.java))
        if (smallIds.isEmpty() && panelIds.isEmpty()) return
        val c = CameraController.get(context)
        val look = look(c)
        val conn = c.connection.value
        val detail = detail(c)
        val key = "$look|$detail|${conn.phase}"
        if (!force && key == lastKey) return
        lastKey = key
        if (smallIds.isNotEmpty()) mgr.updateAppWidget(smallIds, small(context, c, look))
        if (panelIds.isNotEmpty()) mgr.updateAppWidget(panelIds, panel(context, c, look, detail))
    }

    private fun look(c: CameraController): Look {
        val conn = c.connection.value
        val st = c.status.value
        return when {
            conn.phase != Phase.CONNECTED -> Look(R.drawable.widget_circle_off, "●", 0xFF8B93A1.toInt(), conn.phase.label)
            c.asleep.value -> Look(R.drawable.widget_circle_sleep, "z", 0xFFFFFFFF.toInt(), "Uśpiona")
            c.busy.value -> Look(R.drawable.widget_circle_ready, "…", 0xFFFFFFFF.toInt(), "…")
            st?.isRecording == true ->
                Look(R.drawable.widget_circle_rec, "■", 0xFFFFFFFF.toInt(), formatDuration(st.recordTimeSec?.toLong()))
            else -> Look(R.drawable.widget_circle_ready, "●", 0xFFE5484D.toInt(), "Gotowa")
        }
    }

    private fun detail(c: CameraController): String {
        val name = c.connection.value.name ?: c.prefs.lastName ?: "Osmo Pilot"
        val bat = c.status.value?.batteryPercent?.let { " · $it%" } ?: ""
        return name + bat
    }

    private fun recordable(c: CameraController) = c.connection.value.phase == Phase.CONNECTED && !c.asleep.value

    private fun openApp(context: Context) = PendingIntent.getActivity(
        context, 1,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun toggle(context: Context) = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, RecordWidget::class.java).setAction(ACTION_TOGGLE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun small(context: Context, c: CameraController, look: Look) =
        RemoteViews(context.packageName, R.layout.widget_record).apply {
            setLook(look)
            val shortLabel = if (look.label == "Gotowa") "Start" else look.label
            setTextViewText(R.id.widget_label, shortLabel)
            setOnClickPendingIntent(R.id.widget_root, if (recordable(c)) toggle(context) else openApp(context))
        }

    private fun panel(context: Context, c: CameraController, look: Look, detail: String) =
        RemoteViews(context.packageName, R.layout.widget_panel).apply {
            setLook(look)
            setTextViewText(R.id.widget_label, look.label)
            setTextViewText(R.id.widget_detail, detail)
            setOnClickPendingIntent(R.id.widget_button, if (recordable(c)) toggle(context) else openApp(context))
            setOnClickPendingIntent(R.id.widget_root, openApp(context))
            setOnClickPendingIntent(R.id.widget_app, openApp(context))
            setOnClickPendingIntent(
                R.id.widget_mimo,
                PendingIntent.getActivity(
                    context, 3,
                    Intent(context, MimoHandoffActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )

            val phase = c.connection.value.phase
            val idle = phase == Phase.IDLE || phase == Phase.FAILED
            val address = c.prefs.lastAddress
            setTextViewText(R.id.widget_connect, if (idle) "Połącz" else "Rozłącz")
            val connectIntent = when {
                // A widget tap counts as user interaction, so starting the FGS from here is allowed.
                idle && address != null -> PendingIntent.getForegroundService(
                    context, 4,
                    Intent(context, CameraService::class.java)
                        .setAction(CameraService.ACTION_CONNECT)
                        .putExtra(CameraService.EXTRA_ADDRESS, address)
                        .putExtra(CameraService.EXTRA_NAME, c.prefs.lastName),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                idle -> openApp(context) // never connected yet: pick the camera in the app
                else -> PendingIntent.getService(
                    context, 5,
                    Intent(context, CameraService::class.java).setAction(CameraService.ACTION_DISCONNECT),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }
            setOnClickPendingIntent(R.id.widget_connect, connectIntent)
        }

    private fun RemoteViews.setLook(look: Look) {
        setInt(R.id.widget_button, "setBackgroundResource", look.bg)
        setTextViewText(R.id.widget_symbol, look.symbol)
        setTextColor(R.id.widget_symbol, look.color)
    }

    fun onToggle(context: Context) {
        val c = CameraController.get(context)
        if (c.isConnected) {
            AppLog.i("Widget", "record toggled from widget")
            c.toggleRecord()
        } else {
            // Process was restarted or the link dropped since the widget was drawn.
            update(context, force = true)
        }
    }
}

/** 1x1: start/stop. */
class RecordWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) = Widgets.update(context, force = true)

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == Widgets.ACTION_TOGGLE) Widgets.onToggle(context)
    }
}

/** 4x1: start/stop, connect/disconnect, Mimo, app. */
class PanelWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) = Widgets.update(context, force = true)
}
