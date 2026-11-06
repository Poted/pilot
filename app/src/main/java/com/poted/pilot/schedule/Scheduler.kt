package com.poted.pilot.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.poted.pilot.log.AppLog
import com.poted.pilot.service.CameraService
import com.poted.pilot.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * One planned recording, e.g. a timelapse before sunrise. One-shot: it disables itself after
 * firing. [mode] null keeps whatever mode is set on the camera (the only way to use a custom
 * mode, which the protocol cannot select). [durationMin] 0 means "until stopped".
 */
data class Schedule(
    val enabled: Boolean = false,
    val hour: Int = 6,
    val minute: Int = 0,
    val mode: Int? = null,
    val durationMin: Int = 0,
    val startAt: Long = 0,
)

/**
 * Plans the alarms for a [Schedule]:
 *  - prepare, [PREP_LEAD_MS] before the start: make sure the phone is connected to the camera
 *    and warn early if the camera is asleep (it cannot be woken from Android);
 *  - start: optional mode switch, then record;
 *  - stop, after [Schedule.durationMin], when set.
 * setAlarmClock fires on time in Doze and may start the foreground service from the background.
 */
object Scheduler {
    private const val TAG = "Schedule"
    const val PREP_LEAD_MS = 5 * 60_000L

    private val _schedule = MutableStateFlow(Schedule())
    val schedule: StateFlow<Schedule> = _schedule
    private var loaded = false

    fun init(context: Context) {
        if (loaded) return
        loaded = true
        _schedule.value = load(context)
    }

    /** Next time [hour]:[minute] occurs, today or tomorrow. */
    fun nextOccurrence(hour: Int, minute: Int, now: Long = System.currentTimeMillis()): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= now + 30_000) c.add(Calendar.DAY_OF_MONTH, 1)
        return c.timeInMillis
    }

    fun set(context: Context, s: Schedule) {
        init(context)
        val planned = if (s.enabled) s.copy(startAt = nextOccurrence(s.hour, s.minute)) else s.copy(startAt = 0)
        save(context, planned)
        _schedule.value = planned
        cancelAlarms(context, stopToo = false)
        if (planned.enabled) {
            arm(context, planned)
            AppLog.i(TAG, "planned: start ${format(planned.startAt)}, mode=${planned.mode ?: "unchanged"}, duration=${planned.durationMin} min")
        } else {
            AppLog.i(TAG, "schedule disabled")
        }
    }

    /** Called when the start alarm has fired: one-shot, so disable it (the stop alarm stays). */
    fun consumed(context: Context) {
        val s = _schedule.value.copy(enabled = false)
        save(context, s)
        _schedule.value = s
    }

    fun armStop(context: Context, afterMin: Int) {
        val at = System.currentTimeMillis() + afterMin * 60_000L
        alarm(context, at, servicePi(context, CameraService.ACTION_SCHEDULE_STOP, 12))
        AppLog.i(TAG, "stop planned at ${format(at)}")
    }

    /** After a reboot alarms are gone: re-arm a future schedule, report a missed one. */
    fun rearmAfterBoot(context: Context): Boolean {
        init(context)
        val s = _schedule.value
        if (!s.enabled) return true
        return if (s.startAt > System.currentTimeMillis()) {
            arm(context, s)
            AppLog.i(TAG, "re-armed after boot: ${format(s.startAt)}")
            true
        } else {
            consumed(context)
            AppLog.w(TAG, "schedule ${format(s.startAt)} missed (phone was off)")
            false
        }
    }

    private fun arm(context: Context, s: Schedule) {
        val prepAt = s.startAt - PREP_LEAD_MS
        if (prepAt > System.currentTimeMillis()) {
            alarm(context, prepAt, servicePi(context, CameraService.ACTION_SCHEDULE_PREP, 10))
        }
        alarm(context, s.startAt, servicePi(context, CameraService.ACTION_SCHEDULE_START, 11))
    }

    private fun alarm(context: Context, at: Long, pi: PendingIntent) {
        val am = context.getSystemService(AlarmManager::class.java)
        val show = PendingIntent.getActivity(
            context, 13, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
        } catch (e: SecurityException) {
            // Exact alarms not permitted (Android 12 without the permission): best effort.
            AppLog.w(TAG, "exact alarm not permitted, falling back to an inexact one")
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun cancelAlarms(context: Context, stopToo: Boolean) {
        val am = context.getSystemService(AlarmManager::class.java)
        am.cancel(servicePi(context, CameraService.ACTION_SCHEDULE_PREP, 10))
        am.cancel(servicePi(context, CameraService.ACTION_SCHEDULE_START, 11))
        if (stopToo) am.cancel(servicePi(context, CameraService.ACTION_SCHEDULE_STOP, 12))
    }

    fun cancelStop(context: Context) {
        context.getSystemService(AlarmManager::class.java)
            .cancel(servicePi(context, CameraService.ACTION_SCHEDULE_STOP, 12))
    }

    private fun servicePi(context: Context, action: String, code: Int): PendingIntent {
        val i = Intent(context, CameraService::class.java).setAction(action)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(context, code, i, flags)
        } else {
            PendingIntent.getService(context, code, i, flags)
        }
    }

    fun format(at: Long): String = SimpleDateFormat("EEE HH:mm", Locale("pl")).format(Date(at))

    private fun load(context: Context): Schedule {
        val sp = context.getSharedPreferences("schedule", Context.MODE_PRIVATE)
        return Schedule(
            enabled = sp.getBoolean("enabled", false),
            hour = sp.getInt("hour", 6),
            minute = sp.getInt("minute", 0),
            mode = sp.getInt("mode", -1).takeIf { it >= 0 },
            durationMin = sp.getInt("duration", 0),
            startAt = sp.getLong("start_at", 0),
        )
    }

    private fun save(context: Context, s: Schedule) {
        context.getSharedPreferences("schedule", Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", s.enabled)
            .putInt("hour", s.hour)
            .putInt("minute", s.minute)
            .putInt("mode", s.mode ?: -1)
            .putInt("duration", s.durationMin)
            .putLong("start_at", s.startAt)
            .apply()
    }
}

/** Alarms do not survive a reboot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        AppLog.init(context)
        Scheduler.rearmAfterBoot(context)
    }
}
