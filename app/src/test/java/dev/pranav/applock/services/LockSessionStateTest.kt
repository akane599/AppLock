package dev.pranav.applock.services

import org.junit.Assert.*
import org.junit.Test

class LockSessionStateTest {
    private var time = 100L
    private val state = LockSessionState { time }
    private val locked = setOf("a", "b")

    private fun unlock(pkg: String = "a"): Long {
        state.observeForeground(pkg, emptySet())
        val token = state.begin(pkg)!!
        assertTrue(state.claim(token, pkg))
        assertTrue(state.authenticate(token))
        return token
    }

    private fun needsAuth(pkg: String = "a", minutes: Int = 0) =
        state.needsAuthentication(pkg, locked, minutes)

    @Test fun canceledPromptDoesNotUnlockTarget() {
        state.observeForeground("a", emptySet())
        val token = state.begin("a")!!
        state.claim(token, "a")
        state.release(token)
        assertTrue(needsAuth())
        assertFalse(state.authenticate(token))
    }

    @Test fun unclaimedPromptCannotAuthenticate() {
        val token = state.begin("a")!!
        assertFalse(state.authenticate(token))
        assertTrue(needsAuth())
    }

    @Test fun firstLaunchRequiresAuthentication() {
        state.observeForeground("a", emptySet())
        assertTrue(needsAuth())
        assertFalse(needsAuth("unlocked"))
    }

    @Test fun successfulAuthenticationDoesNotPromptAgainOnReturnFromOverlay() {
        val token = unlock()
        state.release(token)
        repeat(10) {
            state.observeForeground("a", emptySet())
            assertFalse(needsAuth())
        }
    }

    @Test fun switchingToAnotherAppRelocksImmediateSession() {
        state.release(unlock())
        state.observeForeground("b", emptySet())
        state.observeForeground("a", emptySet())
        assertTrue(needsAuth())
    }

    @Test fun returningThroughAnyLauncherRelocksImmediateSession() {
        state.release(unlock())
        state.observeForeground("custom.launcher", emptySet())
        state.observeForeground("a", emptySet())
        assertTrue(needsAuth())
    }

    @Test fun timedUnlockSurvivesSwitchUntilDeadline() {
        state.release(unlock())
        state.observeForeground("launcher", emptySet())
        time += 59_999L
        assertFalse(needsAuth(minutes = 1))
        time += 1L
        assertTrue(needsAuth(minutes = 1))
    }

    @Test fun screenOffRevokesUntilScreenOffUnlockAndPendingAuthentication() {
        val token = unlock()
        state.resetUnlocks()
        assertTrue(needsAuth(minutes = 10_000))
        assertFalse(state.authenticate(token))
        assertFalse(state.claim(token, "a"))
    }

    @Test fun duplicateSuccessCannotRefreshUnlockDeadline() {
        val token = unlock()
        time += 30_000L
        assertFalse(state.authenticate(token))
        state.release(token)
        state.observeForeground("launcher", emptySet())
        time += 30_000L
        assertTrue(needsAuth(minutes = 1))
    }

    @Test fun onlyOnePromptCanOwnAuthenticationAtATime() {
        val first = state.begin("a")!!
        assertNull(state.begin("b"))
        assertFalse(state.claim(first, "b"))
        state.release(first)
        assertNotNull(state.begin("b"))
    }

    @Test fun oldActivityDestructionDoesNotReleaseNewPrompt() {
        val first = state.begin("a")!!
        state.release(first)
        val second = state.begin("b")!!
        state.release(first)
        assertTrue(state.isShowing)
        assertFalse(state.authenticate(first))
        assertTrue(state.claim(second, "b"))
    }

    @Test fun blockedActivityLaunchCanRetryButVisiblePromptCannotExpire() {
        val first = state.begin("a")!!
        state.expireUnclaimed(first)
        assertFalse(state.isShowing)
        val second = state.begin("a")!!
        state.claim(second, "a")
        state.expireUnclaimed(second)
        assertTrue(state.isShowing)
    }

    @Test fun triggerExclusionLastsOnlyForThatVisit() {
        val exclusions = setOf("trusted")
        state.observeForeground("trusted", exclusions)
        state.observeForeground("a", exclusions)
        repeat(3) {
            state.observeForeground("a", exclusions)
            assertFalse(needsAuth())
        }
        state.observeForeground("launcher", exclusions)
        state.observeForeground("a", exclusions)
        assertTrue(needsAuth())
    }

    @Test fun relockingAppRevokesPreviousGrant() {
        state.release(unlock())
        state.clearPackage("a")
        assertTrue(needsAuth(minutes = 10_000))
    }

    @Test fun negativeElapsedTimeDoesNotGrantTimedAccess() {
        state.release(unlock())
        state.observeForeground("launcher", emptySet())
        time = 0L
        assertTrue(needsAuth(minutes = 1))
    }
}
