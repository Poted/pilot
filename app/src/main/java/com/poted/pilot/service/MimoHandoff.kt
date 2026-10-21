package com.poted.pilot.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import com.poted.pilot.ble.CameraController
import com.poted.pilot.ble.Phase
import com.poted.pilot.log.AppLog

/**
 * The camera accepts only one controller at a time, so handing it to DJI Mimo means: drop our
 * connection, then open Mimo (which reconnects to its last camera on its own). DJI publishes no
 * deep link into Mimo's connect screen, so opening the app is as far as we can go.
 */
object Mimo {
    private const val TAG = "Mimo"

    /** Finds DJI Mimo among launcher apps by name instead of hard-coding its package. */
    fun launchIntent(context: Context): Intent? {
        val pm = context.packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val match = pm.queryIntentActivities(query, PackageManager.MATCH_ALL).firstOrNull { ri ->
            val pkg = ri.activityInfo.packageName.lowercase()
            val label = ri.loadLabel(pm).toString().lowercase()
            pkg != context.packageName && ("mimo" in label || "mimo" in pkg) && ("dji" in label || "dji" in pkg)
        } ?: return null
        AppLog.i(TAG, "found ${match.activityInfo.packageName}")
        return pm.getLaunchIntentForPackage(match.activityInfo.packageName)
    }

    fun open(context: Context) {
        val intent = launchIntent(context)
        if (intent != null) {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        AppLog.w(TAG, "DJI Mimo not installed, opening the store")
        val store = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=DJI%20Mimo"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(store)
        } catch (_: Exception) {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=DJI%20Mimo"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** Disconnects (if connected) and opens Mimo. */
    fun handOff(context: Context) {
        val phase = CameraController.get(context).connection.value.phase
        if (phase != Phase.IDLE && phase != Phase.FAILED) {
            AppLog.i(TAG, "handing the camera over to Mimo")
            CameraService.disconnect(context)
        }
        open(context)
    }
}

/**
 * Invisible activity used from the notification and the widget: Android 12+ does not let a
 * service or broadcast receiver start another app's activity, an activity PendingIntent can.
 */
class MimoHandoffActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Mimo.handOff(this)
        finish()
    }
}
