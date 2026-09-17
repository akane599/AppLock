package dev.pranav.applock.features.lockscreen.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BiometricOnlyPolicyTest {
    @Test
    fun unavailableBiometricsRestoreCredentialInput() {
        assertTrue(BiometricOnlyPolicy.shouldRestoreCredentialInput(true, false))
    }

    @Test
    fun availableOrDisabledModeDoesNotChangePreference() {
        assertFalse(BiometricOnlyPolicy.shouldRestoreCredentialInput(true, true))
        assertFalse(BiometricOnlyPolicy.shouldRestoreCredentialInput(false, false))
    }
}
