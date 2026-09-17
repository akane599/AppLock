package dev.pranav.applock.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.os.IBinder
import android.util.Log
import android.view.inputmethod.InputMethodManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.pranav.applock.R
import dev.pranav.applock.core.broadcast.DeviceAdmin
import dev.pranav.applock.core.utils.LogUtils
import dev.pranav.applock.core.utils.hasUsagePermission
import dev.pranav.applock.core.utils.appLockRepository
import dev.pranav.applock.data.manager.BackendFallbackPolicy
import dev.pranav.applock.data.repository.AppLockRepository
import dev.pranav.applock.data.repository.AppLockRepository.Companion.shouldStartService
import dev.pranav.applock.data.repository.BackendImplementation
import dev.pranav.applock.shizuku.ShizukuActivityManager

class ShizukuAppLockService : Service() {
    private val appLockRepository: AppLockRepository by lazy { applicationContext.appLockRepository() }
    private lateinit var lockPresenter: ServiceLockPresenter
    private var shizukuActivityManager: ShizukuActivityManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private val usageTracker by lazy { UsageForegroundTracker(this) }
    private var shizukuAvailable = false
    private var monitorStarted = false
    private var nextRoutingCheck = 0L
    private var usageRetryAfter = 0L
    private var notificationText = R.string.shizuku_unavailable

    private val fallbackMonitor = object : Runnable {
        override fun run() {
            if (!isServiceRunning) return
            if (appLockRepository.getBackendImplementation() != BackendImplementation.SHIZUKU) {
                stopSelf()
                return
            }
            if (SystemClock.elapsedRealtime() >= nextRoutingCheck) {
                reconcileBackend()
                nextRoutingCheck = SystemClock.elapsedRealtime() + 1_000L
            }
            if (appLockRepository.getEffectiveBackend() == BackendImplementation.USAGE_STATS &&
                appLockRepository.isProtectEnabled() && !isDeviceLocked()) {
                try {
                    usageTracker.current()?.let { (pkg, activity) -> handleForeground(pkg, activity) }
                } catch (e: Exception) {
                    Log.e(TAG, "Usage Stats fallback unavailable", e)
                    usageRetryAfter = SystemClock.elapsedRealtime() + 5_000L
                    reconcileBackend()
                }
            }
            if (isServiceRunning) handler.postDelayed(this, 250L)
        }
    }

    private val notificationManager: NotificationManager by lazy {
        getSystemService(NotificationManager::class.java)
    }

    companion object {
        private const val TAG = "ShizukuAppLockService"
        private const val NOTIFICATION_ID = 112
        private const val CHANNEL_ID = "ShizukuAppLockServiceChannel"

        @Volatile
        var isServiceRunning = false
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundService()
        lockPresenter = ServiceLockPresenter(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LogUtils.d(TAG, "ShizukuAppLockService started. Running: $isServiceRunning")

        if (isServiceRunning) return START_STICKY
        isServiceRunning = true

        if (!shouldStartService(appLockRepository, this::class.java)) {
            Log.e(TAG, "Service not needed or Shizuku not ready. Stopping service.")
            isServiceRunning = false
            stopSelf()
            return START_NOT_STICKY
        }

        AppLockManager.stopAllOtherServices(this, this::class.java)

        setupShizukuActivityManager()

        monitorStarted = shizukuActivityManager?.start() == true
        // Keep the supervisor alive even if Shizuku cannot start, so fallback and recovery work.
        handler.post(fallbackMonitor)

        return START_STICKY
    }

    override fun onDestroy() {
        LogUtils.d(TAG, "ShizukuAppLockService killed.")

        isServiceRunning = false
        handler.removeCallbacksAndMessages(null)
        shizukuActivityManager?.stop()
        lockPresenter.destroy()
        // An already-connected accessibility service can continue while Android restarts us.
        if (appLockRepository.getBackendImplementation() == BackendImplementation.SHIZUKU) {
            appLockRepository.setShizukuRuntimeBackend(
                if (appLockRepository.isProtectEnabled() && AppLockAccessibilityService.isConnected) {
                    BackendImplementation.ACCESSIBILITY
                } else null
            )
            AppLockAccessibilityService.refreshBackend()
        }
        notificationManager.cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (shouldStartService(appLockRepository, this::class.java)) {
            try {
                val startIntent = Intent(this, ShizukuAppLockService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(this, startIntent)
                } else {
                    startService(startIntent)
                }
                LogUtils.d(TAG, "Re-started ShizukuAppLockService after task removal")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restart service after task removal", e)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundService() {
        createNotificationChannel()
        val notification = createNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val type = determineForegroundServiceType()
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun determineForegroundServiceType(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val component = ComponentName(this, DeviceAdmin::class.java)

            return if (dpm.isAdminActive(component)) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            }
        }
        return 0
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            "AppLock Service",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        notificationManager.createNotificationChannel(serviceChannel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AppLock")
            .setContentText(getString(notificationText))
            .setOnlyAlertOnce(true)
            .setSmallIcon(R.drawable.baseline_shield_24)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
    }

    private fun setupShizukuActivityManager() {
        shizukuActivityManager = ShizukuActivityManager(
            this,
            appLockRepository,
            onAvailabilityChanged = { available ->
                shizukuAvailable = available
                reconcileBackend()
            },
            onDismissLock = { lockPresenter.dismiss() }
        ) { packageName, className ->
            if (appLockRepository.getEffectiveBackend() == BackendImplementation.SHIZUKU) {
                handleForeground(packageName, className)
            }
        }
    }

    private fun reconcileBackend() {
        if (appLockRepository.getBackendImplementation() != BackendImplementation.SHIZUKU) {
            stopSelf()
            return
        }
        if (!monitorStarted) monitorStarted = shizukuActivityManager?.start() == true
        val enabled = appLockRepository.isProtectEnabled()
        val overlayGranted = Settings.canDrawOverlays(this)
        val accessibilityConnected = AppLockAccessibilityService.isConnected
        val previous = appLockRepository.getEffectiveBackend()
        if (AppLockManager.sessions.isShowing && !BackendFallbackPolicy.canRetainChallenge(
                previous, overlayGranted, accessibilityConnected
            )) {
            // A removed window cannot finish authentication; it must not pin routing forever.
            AppLockManager.sessions.resetUnlocks()
            lockPresenter.dismiss()
            AppLockAccessibilityService.refreshBackend()
        }
        val desired = BackendFallbackPolicy.choose(
            protectionEnabled = enabled,
            shizukuAvailable = shizukuAvailable,
            accessibilityConnected = accessibilityConnected,
            usageAccessGranted = runCatching { hasUsagePermission() }.getOrDefault(false) &&
                SystemClock.elapsedRealtime() >= usageRetryAfter,
            overlayGranted = overlayGranted
        )
        val active = BackendFallbackPolicy.transition(previous, desired, AppLockManager.sessions.isShowing)
        if (active != previous) {
            // A real protection gap invalidates grants, but must not cancel an open challenge.
            if (active == null || previous == null) AppLockManager.sessions.clearGrants()
            appLockRepository.setShizukuRuntimeBackend(active)
            if (!AppLockManager.sessions.isShowing) {
                lockPresenter.dismiss()
                AppLockAccessibilityService.refreshBackend()
            }
            LogUtils.d(TAG, "Runtime backend changed: $previous -> $active (preferred: Shizuku)")
        }
        if (!enabled) {
            lockPresenter.dismiss()
            AppLockAccessibilityService.refreshBackend()
        }
        val text = when {
            !enabled -> R.string.shizuku_paused
            active != desired && AppLockManager.sessions.isShowing -> R.string.shizuku_switch_pending
            active == BackendImplementation.ACCESSIBILITY -> R.string.shizuku_fallback_accessibility
            active == BackendImplementation.USAGE_STATS -> R.string.shizuku_fallback_usage
            active == BackendImplementation.SHIZUKU -> R.string.shizuku_protecting
            else -> R.string.shizuku_no_fallback
        }
        if (text != notificationText) {
            notificationText = text
            notificationManager.notify(NOTIFICATION_ID, createNotification())
        }
    }

    private fun handleForeground(packageName: String, className: String) {
        if (className in AppLockConstants.KNOWN_RECENTS_CLASSES) {
            lockPresenter.onForegroundChanged("system.recents")
            AppLockManager.sessions.observeForeground("system.recents", emptySet())
            return
        }
        if (packageName == this.packageName) {
            if (!AppLockManager.sessions.isShowing) AppLockManager.sessions.observeForeground(packageName, emptySet())
            return
        }
        if (packageName in AppLockConstants.EXCLUDED_APPS) return
        if (getSystemService(InputMethodManager::class.java).enabledInputMethodList.any {
                it.packageName == packageName
            }) return
        lockPresenter.onForegroundChanged(packageName)
        val trigger = AppLockManager.sessions.observeForeground(
            packageName, appLockRepository.getTriggerExcludedApps()
        )
        if (AppLockManager.sessions.needsAuthentication(
                packageName, appLockRepository.getLockedApps(), appLockRepository.getUnlockTimeDuration()
            )) {
            try {
                lockPresenter.show(packageName, trigger)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show authentication", e)
                reconcileBackend()
                notificationManager.notify(NOTIFICATION_ID, createNotification())
            }
        }
    }
}
