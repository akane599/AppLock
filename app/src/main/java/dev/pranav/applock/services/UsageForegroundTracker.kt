package dev.pranav.applock.services

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

/** Shared by the standalone Usage Stats backend and Shizuku's in-service fallback. */
class UsageForegroundTracker(context: Context) {
    private val manager = context.getSystemService(UsageStatsManager::class.java)
    private var lastQueryTime = 0L
    private var foreground: Pair<String, String>? = null

    fun reset() {
        lastQueryTime = 0L
        foreground = null
    }

    fun current(): Pair<String, String>? {
        val now = System.currentTimeMillis()
        if (lastQueryTime == 0L || lastQueryTime > now) {
            // Fallback can start long after the target was opened. A short lookback misses it.
            lastQueryTime = now - 24 * 60 * 60 * 1_000L
            foreground = null
        }
        val events = manager.queryEvents(lastQueryTime, now) ?: return null
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED ->
                    foreground = event.packageName?.let { it to (event.className ?: "") }
                UsageEvents.Event.ACTIVITY_PAUSED ->
                    if (foreground?.first == event.packageName && foreground?.second == event.className) {
                        foreground = null
                    }
            }
        }
        lastQueryTime = now
        return foreground
    }
}
