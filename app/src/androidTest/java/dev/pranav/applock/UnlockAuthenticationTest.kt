package dev.pranav.applock

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.pranav.applock.data.repository.PreferencesRepository
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class UnlockAuthenticationTest {
    // Isolate credentials and counters from the installed app and other tests.
    private val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        override fun getSharedPreferences(name: String, mode: Int) =
            super.getSharedPreferences("unlock_test_$name", mode)
    }

    @Before fun clearPreferences() {
        listOf("app_lock_prefs", "app_lock_settings").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test fun failuresAcrossMethodsAndRepositoryInstancesShareCooldown() {
        val repository = PreferencesRepository(context)
        repository.setPassword("1234")
        repository.setPattern("1234")
        repository.setBiometricAuthEnabled(true)
        repeat(2) { assertFalse(repository.validatePassword("9999")) }
        repeat(2) { assertFalse(PreferencesRepository(context).validatePattern("9876")) }
        repository.recordAuthenticationFailure()
        assertTrue(repository.cooldownRemainingMillis() > 0L)
        assertFalse(repository.validatePassword("1234"))
        assertFalse(repository.validatePattern("1234"))
        assertFalse(repository.recordBiometricSuccess())
    }

    @Test fun biometricOnlyRejectsStoredCredentialsAndNeedsNoPassword() {
        val repository = PreferencesRepository(context)
        repository.setBiometricOnly(true)
        assertNull(repository.getPassword())
        assertTrue(repository.isBiometricAuthEnabled())
        repository.setBiometricAuthEnabled(false)
        assertTrue(repository.isBiometricAuthEnabled())
        repository.setPassword("1234")
        assertFalse(repository.validatePassword("1234"))
        assertTrue(repository.recordBiometricSuccess())
    }

    @Test fun successResetsSharedFailures() {
        val repository = PreferencesRepository(context)
        repository.setPassword("1234")
        repeat(4) { repository.recordAuthenticationFailure() }
        assertTrue(repository.validatePassword("1234"))
        repeat(4) { repository.recordAuthenticationFailure() }
        assertEquals(0L, repository.cooldownRemainingMillis())
        repository.recordAuthenticationFailure()
        assertTrue(repository.cooldownRemainingMillis() > 0L)
    }
    @Test fun autoUnlockWaitsForFullPinAndCredentialSetupExitsBiometricOnly() {
        val repository = dev.pranav.applock.data.repository.AppLockRepository(context)
        repository.setPassword("123456")
        repository.setAutoUnlockEnabled(true)
        assertFalse(repository.shouldAutoSubmitPin("1234"))
        assertFalse(repository.shouldAutoSubmitPin("12345"))
        assertTrue(repository.shouldAutoSubmitPin("123456"))
        repository.setBiometricOnly(true)
        repository.setPassword("654321")
        assertTrue(repository.isBiometricOnly())
        repository.setLockType(PreferencesRepository.LOCK_TYPE_PIN)
        assertFalse(repository.isBiometricOnly())
        assertTrue(repository.validatePassword("654321"))
    }

    @Test fun recoverySuccessResetsFailuresEvenWhenOptionalBiometricsAreDisabled() {
        val repository = PreferencesRepository(context)
        repeat(4) { repository.recordAuthenticationFailure() }
        assertFalse(repository.isBiometricAuthEnabled())
        assertTrue(repository.recordDeviceCredentialSuccess())
        repeat(4) { repository.recordAuthenticationFailure() }
        assertEquals(0L, repository.cooldownRemainingMillis())
    }

    @Test fun recoveryCannotBypassCooldownOrBiometricOnlyMode() {
        val repository = PreferencesRepository(context)
        repeat(5) { repository.recordAuthenticationFailure() }
        assertFalse(repository.recordDeviceCredentialSuccess())
        assertTrue(repository.cooldownRemainingMillis() > 0L)
        clearPreferences()
        repository.setBiometricOnly(true)
        assertFalse(repository.recordDeviceCredentialSuccess())
        assertTrue(repository.isBiometricOnly())
    }

}
