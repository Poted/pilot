package com.poted.pilot.log

/**
 * Caps how many lines of one kind get logged per second. A misbehaving camera can send
 * hundreds of frames per second; logging all of them freezes the log screen.
 */
class RateLimiter(private val maxPerSecond: Int) {
    private var windowStart = 0L
    private var count = 0
    private var suppressed = 0

    /** True when the line may be logged. Reports suppressed lines once the window rolls over. */
    @Synchronized
    fun allow(tag: String, what: String): Boolean {
        val now = System.currentTimeMillis()
        if (now - windowStart >= 1000) {
            if (suppressed > 0) AppLog.w(tag, "$what: $suppressed more line(s) suppressed in the last second")
            windowStart = now
            count = 0
            suppressed = 0
        }
        count++
        if (count <= maxPerSecond) return true
        suppressed++
        return false
    }
}
