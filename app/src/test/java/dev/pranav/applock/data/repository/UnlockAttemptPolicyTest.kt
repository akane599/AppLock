package dev.pranav.applock.data.repository

import org.junit.Assert.*
import org.junit.Test

class UnlockAttemptPolicyTest {
    @Test fun fifthFailureStartsFiveMinuteCooldown() {
        var state = UnlockAttemptState(boot = 1)
        repeat(4) { state = state.failed(100L) }
        assertEquals(0L, state.remaining(100L))
        state = state.failed(100L)
        assertEquals(300_000L, state.remaining(100L))
        assertEquals(0L, state.remaining(300_100L))
    }

    @Test fun attemptsDuringCooldownCannotResetOrExtendIt() {
        var state = UnlockAttemptState(boot = 1)
        repeat(5) { state = state.failed(100L) }
        assertEquals(state, state.failed(200L))
        assertEquals(state, state.succeeded(200L))
    }

    @Test fun repeatedLockoutsEscalateAndCapAtOneHour() {
        var state = UnlockAttemptState(boot = 1)
        var now = 100L
        listOf(5L, 10L, 20L, 40L, 60L, 60L).forEach { minutes ->
            repeat(5) { state = state.failed(now) }
            assertEquals(minutes * 60_000L, state.remaining(now))
            now += minutes * 60_000L
        }
    }

    @Test fun successfulAuthenticationResetsFailureCountAndEscalation() {
        var state = UnlockAttemptState(boot = 1)
        repeat(5) { state = state.failed(0L) }
        state = state.succeeded(300_000L)
        assertEquals(0, state.level)
        assertEquals(0, state.failures)
        repeat(5) { state = state.failed(300_000L) }
        assertEquals(300_000L, state.remaining(300_000L))
    }

    @Test fun processRecreationKeepsDeadlineAndFailures() {
        val restored = UnlockAttemptState(failures = 3, level = 2, boot = 9)
        assertEquals(restored, restored.onBoot(9, 500L))
        val locked = restored.failed(500L).failed(500L)
        assertEquals(1_200_000L, locked.remaining(500L))
        assertEquals(locked, locked.onBoot(9, 600L))
    }

    @Test fun rebootRestartsActiveCooldownWithoutResettingEscalation() {
        val state = UnlockAttemptState(level = 2, duration = 600_000L, boot = 1, deadline = 900_000L)
        val rebooted = state.onBoot(2, 100L)
        assertEquals(600_000L, rebooted.remaining(100L))
        assertEquals(2, rebooted.level)
        assertEquals(rebooted, rebooted.onBoot(2, 200L))
    }
}
