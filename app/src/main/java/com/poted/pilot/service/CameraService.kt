package com.poted.pilot.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.poted.pilot.R
import com.poted.pilot.ble.CameraController
import com.poted.pilot.ble.Phase
import com.poted.pilot.log.AppLog
import com.poted.pilot.ui.MainActivity
import com.poted.pilot.ui.formatDuration
import com.poted.pilot.ui.formatFree
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Keeps the BLE session alive while the screen is locked or the app is in the background.
 * The notification is the lock-screen remote: record start/stop and disconnect.
 */
class CameraService : Service() {
    companion object {
        private const val TAG = "Service"
        private const val CHANNEL_ID = "camera_link"
        private const val NOTIFICATION_ID = 1

        const val ACTION_CONNECT = "com.poted.pilot.CONNECT"
        const val ACTION_TOGGLE_RECORD = "com.poted.pilot.TOGGLE_RECORD"
        const val ACTION_DISCONNECT = "com.poted.pilot.DISCONNECT"
        const val ACTION_SET_GPS = "com.poted.pilot.SET_GPS"
        const val EXTRA_ENABLED = "enabled"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_NAME = "name"

        fun connect(context: Context, address: String, name: String?) {
            val i = Intent(context, CameraService::class.java)
                .setAction(ACTION_CONNECT)
                .putExtra(EXTRA_ADDRESS, address)
                .putExtra(EXTRA_NAME, name)
            ContextCompat.startForegroundService(context, i)
        }

        /** Called from the UI (foreground), which Android requires for starting location use. */
        fun setGps(context: Context, enabled: Boolean) {
            val c = CameraController.get(context)
            c.prefs.gpsEnabled = enabled
            val phase = c.connection.value.phase
            if (phase != Phase.IDLE && phase != Phase.FAILED) {
                context.startService(
                    Intent(context, CameraService::class.java).setAction(ACTION_SET_GPS).putExtra(EXTRA_ENABLED, enabled)
                )
            }
        }

        fun hasLocationPermission(context: Context) =
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED

        fun disconnect(context: Context) {
            // If the service isn't running there is nothing to keep alive; just reset state.
            CameraController.get(context).disconnect()
            context.stopService(Intent(context, CameraService::class.java))
        }
    }

    private lateinit var controller: CameraController
    private var scope: CoroutineScope? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastShown: String? = null
    private var isForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        controller = CameraController.get(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must call startForeground promptly on every start.
        goForeground()
        when (intent?.action) {
            ACTION_CONNECT -> {
                val addr = intent.getStringExtra(EXTRA_ADDRESS)
                if (addr != null) controller.connect(addr, intent.getStringExtra(EXTRA_NAME))
            }
            ACTION_TOGGLE_RECORD -> {
                AppLog.i(TAG, "record toggled from notification")
                controller.toggleRecord()
            }
            ACTION_SET_GPS -> applyGps()
            ACTION_DISCONNECT -> {
                AppLog.i(TAG, "disconnect from notification")
                controller.disconnect()
                stopSelfCleanly()
                return START_NOT_STICKY
            }
            null -> {
                // Restarted by the system after process death: resume the last camera.
                val addr = controller.prefs.lastAddress
                AppLog.w(TAG, "service restarted by system, resuming $addr")
                if (addr != null && controller.connection.value.phase == Phase.IDLE) {
                    controller.connect(addr, controller.prefs.lastName)
                }
            }
        }
        startObserving()
        return START_STICKY
    }

    private fun goForeground() {
        if (isForeground) return
        isForeground = true
        if (startForegroundWithTypes(withLocation = wantsGps()) && wantsGps()) controller.gps.start()
        if (wakeLock == null) {
            // Keeps reconnect timers and the coroutine dispatcher running in Doze.
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OsmoPilot:link").apply {
                setReferenceCounted(false)
                acquire()
            }
            AppLog.i(TAG, "foreground service started, wake lock held")
        }
    }

    private fun wantsGps() = controller.prefs.gpsEnabled && hasLocationPermission(this)

    /**
     * Location use needs the `location` FGS type, which Android only grants while the app is in
     * the foreground. Falls back to connectedDevice only (GPS off) when that is refused.
     */
    private fun startForegroundWithTypes(withLocation: Boolean): Boolean {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, n, 0)
            return withLocation
        }
        val base = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (withLocation) {
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, n, base or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                return true
            } catch (e: Exception) {
                AppLog.w(TAG, "location FGS type refused (${e.javaClass.simpleName}); GPS off for this session")
            }
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, base)
        return false
    }

    private fun applyGps() {
        if (wantsGps()) {
            if (startForegroundWithTypes(withLocation = true)) controller.gps.start()
        } else {
            controller.gps.stop()
            startForegroundWithTypes(withLocation = false)
        }
    }

    private fun startObserving() {
        if (scope != null) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = s
        combine(
            listOf(
                controller.connection, controller.status, controller.modeName,
                controller.busy, controller.asleep, controller.notice,
            )
        ) { it.toList() }.distinctUntilChanged().onEach {
            RecordTileService.refresh(this)
            val phase = controller.connection.value.phase
            if (phase == Phase.IDLE || phase == Phase.FAILED) {
                stopSelfCleanly()
            } else {
                refreshNotification()
            }
        }.launchIn(s)
    }

    private fun refreshNotification() {
        val n = buildNotification()
        val key = "" + n.extras.getCharSequence(Notification.EXTRA_TITLE) + "|" + n.extras.getCharSequence(Notification.EXTRA_TEXT) +
            "|" + n.actions?.joinToString { it.title }
        if (key == lastShown) return
        lastShown = key
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
    }

    private fun buildNotification(): Notification {
        val c = controller.connection.value
        val st = controller.status.value
        val recording = st?.isRecording == true
        val asleep = controller.asleep.value
        val name = c.name ?: "Osmo"

        val title = when {
            c.phase == Phase.CONNECTED && asleep -> "$name — uśpiona"
            c.phase == Phase.CONNECTED && recording -> "● REC ${formatDuration(st?.recordTimeSec?.toLong())} — $name"
            c.phase == Phase.CONNECTED -> "$name — gotowa"
            c.phase == Phase.WAITING_APPROVAL -> "$name — potwierdź na kamerze (kod ${c.verifyCode})"
            else -> "$name — ${c.phase.label}"
        }
        val notice = controller.notice.value?.takeIf { System.currentTimeMillis() - it.at < 30_000 }
        val text = if (notice != null) {
            notice.text
        } else if (st != null) {
            val parts = mutableListOf<String>()
            st.batteryPercent?.let { parts += "Bateria $it%" }
            st.remainTimeSec?.let { parts += "zostało ${formatDuration(it)}" }
            st.remainCapacityMb?.let { parts += formatFree(it) }
            parts.joinToString(" · ")
        } else {
            c.message ?: ""
        }

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val toggle = PendingIntent.getService(
            this, 1, Intent(this, CameraService::class.java).setAction(ACTION_TOGGLE_RECORD),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val disconnect = PendingIntent.getService(
            this, 2, Intent(this, CameraService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setColor(if (recording) 0xFFD32F2F.toInt() else 0xFF455A64.toInt())
            .setColorized(recording)

        if (c.phase == Phase.CONNECTED || controller.busy.value) {
            val label = when {
                controller.busy.value -> "…"
                asleep -> "● START (wybudź)"
                recording -> "■ STOP"
                else -> "● START"
            }
            b.addAction(0, label, toggle)
        }
        b.addAction(0, "Rozłącz", disconnect)
        return b.build()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(CHANNEL_ID, "Połączenie z kamerą", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Stałe powiadomienie z przyciskiem nagrywania"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun stopSelfCleanly() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        isForeground = false
        stopSelf()
    }

    override fun onDestroy() {
        AppLog.i(TAG, "service destroyed")
        controller.gps.stop()
        scope?.cancel()
        scope = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }
}
