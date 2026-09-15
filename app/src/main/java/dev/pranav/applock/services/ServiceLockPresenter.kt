package dev.pranav.applock.services

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import dev.pranav.applock.core.utils.appLockRepository
import dev.pranav.applock.features.lockscreen.ui.LockScreenOverlayManager

/** Owns a service's lock window; biometric activities take over only after claiming its token. */
class ServiceLockPresenter(
    private val context: Context,
    private val goHome: () -> Unit = {
        context.startActivity(Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })
    }
) {
    private val overlay = LockScreenOverlayManager(context)
    private val handler = Handler(Looper.getMainLooper())
    private var token: Long? = null
    private var lockedPackage = ""

    fun onForegroundChanged(packageName: String) {
        if (token != null && packageName != lockedPackage) dismiss()
    }

    fun show(packageName: String, trigger: String): Boolean {
        token?.let { if (!AppLockManager.sessions.owns(it)) dismiss() }
        val newToken = AppLockManager.beginLock(packageName) ?: return false
        token = newToken
        lockedPackage = packageName
        AppLockManager.sessions.claim(newToken, packageName)
        try {
            overlay.showOverlay(
                lockedPackageName = packageName,
                triggeringPackageName = trigger,
                lockToken = newToken,
                autoBiometric = context !is android.accessibilityservice.AccessibilityService &&
                    context.appLockRepository().isBiometricAuthEnabled(),
                onUnlock = {
                    if (!AppLockManager.sessions.authenticate(newToken)) goHome()
                    AppLockManager.sessions.release(newToken)
                    token = null
                },
                onExit = {
                    goHome()
                    handler.postDelayed({ AppLockManager.sessions.release(newToken) }, 200L)
                    token = null
                },
                onBiometricHandoff = { token = null }
            )
            return true
        } catch (e: Exception) {
            AppLockManager.sessions.release(newToken)
            token = null
            throw e
        }
    }

    fun dismiss() {
        overlay.removeOverlay()
        token?.let(AppLockManager.sessions::release)
        token = null
    }

    fun destroy() {
        dismiss()
        overlay.destroy()
        // Delayed releases check their token, so cannot release a newer prompt.
    }
}
