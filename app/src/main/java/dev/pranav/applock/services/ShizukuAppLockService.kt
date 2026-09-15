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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.pranav.applock.R
import dev.pranav.applock.core.broadcast.DeviceAdmin
import dev.pranav.applock.core.utils.LogUtils
import dev.pranav.applock.core.utils.appLockRepository
import dev.pranav.applock.data.repository.AppLockRepository
import dev.pranav.applock.data.repository.AppLockRepository.Companion.shouldStartService
import dev.pranav.applock.data.repository.BackendImplementation
import dev.pranav.applock.shizuku.ShizukuActivityManager

class ShizukuAppLockService : Service() {
    private val appLockRepository: AppLockRepository by lazy { applicationContext.appLockRepository() }
    private lateinit var lockPresenter: ServiceLockPresenter
    private var shizukuActivityManager: ShizukuActivityManager? = null

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

        appLockRepository.setActiveBackend(BackendImplementation.SHIZUKU)
        AppLockManager.stopAllOtherServices(this, this::class.java)

        setupShizukuActivityManager()

        val shizukuStarted = shizukuActivityManager?.start() == true
        if (!shizukuStarted) {
            Log.e(TAG, "Shizuku failed to start. Stopping service.")
            isServiceRunning = false
            stopSelf()
            return START_NOT_STICKY
        }

        return START_STICKY
    }

    override fun onDestroy() {
        LogUtils.d(TAG, "ShizukuAppLockService killed.")

        shizukuActivityManager?.stop()
        lockPresenter.destroy()

        if (isServiceRunning) {
            LogUtils.d(TAG, "Service destroyed unexpectedly. Automatic fallback is disabled.")
        }

        isServiceRunning = false
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

    override fun onUnbind(intent: Intent?): Boolean {
        LogUtils.d(TAG, "ShizukuAppLockService unbound. Automatic fallback is disabled.")
        return super.onUnbind(intent)
    }

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

    private fun createNotification(available: Boolean = false): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AppLock")
            .setContentText(getString(when {
                !appLockRepository.isProtectEnabled() -> R.string.shizuku_paused
                available -> R.string.shizuku_protecting
                else -> R.string.shizuku_unavailable
            }))
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
                notificationManager.notify(NOTIFICATION_ID, createNotification(available))
            },
            onDismissLock = { lockPresenter.dismiss() }
        ) { packageName, className ->
            if (className in AppLockConstants.KNOWN_RECENTS_CLASSES) {
                lockPresenter.onForegroundChanged("system.recents")
                AppLockManager.sessions.observeForeground("system.recents", emptySet())
                return@ShizukuActivityManager
            }
            if (packageName == this.packageName) {
                if (!AppLockManager.sessions.isShowing) AppLockManager.sessions.observeForeground(packageName, emptySet())
                return@ShizukuActivityManager
            }
            if (packageName in AppLockConstants.EXCLUDED_APPS) {
                return@ShizukuActivityManager
            }
            lockPresenter.onForegroundChanged(packageName)
            val trigger = AppLockManager.sessions.observeForeground(
                packageName, appLockRepository.getTriggerExcludedApps()
            )
            if (AppLockManager.sessions.needsAuthentication(
                    packageName, appLockRepository.getLockedApps(), appLockRepository.getUnlockTimeDuration()
                )) {
                try {
                    if (lockPresenter.show(packageName, trigger)) {
                        notificationManager.notify(NOTIFICATION_ID, createNotification(true))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to show authentication", e)
                    notificationManager.notify(NOTIFICATION_ID, createNotification(false))
                }
            }
        }
    }
}
