package com.poted.pilot

import android.app.Application
import com.poted.pilot.log.AppLog

class PilotApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            AppLog.e("Crash", "uncaught in ${t.name}: ${e.stackTraceToString().take(4000)}")
            previous?.uncaughtException(t, e)
        }
    }
}
