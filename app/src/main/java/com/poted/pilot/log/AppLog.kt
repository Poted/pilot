package com.poted.pilot.log

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * In-memory ring buffer shown on the log screen, mirrored to logcat and to a file in
 * app storage so the log survives the process being killed.
 */
object AppLog {
    private const val TAG = "OsmoPilot"
    private const val MAX_LINES = 5000
    private const val MAX_FILE_BYTES = 2_000_000L

    private val lines = ArrayDeque<String>()
    private val _version = MutableStateFlow(0L)
    /** Bumped on every change so Compose can recompose. */
    val version: StateFlow<Long> = _version

    @Volatile
    var verbose: Boolean = false

    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val io = Executors.newSingleThreadExecutor()
    private var file: File? = null

    fun init(context: Context) {
        if (file != null) return
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        file = File(dir, "pilot.log")
        i("App", "---- start, ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT}) ----")
    }

    fun i(tag: String, msg: String) = add("I", tag, msg)
    fun w(tag: String, msg: String) = add("W", tag, msg)
    fun e(tag: String, msg: String, t: Throwable? = null) =
        add("E", tag, if (t != null) "$msg: ${t.javaClass.simpleName}: ${t.message}" else msg)

    /** Only recorded when verbose logging is on. */
    fun v(tag: String, msg: String) {
        if (verbose) add("V", tag, msg)
    }

    private fun add(level: String, tag: String, msg: String) {
        val line = "${fmt.format(Date())} $level/$tag: $msg"
        when (level) {
            "E" -> Log.e(TAG, "$tag: $msg")
            "W" -> Log.w(TAG, "$tag: $msg")
            else -> Log.i(TAG, "$tag: $msg")
        }
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        _version.value = _version.value + 1
        val f = file ?: return
        io.execute {
            try {
                if (f.length() > MAX_FILE_BYTES) {
                    val old = File(f.parentFile, "pilot.1.log")
                    old.delete()
                    f.renameTo(old)
                }
                f.appendText(line + "\n")
            } catch (_: Exception) {
            }
        }
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

    /** Newest lines up to [maxChars]; clipboard and share intents go through Binder (~1 MB limit). */
    fun text(maxChars: Int = 300_000): String {
        val all = snapshot()
        val out = ArrayDeque<String>()
        var size = 0
        for (i in all.indices.reversed()) {
            size += all[i].length + 1
            if (size > maxChars) break
            out.addFirst(all[i])
        }
        return out.joinToString("\n")
    }

    fun clear() {
        synchronized(lines) { lines.clear() }
        _version.value = _version.value + 1
    }
}
