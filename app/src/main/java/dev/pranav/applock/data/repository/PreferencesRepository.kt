package dev.pranav.applock.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dev.pranav.applock.core.utils.SecurityUtils

/**
 * Repository for managing application preferences and settings.
 * Handles all SharedPreferences operations with proper separation of concerns.
 */
class PreferencesRepository(context: Context) {

    private val appLockPrefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME_APP_LOCK, Context.MODE_PRIVATE)

    private val settingsPrefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME_SETTINGS, Context.MODE_PRIVATE)

    fun isBiometricOnly(): Boolean = settingsPrefs.getBoolean("biometric_only", false)

    fun setBiometricOnly(enabled: Boolean) {
        settingsPrefs.edit(commit = true) {
            putBoolean("biometric_only", enabled)
            if (enabled) putBoolean(KEY_BIOMETRIC_AUTH_ENABLED, true)
        }
    }

    fun passwordLength(): Int = appLockPrefs.getInt("password_length", 0)

    private fun bootCount(): Int = android.provider.Settings.Global.getInt(
        appContext.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1
    )

    private val appContext = context.applicationContext

    private fun attemptState(): UnlockAttemptState {
        val state = UnlockAttemptState(
            failures = appLockPrefs.getInt("unlock_failures", 0),
            level = appLockPrefs.getInt("cooldown_level", 0),
            duration = appLockPrefs.getLong("cooldown_duration", 0L),
            boot = appLockPrefs.getInt("cooldown_boot", -1),
            deadline = appLockPrefs.getLong("cooldown_elapsed_end", 0L)
        )
        val current = state.onBoot(bootCount(), android.os.SystemClock.elapsedRealtime())
        if (current != state) saveAttemptState(current)
        return current
    }

    private fun saveAttemptState(state: UnlockAttemptState) {
        appLockPrefs.edit(commit = true) {
            putInt("unlock_failures", state.failures)
            putInt("cooldown_level", state.level)
            putLong("cooldown_duration", state.duration)
            putInt("cooldown_boot", state.boot)
            putLong("cooldown_elapsed_end", state.deadline)
        }
    }

    fun cooldownRemainingMillis(): Long = synchronized(attemptLock) {
        val state = attemptState()
        val remaining = state.remaining(android.os.SystemClock.elapsedRealtime())
        if (remaining == 0L && state.duration > 0L) {
            saveAttemptState(state.copy(duration = 0L, deadline = 0L))
        }
        remaining
    }

    fun recordAuthenticationFailure() = synchronized(attemptLock) {
        saveAttemptState(attemptState().failed(android.os.SystemClock.elapsedRealtime()))
    }

    fun recordBiometricLockout() {
        repeat(UnlockAttemptPolicy.MAX_FAILURES) { recordAuthenticationFailure() }
    }

    fun recordBiometricSuccess(): Boolean = synchronized(attemptLock) {
        if (!isBiometricAuthEnabled() || cooldownRemainingMillis() > 0L) return@synchronized false
        resetAttempts()
        true
    }

    // Called only after the system confirms the device credential or a strong biometric.
    fun recordDeviceCredentialSuccess(): Boolean = synchronized(attemptLock) {
        if (isBiometricOnly() || cooldownRemainingMillis() > 0L) return@synchronized false
        resetAttempts()
        true
    }

    private fun resetAttempts() {
        saveAttemptState(attemptState().succeeded(android.os.SystemClock.elapsedRealtime()))
    }

    private fun authenticate(check: () -> Boolean): Boolean = synchronized(attemptLock) {
        if (isBiometricOnly() || cooldownRemainingMillis() > 0L) return@synchronized false
        check().also { if (it) resetAttempts() else recordAuthenticationFailure() }
    }

    fun setPassword(password: String) {
        val salt = SecurityUtils.generateSalt()
        val saltedHash = SecurityUtils.hashPassword(password, salt)
        appLockPrefs.edit(commit = true) {
            putString(KEY_PASSWORD, saltedHash)
            putInt("password_length", password.length)
        }
    }

    fun getPassword(): String? {
        return appLockPrefs.getString(KEY_PASSWORD, null)
    }

    fun validatePassword(input: String): Boolean = authenticate { checkPassword(input) }

    private fun checkPassword(input: String): Boolean {
        val stored = getPassword()
        if (stored.isNullOrBlank()) return false

        val sanitizedInput = SecurityUtils.sanitizePassword(input)

        if (SecurityUtils.isSaltedHash(stored)) {
            return SecurityUtils.verifyPassword(sanitizedInput, stored).also { valid ->
                if (valid && passwordLength() == 0) {
                    appLockPrefs.edit(commit = true) { putInt("password_length", sanitizedInput.length) }
                }
            }
        }

        if (stored == input || stored == sanitizedInput) {
            setPassword(sanitizedInput)
            return true
        }

        return false
    }

    fun setPattern(pattern: String) {
        val saltedHash = SecurityUtils.hashPassword(pattern)
        appLockPrefs.edit(commit = true) { putString(KEY_PATTERN, saltedHash) }
    }

    fun getPattern(): String? {
        return appLockPrefs.getString(KEY_PATTERN, null)
    }

    fun validatePattern(inputPattern: String): Boolean = authenticate { checkPattern(inputPattern) }

    private fun checkPattern(inputPattern: String): Boolean {
        val storedPattern = getPattern()
        if (storedPattern.isNullOrBlank() || inputPattern.isBlank()) return false
        if (SecurityUtils.isSaltedHash(storedPattern)) {
            return SecurityUtils.verifyPassword(inputPattern, storedPattern)
        }
        // Migrate legacy plaintext patterns only after successful authentication.
        if (inputPattern != storedPattern) return false
        setPattern(inputPattern)
        return true
    }

    fun setLockType(lockType: String) {
        settingsPrefs.edit(commit = true) { putString(KEY_LOCK_TYPE, lockType) }
    }

    fun getLockType(): String {
        return settingsPrefs.getString(KEY_LOCK_TYPE, LOCK_TYPE_PIN) ?: LOCK_TYPE_PIN
    }

    fun setBiometricAuthEnabled(enabled: Boolean) {
        if (!enabled && isBiometricOnly()) return
        settingsPrefs.edit { putBoolean(KEY_BIOMETRIC_AUTH_ENABLED, enabled) }
    }

    fun isBiometricAuthEnabled(): Boolean {
        return settingsPrefs.getBoolean(KEY_BIOMETRIC_AUTH_ENABLED, false)
    }

    fun setUseMaxBrightness(enabled: Boolean) {
        settingsPrefs.edit { putBoolean(KEY_USE_MAX_BRIGHTNESS, enabled) }
    }

    fun shouldUseMaxBrightness(): Boolean {
        return settingsPrefs.getBoolean(KEY_USE_MAX_BRIGHTNESS, false)
    }

    fun setDisableHaptics(enabled: Boolean) {
        settingsPrefs.edit { putBoolean(KEY_DISABLE_HAPTICS, enabled) }
    }

    fun shouldDisableHaptics(): Boolean {
        return settingsPrefs.getBoolean(KEY_DISABLE_HAPTICS, false)
    }

    fun setShowSystemApps(enabled: Boolean) {
        settingsPrefs.edit { putBoolean(KEY_SHOW_SYSTEM_APPS, enabled) }
    }

    fun shouldShowSystemApps(): Boolean {
        return settingsPrefs.getBoolean(KEY_SHOW_SYSTEM_APPS, false)
    }

    fun setAntiUninstallEnabled(enabled: Boolean) {
        settingsPrefs.edit { putBoolean(KEY_ANTI_UNINSTALL, enabled) }
    }

    fun isAntiUninstallEnabled(): Boolean {
        return settingsPrefs.getBoolean(KEY_ANTI_UNINSTALL, false)
    }

    fun setProtectEnabled(enabled: Boolean) {
        settingsPrefs.edit { putBoolean(KEY_APPLOCK_ENABLED, enabled) }
    }

    fun isProtectEnabled(): Boolean {
        return settingsPrefs.getBoolean(KEY_APPLOCK_ENABLED, DEFAULT_PROTECT_ENABLED)
    }

    fun setUnlockTimeDuration(minutes: Int) {
        settingsPrefs.edit { putInt(KEY_UNLOCK_TIME_DURATION, minutes) }
    }

    fun getUnlockTimeDuration(): Int {
        return settingsPrefs.getInt(KEY_UNLOCK_TIME_DURATION, DEFAULT_UNLOCK_DURATION)
    }

    fun setAutoUnlockEnabled(enabled: Boolean) {
        settingsPrefs.edit { putBoolean(KEY_AUTO_UNLOCK, enabled) }
    }

    fun isAutoUnlockEnabled(): Boolean {
        return settingsPrefs.getBoolean(KEY_AUTO_UNLOCK, false)
    }

    fun setBackendImplementation(backend: BackendImplementation) {
        settingsPrefs.edit { putString(KEY_BACKEND_IMPLEMENTATION, backend.name) }
    }

    fun getBackendImplementation(): BackendImplementation {
        val backend = settingsPrefs.getString(
            KEY_BACKEND_IMPLEMENTATION,
            BackendImplementation.ACCESSIBILITY.name
        )
        return try {
            BackendImplementation.valueOf(backend ?: BackendImplementation.ACCESSIBILITY.name)
        } catch (_: IllegalArgumentException) {
            BackendImplementation.ACCESSIBILITY
        }
    }

    fun isShowCommunityLink(): Boolean {
        return !settingsPrefs.getBoolean(KEY_COMMUNITY_LINK_SHOWN, false)
    }

    fun setCommunityLinkShown(shown: Boolean) {
        settingsPrefs.edit { putBoolean(KEY_COMMUNITY_LINK_SHOWN, shown) }
    }

    fun isShowDonateLink(context: Context): Boolean {
        return settingsPrefs.getBoolean(KEY_SHOW_DONATE_LINK, false)
    }

    fun setShowDonateLink(context: Context, show: Boolean) {
        settingsPrefs.edit { putBoolean(KEY_SHOW_DONATE_LINK, show) }
    }

    fun isLoggingEnabled(): Boolean {
        return settingsPrefs.getBoolean(KEY_LOGGING_ENABLED, false)
    }

    fun setLoggingEnabled(enabled: Boolean) {
        settingsPrefs.edit { putBoolean(KEY_LOGGING_ENABLED, enabled) }
    }

    companion object {
        private val attemptLock = Any()
        private const val PREFS_NAME_APP_LOCK = "app_lock_prefs"
        private const val PREFS_NAME_SETTINGS = "app_lock_settings"

        private const val KEY_PASSWORD = "password"
        private const val KEY_PATTERN = "pattern"
        private const val KEY_BIOMETRIC_AUTH_ENABLED = "use_biometric_auth"
        private const val KEY_DISABLE_HAPTICS = "disable_haptics"
        private const val KEY_USE_MAX_BRIGHTNESS = "use_max_brightness"
        private const val KEY_ANTI_UNINSTALL = "anti_uninstall"
        private const val KEY_UNLOCK_TIME_DURATION = "unlock_time_duration"
        private const val KEY_BACKEND_IMPLEMENTATION = "backend_implementation"
        private const val KEY_COMMUNITY_LINK_SHOWN = "community_link_shown"
        private const val KEY_SHOW_DONATE_LINK = "show_donate_link"
        private const val KEY_LOGGING_ENABLED = "logging_enabled"
        private const val LAST_VERSION_CODE = "last_version_code"
        private const val KEY_APPLOCK_ENABLED = "applock_enabled"
        private const val KEY_AUTO_UNLOCK = "auto_unlock"
        private const val KEY_SHOW_SYSTEM_APPS = "show_system_apps"
        private const val KEY_LOCK_TYPE = "lock_type"

        private const val DEFAULT_PROTECT_ENABLED = true
        private const val DEFAULT_UNLOCK_DURATION = 0

        const val LOCK_TYPE_PIN = "pin"
        const val LOCK_TYPE_PATTERN = "pattern"
        const val LOCK_TYPE_PASSWORD = "password"
    }
}
