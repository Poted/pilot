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
 * Home-screen widget: a single start/stop button. Without a usable connection it opens the app.
 * The service pushes updates on every state change ([update]).
 */
class RecordWidget : AppWidgetProvider() {
    companion object {
        private const val ACTION_TOGGLE = "com.poted.pilot.WIDGET_TOGGLE"
        @Volatile private var lastKey: String? = null

        fun update(context: Context, force: Boolean = false) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, RecordWidget::class.java))
            if (ids.isEmpty()) return
            val (views, key) = build(context)
            if (!force && key == lastKey) return
            lastKey = key
            mgr.updateAppWidget(ids, views)
        }

        private fun build(context: Context): Pair<RemoteViews, String> {
            val c = CameraController.get(context)
            val conn = c.connection.value
            val st = c.status.value
            val recording = st?.isRecording == true
            val usable = conn.phase == Phase.CONNECTED && !c.asleep.value

            val (bg, symbol, symbolColor, label) = when {
                conn.phase != Phase.CONNECTED -> Quad(R.drawable.widget_circle_off, "●", 0xFF8B93A1.toInt(), conn.phase.label)
                c.asleep.value -> Quad(R.drawable.widget_circle_sleep, "z", 0xFFFFFFFF.toInt(), "Uśpiona")
                c.busy.value -> Quad(R.drawable.widget_circle_ready, "…", 0xFFFFFFFF.toInt(), "…")
                recording -> Quad(R.drawable.widget_circle_rec, "■", 0xFFFFFFFF.toInt(), formatDuration(st?.recordTimeSec?.toLong()))
                else -> Quad(R.drawable.widget_circle_ready, "●", 0xFFE5484D.toInt(), "Start")
            }
            val views = RemoteViews(context.packageName, R.layout.widget_record).apply {
                setInt(R.id.widget_button, "setBackgroundResource", bg)
                setTextViewText(R.id.widget_symbol, symbol)
                setTextColor(R.id.widget_symbol, symbolColor)
                setTextViewText(R.id.widget_label, label)
            }
            val click = if (usable) {
                PendingIntent.getBroadcast(
                    context, 0,
                    Intent(context, RecordWidget::class.java).setAction(ACTION_TOGGLE),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            } else {
                PendingIntent.getActivity(
                    context, 1,
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }
            views.setOnClickPendingIntent(R.id.widget_root, click)
            return views to "$bg|$symbol|$label|$usable"
        }

        private data class Quad(val bg: Int, val symbol: String, val color: Int, val label: String)
    }

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        update(context, force = true)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_TOGGLE) return
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
