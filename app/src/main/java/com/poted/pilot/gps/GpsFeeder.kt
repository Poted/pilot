package com.poted.pilot.gps

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.poted.pilot.log.AppLog
import com.poted.pilot.protocol.Commands
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

data class GpsState(
    val running: Boolean = false,
    val location: Location? = null,
    val satellitesUsed: Int = 0,
    val satellitesVisible: Int = 0,
    val sentCount: Long = 0,
)

/**
 * Phone GNSS -> 0017 payloads, replacing the NMEA receiver of the ESP32 demo (gps_logic.c).
 * Uses the raw GPS provider (no Play Services) at 1 Hz.
 */
@SuppressLint("MissingPermission")
class GpsFeeder(private val context: Context) {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val main = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(GpsState())
    val state: StateFlow<GpsState> = _state

    private var prevAltitude: Double? = null
    private var prevAltitudeAt = 0L
    @Volatile private var verticalSpeedDown = 0f // m/s, positive = descending

    private val listener = LocationListener { loc -> onLocation(loc) }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
            _state.value = _state.value.copy(satellitesUsed = used, satellitesVisible = status.satelliteCount)
        }
    }

    fun start() {
        if (_state.value.running) return
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            AppLog.w(TAG, "GPS provider disabled in system settings")
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            lm.registerGnssStatusCallback(gnssCallback, main)
            _state.value = GpsState(running = true)
            AppLog.i(TAG, "GPS started")
        } catch (e: Exception) {
            AppLog.e(TAG, "GPS start failed", e)
        }
    }

    fun stop() {
        if (!_state.value.running) return
        lm.removeUpdates(listener)
        lm.unregisterGnssStatusCallback(gnssCallback)
        prevAltitude = null
        _state.value = GpsState(running = false)
        AppLog.i(TAG, "GPS stopped")
    }

    private fun onLocation(loc: Location) {
        val alt = altitudeOf(loc)
        val now = SystemClock.elapsedRealtime()
        val prev = prevAltitude
        if (prev != null && alt != null && now > prevAltitudeAt) {
            val v = ((prev - alt) / ((now - prevAltitudeAt) / 1000.0)).toFloat()
            // Light smoothing; GPS altitude is noisy.
            verticalSpeedDown = (0.7f * verticalSpeedDown + 0.3f * v).coerceIn(-100f, 100f)
        }
        prevAltitude = alt
        prevAltitudeAt = now
        if (_state.value.location == null) AppLog.i(TAG, "first fix: ±${loc.accuracy.roundToInt()} m")
        _state.value = _state.value.copy(location = loc)
    }

    fun markSent() {
        _state.value = _state.value.copy(sentCount = _state.value.sentCount + 1)
    }

    /** Last fix as a 0017 payload, or null when there is no fix or it is older than [maxAgeMs]. */
    fun payload(maxAgeMs: Long = 3000): ByteArray? {
        val st = _state.value
        val loc = st.location ?: return null
        val ageMs = (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000
        if (ageMs > maxAgeMs) return null

        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = loc.time }
        val ymd = utc.get(Calendar.YEAR) * 10000 + (utc.get(Calendar.MONTH) + 1) * 100 + utc.get(Calendar.DAY_OF_MONTH)
        val hms = (utc.get(Calendar.HOUR_OF_DAY) + 8) * 10000 + utc.get(Calendar.MINUTE) * 100 + utc.get(Calendar.SECOND)

        val speed = if (loc.hasSpeed()) loc.speed else 0f
        val bearing = if (loc.hasBearing()) Math.toRadians(loc.bearing.toDouble()) else 0.0
        val north = (speed * cos(bearing) * 100).toFloat()
        val east = (speed * sin(bearing) * 100).toFloat()

        val hAcc = if (loc.hasAccuracy()) (loc.accuracy * 1000).toLong() else 1000L
        val vAcc = if (loc.hasVerticalAccuracy()) (loc.verticalAccuracyMeters * 1000).toLong() else 1000L
        val sAcc = if (loc.hasSpeedAccuracy()) (loc.speedAccuracyMetersPerSecond * 100).toLong() else 10L

        return Commands.gpsPush(
            yearMonthDay = ymd,
            hourMinuteSecond = hms,
            longitudeE7 = (loc.longitude * 1e7).toInt(),
            latitudeE7 = (loc.latitude * 1e7).toInt(),
            heightMm = ((altitudeOf(loc) ?: 0.0) * 1000).toInt(),
            speedNorthCms = north,
            speedEastCms = east,
            speedDownCms = verticalSpeedDown * 100,
            verticalAccuracyMm = vAcc,
            horizontalAccuracyMm = hAcc,
            speedAccuracyCms = sAcc,
            // GnssStatus may lag behind the first fix; the provider also reports it in extras.
            satellites = (st.satellitesUsed.takeIf { it > 0 } ?: loc.extras?.getInt("satellites") ?: 0).toLong(),
        )
    }

    /** Height above mean sea level when available (what an NMEA GGA receiver reports). */
    private fun altitudeOf(loc: Location): Double? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && loc.hasMslAltitude() -> loc.mslAltitudeMeters
        loc.hasAltitude() -> loc.altitude
        else -> null
    }

    companion object {
        private const val TAG = "GPS"
    }
}
