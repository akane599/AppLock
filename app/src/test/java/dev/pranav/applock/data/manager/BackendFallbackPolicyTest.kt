package dev.pranav.applock.data.manager

import dev.pranav.applock.data.repository.BackendImplementation
import dev.pranav.applock.data.repository.BackendImplementation.*
import dev.pranav.applock.services.LockSessionState
import org.junit.Assert.*
import org.junit.Test

class BackendFallbackPolicyTest {
    @Test fun routingRequiresPermissionsAndPrefersShizukuThenAccessibility() {
        for (enabled in listOf(false, true)) {
            for (shizuku in listOf(false, true)) {
                for (accessibility in listOf(false, true)) {
                    for (usage in listOf(false, true)) {
                        for (overlay in listOf(false, true)) {
                            val actual = BackendFallbackPolicy.choose(enabled, shizuku, accessibility, usage, overlay)
                            if (!enabled) assertNull(actual)
                            else if (shizuku && overlay) assertEquals(SHIZUKU, actual)
                            else if (accessibility) assertEquals(ACCESSIBILITY, actual)
                            else if (usage && overlay) assertEquals(USAGE_STATS, actual)
                            else assertNull(actual)
                        }
                    }
                }
            }
        }
    }

    @Test fun fallbackAndRecoveryPreserveOnePromptAndSuccessfulGrant() {
        for (fallback in listOf(ACCESSIBILITY, USAGE_STATS)) {
            val state = LockSessionState { 100L }
            val token = state.begin("target")!!
            assertTrue(state.claim(token, "target"))
            assertEquals(SHIZUKU, BackendFallbackPolicy.transition(SHIZUKU, fallback, state.isShowing))
            assertNull(state.begin("target"))
            assertTrue(state.authenticate(token))
            state.release(token)
            assertEquals(fallback, BackendFallbackPolicy.transition(SHIZUKU, fallback, state.isShowing))
            assertEquals(SHIZUKU, BackendFallbackPolicy.transition(fallback, SHIZUKU, state.isShowing))
            state.observeForeground("target", emptySet())
            assertFalse(state.needsAuthentication("target", setOf("target"), 0))
            assertFalse(state.authenticate(token))
        }
    }

    @Test fun recoveryWaitsForFallbackPromptToFinish() {
        assertEquals(ACCESSIBILITY, BackendFallbackPolicy.transition(ACCESSIBILITY, SHIZUKU, true))
        assertEquals(USAGE_STATS, BackendFallbackPolicy.transition(USAGE_STATS, SHIZUKU, true))
        assertEquals(SHIZUKU, BackendFallbackPolicy.transition(USAGE_STATS, SHIZUKU, false))
    }

    @Test fun noPermissionsOrDisabledProtectionCannotReportAnActiveBackend() {
        assertNull(BackendFallbackPolicy.transition(SHIZUKU, null, true))
        assertEquals(ACCESSIBILITY, BackendFallbackPolicy.transition(null, ACCESSIBILITY, true))
    }

    @Test fun manualBackendSelectionOverridesShizukuRuntime() {
        for (preferred in BackendImplementation.entries) {
            val expected = if (preferred == SHIZUKU) USAGE_STATS else preferred
            assertEquals(expected, BackendFallbackPolicy.effective(preferred, USAGE_STATS))
        }
        assertNull(BackendFallbackPolicy.effective(SHIZUKU, null))
    }
    @Test fun aRemovedWindowCannotKeepRoutingPinnedToItsBackend() {
        for (backend in listOf(SHIZUKU, USAGE_STATS)) {
            assertFalse(BackendFallbackPolicy.canRetainChallenge(backend, false, true))
            assertTrue(BackendFallbackPolicy.canRetainChallenge(backend, true, false))
        }
        assertFalse(BackendFallbackPolicy.canRetainChallenge(ACCESSIBILITY, true, false))
        assertTrue(BackendFallbackPolicy.canRetainChallenge(ACCESSIBILITY, false, true))
        assertFalse(BackendFallbackPolicy.canRetainChallenge(null, true, true))
    }

    @Test fun canceledUnavailableChallengeCannotUnlockAndFallbackCanStart() {
        val state = LockSessionState { 100L }
        val stale = state.begin("target")!!
        state.claim(stale, "target")
        if (!BackendFallbackPolicy.canRetainChallenge(USAGE_STATS, false, true)) {
            state.resetUnlocks()
        }
        assertEquals(ACCESSIBILITY, BackendFallbackPolicy.transition(USAGE_STATS, ACCESSIBILITY, state.isShowing))
        assertFalse(state.authenticate(stale))
        assertNotNull(state.begin("target"))
    }

}
