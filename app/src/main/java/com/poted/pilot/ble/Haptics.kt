package com.poted.pilot.ble

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Vibration feedback when recording starts/stops, useful with the phone in a pocket. */
class Haptics(context: Context) {
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    /** Start: one long pulse. Stop: two short pulses. */
    fun recordChanged(recording: Boolean) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val effect = if (recording) {
            VibrationEffect.createOneShot(350, VibrationEffect.DEFAULT_AMPLITUDE)
        } else {
            VibrationEffect.createWaveform(longArrayOf(0, 120, 120, 120), -1)
        }
        v.vibrate(effect)
    }
}
