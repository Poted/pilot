package com.poted.pilot.ui

import java.util.Locale

fun formatDuration(sec: Long?): String {
    if (sec == null) return "—"
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s)
}

fun formatFree(mb: Long?): String {
    if (mb == null) return "—"
    return if (mb >= 1024) String.format(Locale.US, "%.1f GB wolne", mb / 1024.0) else "$mb MB wolne"
}
