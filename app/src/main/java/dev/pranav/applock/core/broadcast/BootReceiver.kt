package dev.pranav.applock.core.broadcast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.pranav.applock.core.utils.LogUtils
import dev.pranav.applock.core.utils.appLockRepository

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val repository = context.appLockRepository()
        
        when (intent.action) {
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.d(TAG, "App package replaced, clearing old logs and showing donate link")
//                repository.setShowDonateLink(true)
                // Clear all old logs on app update
                LogUtils.clearAllLogs()
                try {
                    AppLockServiceStarter.startAppropriateServices(context, repository)
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting services on package replace", e)
                }
            }

            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_USER_UNLOCKED,
            Intent.ACTION_USER_PRESENT -> {
                try {
                    AppLockServiceStarter.startAppropriateServices(context, repository)
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting services on boot or unlock", e)
                }
            }
            else -> {
                Log.w(TAG, "Invalid intent action: ${intent.action}")
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
