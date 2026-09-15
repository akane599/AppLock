package dev.pranav.applock.shizuku

import android.app.ActivityManager
import android.app.TaskStackListener
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import dev.pranav.applock.data.repository.AppLockRepository
import dev.pranav.applock.data.repository.BackendImplementation
import dev.pranav.applock.services.AppLockManager
import dev.pranav.applock.services.isDeviceLocked
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

class ShizukuActivityManager(
    private val context: Context,
    private val appLockRepository: AppLockRepository,
    private val onAvailabilityChanged: (Boolean) -> Unit,
    private val onDismissLock: () -> Unit,
    private val onForegroundAppChanged: (String, String) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var receiverRegistered = false
    private var taskService: Any? = null
    private var listenerRegistered = false
    private var available: Boolean? = null
    private var protectionEnabled: Boolean? = null

    private val taskListener = object : TaskStackListener() {
        override fun onTaskStackChanged() = requestCheck()
        override fun onTaskMovedToFront(taskInfo: ActivityManager.RunningTaskInfo) = requestCheck()
    }

    private fun requestCheck() {
        handler.post {
            if (running) {
                handler.removeCallbacks(monitor)
                handler.post(monitor)
            }
        }
    }

    private val monitor = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                checkForegroundApp()
            } catch (e: Exception) {
                if (available != false) Log.e(TAG, "Cannot query foreground tasks", e)
                taskService = null
                listenerRegistered = false
                reportAvailability(false)
            } finally {
                if (running) handler.postDelayed(this, if (available == false) 1_000L else 250L)
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                AppLockManager.sessions.resetUnlocks()
                onDismissLock()
            }
            requestCheck()
        }
    }

    fun start(): Boolean {
        if (running) return true
        return try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            context.registerReceiver(screenReceiver, filter)
            receiverRegistered = true
            running = true
            handler.post(monitor)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Cannot start foreground monitor", e)
            stop()
            false
        }
    }

    private fun checkForegroundApp() {
        if (appLockRepository.getBackendImplementation() != BackendImplementation.SHIZUKU) {
            stop()
            (context as? android.app.Service)?.stopSelf()
            return
        }
        if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            taskService = null
            listenerRegistered = false
            reportAvailability(false)
            return
        }
        val service = taskService ?: createTaskService().also { taskService = it }
        if (!listenerRegistered) {
            try {
                service.javaClass.methods.first { it.name == "registerTaskStackListener" }
                    .apply { isAccessible = true }.invoke(service, taskListener)
                listenerRegistered = true
            } catch (e: Exception) {
                // Polling still works on systems that do not permit the optional listener.
                Log.w(TAG, "Task listener unavailable; using polling", e)
                listenerRegistered = true
            }
        }
        @Suppress("UNCHECKED_CAST")
        val tasks = TaskQueryCompat.query(service) as List<ActivityManager.RunningTaskInfo>
        reportAvailability(true)
        if (!appLockRepository.isProtectEnabled()) {
            onDismissLock()
            return
        }
        if (context.isDeviceLocked()) return
        // getTasks is ordered by foreground task. Iterating every visible task oscillates
        // between apps in split screen and can overwrite a just-authenticated session.
        val activity = tasks.firstOrNull()?.topActivity ?: return
        onForegroundAppChanged(activity.packageName, activity.className)
    }

    private fun createTaskService(): Any {
        val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val serviceName = if (modern) "activity_task" else "activity"
        val interfaceName = if (modern) "IActivityTaskManager" else "IActivityManager"
        val binder = ShizukuBinderWrapper(SystemServiceHelper.getSystemService(serviceName))
        return Class.forName("android.app.$interfaceName\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
    }

    private fun reportAvailability(value: Boolean) {
        val enabled = appLockRepository.isProtectEnabled()
        if (available == value && protectionEnabled == enabled) return
        available = value
        protectionEnabled = enabled
        if (!value) AppLockManager.sessions.resetUnlocks()
        onAvailabilityChanged(value)
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        if (receiverRegistered) {
            context.unregisterReceiver(screenReceiver)
            receiverRegistered = false
        }
        taskService?.let { service ->
            if (listenerRegistered) runCatching {
                service.javaClass.methods.first { it.name == "unregisterTaskStackListener" }
                    .apply { isAccessible = true }.invoke(service, taskListener)
            }
        }
        listenerRegistered = false
        taskService = null
    }

    companion object { private const val TAG = "ShizukuActivityManager" }
}
