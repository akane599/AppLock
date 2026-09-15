package dev.pranav.applock.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.content.getSystemService
import dev.pranav.applock.core.broadcast.DeviceAdmin
import dev.pranav.applock.core.utils.LogUtils
import dev.pranav.applock.core.utils.appLockRepository
import dev.pranav.applock.core.utils.enableAccessibilityServiceWithShizuku
import dev.pranav.applock.data.repository.AppLockRepository
import dev.pranav.applock.data.repository.BackendImplementation
import dev.pranav.applock.services.AppLockConstants.ACCESSIBILITY_SETTINGS_CLASSES
import dev.pranav.applock.services.AppLockConstants.EXCLUDED_APPS
import rikka.shizuku.Shizuku

@SuppressLint("AccessibilityPolicy")
class AppLockAccessibilityService : AccessibilityService() {
    private val appLockRepository: AppLockRepository by lazy { applicationContext.appLockRepository() }
    private val keyboardPackages: List<String> by lazy { getKeyboardPackageNames() }

    private var lockPresenter: ServiceLockPresenter? = null
    private lateinit var mainHandler: Handler

    companion object {
        private const val TAG = "AppLockAccessibility"
        private const val DEVICE_ADMIN_SETTINGS_PACKAGE = "com.android.settings"

        @Volatile
        var isServiceRunning = false
    }

    private val screenStateReceiver = object: android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                    LogUtils.d(TAG, "Screen off detected. Resetting AppLock state.")
                    AppLockManager.sessions.resetUnlocks()
                    lockPresenter?.dismiss()
                } else if (intent?.action == Intent.ACTION_USER_PRESENT && shouldAccessibilityHandleLocking()) {
                    mainHandler.post { checkActiveWindow() }
                }
            } catch (e: Exception) {
                logError("Error in screenStateReceiver", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            isServiceRunning = true
            startPrimaryBackendService()

            mainHandler = Handler(mainLooper)

            lockPresenter = ServiceLockPresenter(this) { performGlobalAction(GLOBAL_ACTION_HOME); Unit }

            val filter = android.content.IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            registerReceiver(screenStateReceiver, filter)
        } catch (e: Exception) {
            logError("Error in onCreate", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            serviceInfo = serviceInfo.apply {
                eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                        AccessibilityEvent.TYPE_WINDOWS_CHANGED
                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                packageNames = null
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }

            Log.d(TAG, "Accessibility service connected")
            if (shouldAccessibilityHandleLocking()) {
                appLockRepository.setActiveBackend(BackendImplementation.ACCESSIBILITY)
            }
        } catch (e: Exception) {
            logError("Error in onServiceConnected", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        try {
            handleAccessibilityEvent(event)
        } catch (e: Exception) {
            logError("Unhandled error in onAccessibilityEvent", e)
        }
    }

    private fun handleAccessibilityEvent(event: AccessibilityEvent) {
        if (appLockRepository.isAntiUninstallEnabled() &&
            event.packageName == DEVICE_ADMIN_SETTINGS_PACKAGE
        ) {
            checkForDeviceAdminDeactivation(event)
        }

        if (!appLockRepository.isProtectEnabled() || !shouldAccessibilityHandleLocking()) {
            lockPresenter?.dismiss()
            return
        }
        if (isDeviceLocked()) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.packageName?.toString() == getSystemDefaultLauncherPackageName()) {
            lockPresenter?.onForegroundChanged(event.packageName.toString())
            AppLockManager.sessions.observeForeground(event.packageName.toString(), emptySet())
            return
        }
        if (event.className?.toString() in AppLockConstants.KNOWN_RECENTS_CLASSES) {
            lockPresenter?.onForegroundChanged("system.recents")
            AppLockManager.sessions.observeForeground("system.recents", emptySet())
            return
        }
        // Content events can originate in background windows. Lock only the active window.
        val packageName = rootInActiveWindow?.packageName?.toString()
            ?: if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                event.packageName?.toString()
            } else null
        packageName ?: return
        if (packageName == this.packageName) {
            if (!AppLockManager.sessions.isShowing) {
                AppLockManager.sessions.observeForeground(packageName, emptySet())
            }
            return
        }
        if (packageName in keyboardPackages || packageName in EXCLUDED_APPS) return
        processPackageLocking(packageName)
    }

    private fun checkActiveWindow() {
        if (!appLockRepository.isProtectEnabled() || isDeviceLocked()) return
        val packageName = rootInActiveWindow?.packageName?.toString() ?: return
        if (packageName != this.packageName && packageName !in keyboardPackages && packageName !in EXCLUDED_APPS) {
            processPackageLocking(packageName)
        }
    }

    private fun processPackageLocking(packageName: String) {
        lockPresenter?.onForegroundChanged(packageName)
        val trigger = AppLockManager.sessions.observeForeground(
            packageName, appLockRepository.getTriggerExcludedApps()
        )
        if (AppLockManager.sessions.needsAuthentication(
                packageName, appLockRepository.getLockedApps(), appLockRepository.getUnlockTimeDuration()
            )) showLockScreenOverlay(packageName, trigger)
    }

    private fun shouldAccessibilityHandleLocking(): Boolean =
        appLockRepository.getBackendImplementation() == BackendImplementation.ACCESSIBILITY

    private fun showLockScreenOverlay(packageName: String, triggeringPackage: String) {
        try {
            lockPresenter?.show(packageName, triggeringPackage)
        } catch (e: Exception) {
            logError("Could not show authentication overlay", e)
        }
    }

    private fun checkForDeviceAdminDeactivation(event: AccessibilityEvent) {
        Log.d(TAG, "Checking for device admin deactivation for event: $event")

        // Check if user is trying to deactivate the accessibility service
        if (isDeactivationAttempt(event)) {
            Log.d(TAG, "Blocking accessibility service deactivation")
            blockDeactivationAttempt()
            return
        }

        // Check if on device admin page and our app is visible
        val isDeviceAdminPage = isDeviceAdminPage(event)
        //val isOurAppVisible = findNodeWithTextContaining(rootNode, "App Lock") != null ||
        //        findNodeWithTextContaining(rootNode, "AppLock") != null

        LogUtils.d(TAG, "User is on device admin page: $isDeviceAdminPage, $event")

        if (!isDeviceAdminPage) {
            return
        }

        blockDeviceAdminDeactivation()
    }

    private fun isDeactivationAttempt(event: AccessibilityEvent): Boolean {
        val isAccessibilitySettings = event.className in ACCESSIBILITY_SETTINGS_CLASSES &&
                event.text.any { it.contains("App Lock") }
        val isSubSettings = event.className == "com.android.settings.SubSettings" &&
                event.text.any { it.contains("App Lock") }
        val isAlertDialog =
            event.packageName == "com.google.android.packageinstaller" && event.className == "android.app.AlertDialog" && event.text.toString()
                .lowercase().contains("App Lock")

        return isAccessibilitySettings || isSubSettings || isAlertDialog
    }

    @SuppressLint("InlinedApi")
    private fun blockDeactivationAttempt() {
        try {
            performGlobalAction(GLOBAL_ACTION_BACK)
            performGlobalAction(GLOBAL_ACTION_HOME)
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        } catch (e: Exception) {
            logError("Error blocking deactivation attempt", e)
        }
    }

    private fun isDeviceAdminPage(event: AccessibilityEvent): Boolean {
        val hasDeviceAdminDescription = event.contentDescription?.toString()?.lowercase()
            ?.contains("Device admin app") == true &&
                event.className == "android.widget.FrameLayout"

        val isAdminConfigClass =
            event.className?.contains("DeviceAdminAdd") == true || event.className?.contains("DeviceAdminSettings") == true

        return hasDeviceAdminDescription || isAdminConfigClass
    }

    @SuppressLint("InlinedApi")
    private fun blockDeviceAdminDeactivation() {
        try {
            val dpm: DevicePolicyManager? = getSystemService()
            val component = ComponentName(this, DeviceAdmin::class.java)

            if (dpm?.isAdminActive(component) == true) {
                performGlobalAction(GLOBAL_ACTION_BACK)
                performGlobalAction(GLOBAL_ACTION_BACK)
                performGlobalAction(GLOBAL_ACTION_HOME)
                Thread.sleep(100)
                performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                Toast.makeText(
                    this,
                    "Disable anti-uninstall from AppLock settings to remove this restriction.",
                    Toast.LENGTH_LONG
                ).show()
                Log.w(TAG, "Blocked device admin deactivation attempt.")
            }
        } catch (e: Exception) {
            logError("Error blocking device admin deactivation", e)
        }
    }

    private fun findNodeWithTextContaining(
        node: AccessibilityNodeInfo,
        text: String
    ): AccessibilityNodeInfo? {
        return try {
            if (node.text?.toString()?.contains(text, ignoreCase = true) == true) {
                return node
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val result = findNodeWithTextContaining(child, text)
                if (result != null) return result
            }
            null
        } catch (e: Exception) {
            logError("Error finding node with text: $text", e)
            null
        }
    }

    private fun getKeyboardPackageNames(): List<String> {
        return try {
            getSystemService<InputMethodManager>()?.enabledInputMethodList?.map { it.packageName }
                ?: emptyList()
        } catch (e: Exception) {
            logError("Error getting keyboard package names", e)
            emptyList()
        }
    }

    private fun getSystemDefaultLauncherPackageName(): String = runCatching {
        packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName.orEmpty()
    }.getOrDefault("")

    private fun startPrimaryBackendService() {
        try {
            when (appLockRepository.getBackendImplementation()) {
                BackendImplementation.SHIZUKU -> {
                    Log.d(TAG, "Starting Shizuku service as primary backend")
                    androidx.core.content.ContextCompat.startForegroundService(this, Intent(this, ShizukuAppLockService::class.java))
                }

                BackendImplementation.USAGE_STATS -> {
                    Log.d(TAG, "Starting Experimental service as primary backend")
                    androidx.core.content.ContextCompat.startForegroundService(this, Intent(this, UsageLockService::class.java))
                }

                else -> {
                    AppLockManager.stopAllOtherServices(this, AppLockAccessibilityService::class.java)
                }
            }
        } catch (e: Exception) {
            logError("Error starting primary backend service", e)
        }
    }

    override fun onInterrupt() {
        try {
            LogUtils.d(TAG, "Accessibility service interrupted")
        } catch (e: Exception) {
            logError("Error in onInterrupt", e)
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        return try {
            Log.d(TAG, "Accessibility service unbound")
            isServiceRunning = false

            if (Shizuku.pingBinder() && appLockRepository.isAntiUninstallEnabled()) {
                enableAccessibilityServiceWithShizuku(ComponentName(packageName, javaClass.name))
            }

            super.onUnbind(intent)
        } catch (e: Exception) {
            logError("Error in onUnbind", e)
            super.onUnbind(intent)
        }
    }

    override fun onDestroy() {
        try {
            super.onDestroy()
            isServiceRunning = false
            LogUtils.d(TAG, "Accessibility service destroyed")

            lockPresenter?.destroy()

            try {
                unregisterReceiver(screenStateReceiver)
            } catch (_: IllegalArgumentException) {
                // Ignore if not registered
                Log.w(TAG, "Receiver not registered or already unregistered")
            }

            mainHandler.removeCallbacksAndMessages(null)
        } catch (e: Exception) {
            logError("Error in onDestroy", e)
        }
    }

    /**
     * Logs errors silently without crashing the service.
     * Only logs to debug level to avoid unnecessary noise in production.
     */
    private fun logError(message: String, throwable: Throwable? = null) {
        Log.e(TAG, message, throwable)
    }
}
