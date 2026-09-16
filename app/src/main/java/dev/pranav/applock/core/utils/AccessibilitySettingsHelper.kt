package dev.pranav.applock.core.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess

fun Context.isAccessibilityServiceEnabled(): Boolean {
    val component = ComponentName(this, dev.pranav.applock.services.AppLockAccessibilityService::class.java)
    val enabledServices = Settings.Secure.getString(
        contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ).orEmpty()
    return enabledServices.split(':').mapNotNull(ComponentName::unflattenFromString).contains(component)

}

fun openAccessibilitySettings(context: Context) {
    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
    context.startActivity(intent)
}

fun Context.enableAccessibilityServiceWithShizuku(serviceComponentName: ComponentName): Boolean {
    val TAG = "ShizukuAccessibilityStarter"
    val serviceString = serviceComponentName.flattenToString()

    if (!Shizuku.pingBinder()) {
        Log.e(TAG, "Shizuku is not available or permission denied.")
        return false
    }

    try {
        enableAccessibilityComponent(serviceComponentName) { exec(*it) }

        Log.i(TAG, "Successfully enabled service: $serviceString")
        return true

    } catch (e: Exception) {
        Log.e(
            TAG,
            "Failed to enable Accessibility Service with Shizuku for $serviceString: ${e.message}"
        )
        e.printStackTrace()
        return false
    }
}

internal fun enableAccessibilityComponent(
    component: ComponentName,
    execute: (Array<out String>) -> String
) {
    val currentServices = execute(arrayOf("settings", "get", "secure", "enabled_accessibility_services"))
    val services = currentServices.trim().split(':')
        .mapNotNull(ComponentName::unflattenFromString).toMutableSet()
    services.add(component)
    execute(arrayOf("settings", "put", "secure", "enabled_accessibility_services",
        services.joinToString(":") { it.flattenToString() }))
    execute(arrayOf("settings", "put", "secure", "accessibility_enabled", "1"))
}

private fun exec(vararg command: String): String {
    val method = Shizuku::class.java.getDeclaredMethod(
        "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
    ).apply { isAccessible = true }
    val process = method.invoke(null, command, null, "/") as ShizukuRemoteProcess
    try {
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "Shizuku settings command failed" }
        return output
    } finally {
        process.destroy()
    }
}
