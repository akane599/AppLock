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
import android.os.IBinder
import android.util.Log
import android.view.inputmethod.InputMethodManager
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import dev.pranav.applock.R
import dev.pranav.applock.core.broadcast.DeviceAdmin
import dev.pranav.applock.core.utils.LogUtils
import dev.pranav.applock.core.utils.appLockRepository
import dev.pranav.applock.core.utils.hasUsagePermission
import dev.pranav.applock.data.repository.AppLockRepository
import dev.pranav.applock.data.repository.AppLockRepository.Companion.shouldStartService
import dev.pranav.applock.data.repository.BackendImplementation
import android.os.Handler
import android.os.Looper

class UsageLockService: Service() {
    private val TAG = "UsageLockService"
    private val NOTIFICATION_ID = 113
    private val CHANNEL_ID = "UsageLockServiceChannel"

    companion object {
        @Volatile
        var isServiceRunning = false
    }

    private val appLockRepository: AppLockRepository by lazy { applicationContext.appLockRepository() }
    private val foregroundTracker by lazy { UsageForegroundTracker(this) }
    private val notificationManager: NotificationManager by lazy { getSystemService()!! }

    private val lockPresenter by lazy { ServiceLockPresenter(this) }
    private val handler = Handler(Looper.getMainLooper())
    private var pauseMonitoring = false
    private val monitor = object : Runnable {
        override fun run() {
            if (!isServiceRunning) return
            safeMonitorForegroundApp()
            handler.postDelayed(this, 250L)
        }
    }

    private val screenStateReceiver = object: android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                LogUtils.d(
                    TAG,
                    "Screen off detected in Usage Stats fallback. Resetting AppLock state."
                )
                AppLockManager.sessions.resetUnlocks()
                lockPresenter.dismiss()
                pauseMonitoring = true
            } else if (intent?.action == Intent.ACTION_USER_PRESENT) {
                pauseMonitoring = false
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!shouldStartService(appLockRepository, this::class.java) || !hasUsagePermission()) {
            Log.e(TAG, "Permissions missing or service not needed. Stopping service.")
            stopSelf()
            return START_NOT_STICKY
        }

        if (isServiceRunning) return START_STICKY
        isServiceRunning = true
        appLockRepository.setActiveBackend(BackendImplementation.USAGE_STATS)
        AppLockManager.stopAllOtherServices(this, this::class.java)

        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenStateReceiver, filter)

        startMonitoringTimer()
        startForegroundService()

        return START_STICKY
    }

    override fun onDestroy() {
        isServiceRunning = false
        handler.removeCallbacksAndMessages(null)
        lockPresenter.destroy()
        LogUtils.d(TAG, "Service destroyed")

        try {
            unregisterReceiver(screenStateReceiver)
        } catch (_: IllegalArgumentException) {
            Log.w(TAG, "Receiver not registered or already unregistered")
        }

        notificationManager.cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (shouldStartService(appLockRepository, this::class.java)) {
            try {
                val startIntent = Intent(this, UsageLockService::class.java)
                ContextCompat.startForegroundService(this, startIntent)
                Log.d(TAG, "Re-started ExperimentalAppLockService after task removal")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restart service after task removal", e)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startMonitoringTimer() {
        handler.removeCallbacks(monitor)
        handler.post(monitor)
    }

    private fun safeMonitorForegroundApp() {
        try {
            if (appLockRepository.getBackendImplementation() != BackendImplementation.USAGE_STATS) {
                stopSelf()
                return
            }
            if (!appLockRepository.isProtectEnabled()) {
                lockPresenter.dismiss()
                return
            }
            if (pauseMonitoring || isDeviceLocked()) return
            val foreground = foregroundTracker.current() ?: return
            if (foreground.second in AppLockConstants.KNOWN_RECENTS_CLASSES) {
                lockPresenter.onForegroundChanged("system.recents")
                AppLockManager.sessions.observeForeground("system.recents", emptySet())
                return
            }
            val currentPackage = foreground.first
            if (currentPackage == packageName && !AppLockManager.sessions.isShowing) {
                AppLockManager.sessions.observeForeground(currentPackage, emptySet())
            }
            if (isExclusionApp(currentPackage)) return
            lockPresenter.onForegroundChanged(currentPackage)
            val trigger = AppLockManager.sessions.observeForeground(
                currentPackage, appLockRepository.getTriggerExcludedApps()
            )
            if (!AppLockManager.sessions.needsAuthentication(
                    currentPackage, appLockRepository.getLockedApps(), appLockRepository.getUnlockTimeDuration()
                )) return
            lockPresenter.show(currentPackage, trigger)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in Usage Stats monitoring", e)
        }
    }

    private fun isExclusionApp(packageName: String): Boolean {
        val keyboardPackages = getSystemService<InputMethodManager>()
            ?.enabledInputMethodList
            ?.map { it.packageName }
            ?: emptyList()

        return packageName == this.packageName ||
                packageName in keyboardPackages ||
                packageName in AppLockConstants.EXCLUDED_APPS
    }

    private fun startForegroundService() {
        createNotificationChannel()
        val notification = createNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, determineForegroundServiceType())
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun determineForegroundServiceType(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val dpm: DevicePolicyManager? = getSystemService()
            val component = ComponentName(this, DeviceAdmin::class.java)

            return if (dpm?.isAdminActive(component) == true) {
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
            "AppLock Service (Usage Stats)",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        notificationManager.createNotificationChannel(serviceChannel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("App Lock")
            .setContentText("Protecting your apps")
            .setSmallIcon(R.drawable.baseline_shield_24)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
    }
}
