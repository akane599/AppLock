package dev.pranav.applock.data.repository

/** Shared across apps and authentication methods; successful authentication resets escalation. */
object UnlockAttemptPolicy {
    const val MAX_FAILURES = 5

    fun cooldownMillis(level: Int): Long =
        (5L * (1L shl level.coerceIn(0, 4))).coerceAtMost(60L) * 60_000L
}

/** Persisted state uses elapsed time. A reboot restarts an active cooldown conservatively. */
data class UnlockAttemptState(
    val failures: Int = 0,
    val level: Int = 0,
    val duration: Long = 0L,
    val boot: Int = -1,
    val deadline: Long = 0L
) {
    fun remaining(now: Long): Long = (deadline - now).coerceIn(0L, duration)

    fun onBoot(currentBoot: Int, now: Long): UnlockAttemptState =
        if (boot == currentBoot) this else copy(boot = currentBoot, deadline = now + duration)

    fun failed(now: Long): UnlockAttemptState {
        if (remaining(now) > 0L) return this
        if (failures + 1 < UnlockAttemptPolicy.MAX_FAILURES) return copy(failures = failures + 1)
        val cooldown = UnlockAttemptPolicy.cooldownMillis(level)
        return copy(failures = 0, level = (level + 1).coerceAtMost(4),
            duration = cooldown, deadline = now + cooldown)
    }

    fun succeeded(now: Long): UnlockAttemptState =
        if (remaining(now) > 0L) this else UnlockAttemptState(boot = boot)
}
