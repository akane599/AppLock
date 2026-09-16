package dev.pranav.applock.data.repository

import android.content.Context
import dev.pranav.applock.data.manager.BackendFallbackPolicy
import dev.pranav.applock.data.manager.BackendServiceManager
import dev.pranav.applock.services.AppLockManager

/**
 * Main repository that coordinates between different specialized repositories and managers.
 * Provides a unified interface for all app lock functionality.
 */
class AppLockRepository(private val context: Context) {

    private val preferencesRepository = PreferencesRepository(context)
    private val lockedAppsRepository = LockedAppsRepository(context)
    private val backendServiceManager = BackendServiceManager()

    fun getEffectiveBackend(): BackendImplementation? =
        BackendFallbackPolicy.effective(getBackendImplementation(), shizukuRuntimeBackend)

    fun setShizukuRuntimeBackend(backend: BackendImplementation?) {
        shizukuRuntimeBackend = backend
    }

    fun getLockedApps(): Set<String> = lockedAppsRepository.getLockedApps()
    fun addLockedApp(packageName: String) {
        lockedAppsRepository.addLockedApp(packageName)
        AppLockManager.clearAppUnlockState(packageName)
    }

    fun addMultipleLockedApps(packageNames: Set<String>) {
        lockedAppsRepository.addMultipleLockedApps(packageNames)
        packageNames.forEach(AppLockManager::clearAppUnlockState)
    }
    fun removeLockedApp(packageName: String) = lockedAppsRepository.removeLockedApp(packageName)
    fun isAppLocked(packageName: String): Boolean = lockedAppsRepository.isAppLocked(packageName)

    fun getTriggerExcludedApps(): Set<String> = lockedAppsRepository.getTriggerExcludedApps()
    fun addTriggerExcludedApp(packageName: String) =
        lockedAppsRepository.addTriggerExcludedApp(packageName)

    fun removeTriggerExcludedApp(packageName: String) =
        lockedAppsRepository.removeTriggerExcludedApp(packageName)

    fun isAppTriggerExcluded(packageName: String): Boolean =
        lockedAppsRepository.isAppTriggerExcluded(packageName)

    fun getAntiUninstallApps(): Set<String> = lockedAppsRepository.getAntiUninstallApps()
    fun addAntiUninstallApp(packageName: String) =
        lockedAppsRepository.addAntiUninstallApp(packageName)

    fun removeAntiUninstallApp(packageName: String) =
        lockedAppsRepository.removeAntiUninstallApp(packageName)

    fun isAppAntiUninstall(packageName: String): Boolean =
        lockedAppsRepository.isAppAntiUninstall(packageName)

    fun getPassword(): String? = preferencesRepository.getPassword()
    fun setPassword(password: String) {
        preferencesRepository.setPassword(password)
        AppLockManager.sessions.resetUnlocks()
    }
    fun validatePassword(inputPassword: String): Boolean =
        preferencesRepository.validatePassword(inputPassword)

    fun getPattern(): String? = preferencesRepository.getPattern()
    fun setPattern(pattern: String) {
        preferencesRepository.setPattern(pattern)
        AppLockManager.sessions.resetUnlocks()
    }
    fun validatePattern(inputPattern: String): Boolean =
        preferencesRepository.validatePattern(inputPattern)

    fun setLockType(lockType: String) {
        preferencesRepository.setLockType(lockType)
        AppLockManager.sessions.resetUnlocks()
    }
    fun getLockType(): String = preferencesRepository.getLockType()

    fun setBiometricAuthEnabled(enabled: Boolean) =
        preferencesRepository.setBiometricAuthEnabled(enabled)

    fun isBiometricAuthEnabled(): Boolean = preferencesRepository.isBiometricAuthEnabled()

    fun setUseMaxBrightness(enabled: Boolean) = preferencesRepository.setUseMaxBrightness(enabled)
    fun shouldUseMaxBrightness(): Boolean = preferencesRepository.shouldUseMaxBrightness()
    fun setDisableHaptics(enabled: Boolean) = preferencesRepository.setDisableHaptics(enabled)
    fun shouldDisableHaptics(): Boolean = preferencesRepository.shouldDisableHaptics()
    fun setShowSystemApps(enabled: Boolean) = preferencesRepository.setShowSystemApps(enabled)
    fun shouldShowSystemApps(): Boolean = preferencesRepository.shouldShowSystemApps()

    fun setAntiUninstallEnabled(enabled: Boolean) =
        preferencesRepository.setAntiUninstallEnabled(enabled)

    fun isAntiUninstallEnabled(): Boolean = preferencesRepository.isAntiUninstallEnabled()
    fun setProtectEnabled(enabled: Boolean) {
        preferencesRepository.setProtectEnabled(enabled)
        AppLockManager.sessions.resetUnlocks()
    }
    fun isProtectEnabled(): Boolean = preferencesRepository.isProtectEnabled()

    fun setUnlockTimeDuration(minutes: Int) = preferencesRepository.setUnlockTimeDuration(minutes)
    fun getUnlockTimeDuration(): Int = preferencesRepository.getUnlockTimeDuration()
    fun setAutoUnlockEnabled(enabled: Boolean) = preferencesRepository.setAutoUnlockEnabled(enabled)
    fun isAutoUnlockEnabled(): Boolean = preferencesRepository.isAutoUnlockEnabled()

    fun setBackendImplementation(backend: BackendImplementation) {
        preferencesRepository.setBackendImplementation(backend)
        shizukuRuntimeBackend = null
        AppLockManager.sessions.resetUnlocks()
    }

    fun getBackendImplementation(): BackendImplementation =
        preferencesRepository.getBackendImplementation()

    fun isShowCommunityLink(): Boolean = preferencesRepository.isShowCommunityLink()
    fun setCommunityLinkShown(shown: Boolean) = preferencesRepository.setCommunityLinkShown(shown)
    fun isShowDonateLink(): Boolean = preferencesRepository.isShowDonateLink(context)
    fun setShowDonateLink(show: Boolean) = preferencesRepository.setShowDonateLink(context, show)

    fun isLoggingEnabled(): Boolean = preferencesRepository.isLoggingEnabled()
    fun setLoggingEnabled(enabled: Boolean) = preferencesRepository.setLoggingEnabled(enabled)

    fun setActiveBackend(backend: BackendImplementation) =
        backendServiceManager.setActiveBackend(backend)

    companion object {
        // Repositories in Settings and ViewModels must observe the same runtime routing.
        @Volatile
        private var shizukuRuntimeBackend: BackendImplementation? = null

        private const val TAG = "AppLockRepository"

        fun shouldStartService(repository: AppLockRepository, serviceClass: Class<*>): Boolean {
            return repository.backendServiceManager.shouldStartService(
                serviceClass,
                repository.getBackendImplementation()
            )
        }
    }
}

enum class BackendImplementation {
    ACCESSIBILITY,
    USAGE_STATS,
    SHIZUKU
}
