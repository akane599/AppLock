package dev.pranav.applock.services

/** Authentication and foreground state shared by all backends. Times must be monotonic. */
class LockSessionState(private val now: () -> Long) {
    private val unlockTimes = mutableMapOf<String, Long>()
    private var unlockedPackage = ""
    private var foregroundPackage = ""
    private var excludedSession = ""
    private var nextToken = 0L
    private var challenge: Challenge? = null

    private data class Challenge(
        val token: Long,
        val packageName: String,
        var claimed: Boolean = false,
        var authenticated: Boolean = false
    )

    @get:Synchronized
    val isShowing: Boolean get() = challenge != null

    @Synchronized
    fun observeForeground(packageName: String, excludedTriggers: Set<String>): String {
        val previous = foregroundPackage
        if (packageName != previous) {
            foregroundPackage = packageName
            excludedSession = if (previous in excludedTriggers) packageName else ""
        }
        if (unlockedPackage != packageName) unlockedPackage = ""
        return previous
    }

    @Synchronized
    fun needsAuthentication(packageName: String, lockedApps: Set<String>, durationMinutes: Int): Boolean {
        if (packageName !in lockedApps || packageName == excludedSession) return false
        if (packageName == unlockedPackage) return false
        val timestamp = unlockTimes[packageName] ?: return true
        val elapsed = now() - timestamp
        if (durationMinutes > 0 && elapsed >= 0 &&
            (durationMinutes >= 10_000 || elapsed < durationMinutes.toLong() * 60_000L)
        ) return false
        unlockTimes.remove(packageName)
        return true
    }

    @Synchronized
    fun begin(packageName: String): Long? {
        if (challenge != null || packageName.isBlank()) return null
        return (++nextToken).also { challenge = Challenge(it, packageName) }
    }

    @Synchronized
    fun claim(token: Long, packageName: String): Boolean {
        val active = challenge ?: return false
        if (active.token != token || active.packageName != packageName) return false
        active.claimed = true
        return true
    }

    @Synchronized
    fun authenticate(token: Long): Boolean {
        val active = challenge ?: return false
        if (active.token != token || !active.claimed || active.authenticated) return false
        active.authenticated = true
        unlockedPackage = active.packageName
        // The authentication UI is transient; returning to the target is not an app switch.
        foregroundPackage = active.packageName
        unlockTimes[active.packageName] = now()
        return true
    }

    @Synchronized
    fun owns(token: Long): Boolean = challenge?.token == token

    @Synchronized
    fun release(token: Long) {
        if (challenge?.token == token) challenge = null
    }

    @Synchronized
    fun expireUnclaimed(token: Long) {
        if (challenge?.token == token && challenge?.claimed == false) challenge = null
    }

    @Synchronized
    fun clearPackage(packageName: String) {
        if (unlockedPackage == packageName) unlockedPackage = ""
        if (excludedSession == packageName) excludedSession = ""
        unlockTimes.remove(packageName)
    }

    @Synchronized
    fun clearGrants() {
        unlockedPackage = ""
        foregroundPackage = ""
        excludedSession = ""
        unlockTimes.clear()
    }

    @Synchronized
    fun resetUnlocks() {
        clearGrants()
        // A password entered before screen-off must not authenticate after screen-off.
        challenge = null
    }
}
