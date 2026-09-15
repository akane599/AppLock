package dev.pranav.applock.services

import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent

object AppLockConstants {
    val KNOWN_RECENTS_CLASSES = setOf(
        "com.android.systemui.recents.RecentsActivity",
        "com.android.quickstep.RecentsActivity",
        "com.android.systemui.recents.RecentsView",
        "com.android.systemui.recents.RecentsPanelView",
    )

    val EXCLUDED_APPS = setOf(
        "com.android.systemui",
        "com.android.intentresolver",
        "com.google.android.permissioncontroller",
        "android.uid.system:1000",
        "android",
        "com.google.android.gms",
        "com.google.android.webview"
    )

    val ACCESSIBILITY_SETTINGS_CLASSES = setOf(
        "com.android.settings.accessibility.AccessibilitySettings",
        "com.android.settings.accessibility.AccessibilityMenuActivity",
        "com.android.settings.accessibility.AccessibilityShortcutActivity",
        "com.android.settings.Settings\$AccessibilitySettingsActivity"
    )

}

fun Context.isDeviceLocked(): Boolean {
    val keyguardManager = getSystemService(KeyguardManager::class.java)
    return keyguardManager?.isKeyguardLocked ?: false
}

@Suppress("DEPRECATION")
fun Context.isServiceRunning(serviceClass: Class<*>): Boolean {
    val manager = getSystemService(ActivityManager::class.java) ?: return false
    return manager.getRunningServices(Int.MAX_VALUE)
        .any { serviceClass.name == it.service.className }
}

object AppLockManager {
    val sessions = LockSessionState(android.os.SystemClock::elapsedRealtime)
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    fun beginLock(packageName: String): Long? = sessions.begin(packageName)?.also { token ->
        // Background activity launches can be refused without throwing. Allow a retry.
        handler.postDelayed({ sessions.expireUnclaimed(token) }, 2_000L)
    }

    private var activityHandoff: Pair<Long, () -> Unit>? = null

    fun prepareActivityHandoff(token: Long, onClaimed: () -> Unit) {
        activityHandoff = token to onClaimed
    }

    fun cancelActivityHandoff(token: Long) {
        if (activityHandoff?.first == token) activityHandoff = null
    }

    fun claimActivityLock(token: Long, packageName: String): Boolean {
        if (!sessions.claim(token, packageName)) return false
        activityHandoff?.takeIf { it.first == token }?.let {
            activityHandoff = null
            it.second()
        }
        return true
    }

    fun clearAppUnlockState(packageName: String) = sessions.clearPackage(packageName)

    fun stopAllOtherServices(context: Context, excludeService: Class<*>) {
        setOf(ShizukuAppLockService::class.java, UsageLockService::class.java)
            .filter { it != excludeService }
            .forEach { context.stopService(Intent(context, it)) }
    }
}
