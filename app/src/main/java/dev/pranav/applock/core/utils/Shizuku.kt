package dev.pranav.applock.core.utils

import android.os.Process
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

fun blockUninstallForUser(packageName: String) = setUninstallBlocked(packageName, true)

fun unblockUninstallForUser(packageName: String) = setUninstallBlocked(packageName, false)

private fun setUninstallBlocked(packageName: String, blocked: Boolean) {
    // Resolve a fresh binder after Shizuku restarts; describeContents() is not a user ID.
    val manager = SystemServiceHelper.getSystemService("package")
        .let(::ShizukuBinderWrapper)
        .let(android.content.pm.IPackageManager.Stub::asInterface)
    check(manager.setBlockUninstallForUser(packageName, blocked, Process.myUid() / 100_000)) {
        "Android rejected the uninstall policy"
    }
}
