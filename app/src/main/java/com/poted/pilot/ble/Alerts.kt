package com.poted.pilot.ble

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.poted.pilot.R
import com.poted.pilot.log.AppLog
import com.poted.pilot.protocol.CameraStatus
import com.poted.pilot.ui.MainActivity
import com.poted.pilot.ui.formatDuration

/**
 * Heads-up notifications (with vibration) for things worth noticing while the camera is out of
 * sight: low battery, little recording time left, overheating, recording that stopped without
 * the app asking, and losing the link while recording. Each alert fires once per threshold and
 * re-arms with some hysteresis.
 */
class Alerts(private val context: Context) {
    companion object {
        private const val TAG = "Alert"
        private const val CHANNEL_ID = "camera_alerts"
        private const val ID_BATTERY = 100
        private const val ID_STORAGE = 101
        private const val ID_TEMP = 102
        private const val ID_STOPPED = 103
        private const val ID_LINK = 104

        private val BATTERY_LEVELS = listOf(15, 5)
        private val REMAIN_LEVELS_SEC = listOf(600L, 120L)
        private const val PHOTO_MODE = 0x05
    }

    private val firedBattery = mutableSetOf<Int>()
    private val firedRemain = mutableSetOf<Long>()
    private var lastTemp = 0

    init {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Alerty kamery", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Bateria, miejsce na karcie, temperatura, zatrzymane nagrywanie"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 300, 150, 300, 150, 300)
                }
            )
        }
    }

    /**
     * @param stoppedByApp true when this app sent STOP shortly before the camera stopped.
     * @return a short text for the in-app banner when an alert fired, else null.
     */
    fun onStatus(prev: CameraStatus?, s: CameraStatus, stoppedByApp: Boolean): String? {
        var banner: String? = null

        s.batteryPercent?.let { bat ->
            BATTERY_LEVELS.forEach { level ->
                if (bat <= level && firedBattery.add(level)) {
                    banner = "Bateria kamery: $bat%"
                    post(ID_BATTERY, "Słaba bateria kamery", "Zostało $bat%.")
                }
                if (bat > level + 5) firedBattery.remove(level)
            }
        }

        val remain = s.remainTimeSec
        if (remain != null && remain > 0 && s.cameraMode != PHOTO_MODE) {
            REMAIN_LEVELS_SEC.forEach { level ->
                if (remain <= level && firedRemain.add(level)) {
                    banner = "Na karcie zostało ${formatDuration(remain)} nagrania"
                    post(ID_STORAGE, "Kończy się miejsce na karcie", "Zostało ${formatDuration(remain)} nagrania.")
                }
                if (remain > level + 120) firedRemain.remove(level)
            }
        }

        val temp = s.tempOver ?: 0
        if (temp > lastTemp) {
            val text = when (temp) {
                1 -> "Kamera jest gorąca, nagrywa dalej."
                2 -> "Kamera przegrzana — nie może nagrywać."
                else -> "Kamera przegrzana — wyłącza się."
            }
            banner = text
            post(ID_TEMP, "Temperatura kamery", text)
        }
        lastTemp = temp

        if (prev != null && prev.isRecording && !s.isRecording && !stoppedByApp) {
            val reason = when {
                temp >= 2 -> " (przegrzanie)"
                remain != null && remain in 1..30 -> " (pełna karta)"
                (s.batteryPercent ?: 100) <= 3 -> " (bateria)"
                else -> ""
            }
            val text = "Nagrywanie zatrzymane nie z aplikacji$reason. Czas klipu: ${formatDuration(prev.recordTimeSec?.toLong())}."
            banner = text
            post(ID_STOPPED, "Nagrywanie zatrzymane", text)
        }
        return banner
    }

    fun onLinkLostWhileRecording() {
        post(
            ID_LINK, "Utracono połączenie z kamerą",
            "Kamera prawdopodobnie nagrywa dalej. Aplikacja próbuje połączyć się ponownie.",
        )
    }

    private fun post(id: Int, title: String, text: String) {
        AppLog.w(TAG, "$title: $text")
        val open = PendingIntent.getActivity(
            context, 10, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (e: SecurityException) {
            AppLog.w(TAG, "notifications not permitted")
        }
    }
}
