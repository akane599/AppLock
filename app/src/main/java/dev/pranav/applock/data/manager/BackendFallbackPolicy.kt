package dev.pranav.applock.data.manager

import dev.pranav.applock.data.repository.BackendImplementation

/** Runtime routing never changes the backend selected by the user. */
object BackendFallbackPolicy {
    fun choose(
        protectionEnabled: Boolean,
        shizukuAvailable: Boolean,
        accessibilityConnected: Boolean,
        usageAccessGranted: Boolean,
        overlayGranted: Boolean
    ): BackendImplementation? = when {
        !protectionEnabled -> null
        shizukuAvailable && overlayGranted -> BackendImplementation.SHIZUKU
        accessibilityConnected -> BackendImplementation.ACCESSIBILITY
        usageAccessGranted && overlayGranted -> BackendImplementation.USAGE_STATS
        else -> null
    }

    fun transition(
        current: BackendImplementation?,
        desired: BackendImplementation?,
        authenticating: Boolean
    ): BackendImplementation? =
        if (authenticating && current != null && desired != null) current else desired

    fun effective(
        preferred: BackendImplementation,
        shizukuRuntime: BackendImplementation?
    ): BackendImplementation? =
        if (preferred == BackendImplementation.SHIZUKU) shizukuRuntime else preferred
}
