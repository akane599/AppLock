package dev.pranav.applock

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import dev.pranav.applock.core.utils.LogUtils
import dev.pranav.applock.data.repository.BackendImplementation
import dev.pranav.applock.features.applist.ui.MainScreen
import dev.pranav.applock.features.lockscreen.ui.PinPasswordOverlayScreen
import dev.pranav.applock.services.AppLockManager
import dev.pranav.applock.services.ShizukuAppLockService
import dev.pranav.applock.services.UsageLockService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant

class SecondPassRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = compose.activity
    private val repository get() = (context.application as AppLockApplication).appLockRepository

    @Before fun isolate() {
        check(context.packageName.endsWith(".audit")) { "Use -PauditBuild for tests that change app ops" }
        repository.setAntiUninstallEnabled(false)
        repository.setCommunityLinkShown(true)
        repository.setBiometricAuthEnabled(false)
        repository.setAutoUnlockEnabled(false)
        repository.setBackendImplementation(BackendImplementation.ACCESSIBILITY)
        context.stopService(Intent(context, ShizukuAppLockService::class.java))
        context.stopService(Intent(context, UsageLockService::class.java))
        compose.waitUntil(5_000) { !ShizukuAppLockService.isServiceRunning && !UsageLockService.isServiceRunning }
    }

    @After fun cleanup() {
        if (!context.packageName.endsWith(".audit")) return
        repository.setBackendImplementation(BackendImplementation.ACCESSIBILITY)
        context.stopService(Intent(context, ShizukuAppLockService::class.java))
        context.stopService(Intent(context, UsageLockService::class.java))
        appOp("GET_USAGE_STATS", "default")
        appOp("SYSTEM_ALERT_WINDOW", "default")
        AppLockManager.sessions.resetUnlocks()
    }

    private fun content(body: @Composable () -> Unit) {
        compose.runOnUiThread {
            context.setContentView(ComposeView(context).apply { setContent(content = body) })
        }
        compose.waitForIdle()
    }

    private fun appOp(op: String, mode: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("appops set ${context.packageName} $op $mode")
            .use { descriptor -> android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).readBytes() }
    }

    @Test fun reopeningDashboardRestartsSelectedBackend() {
        repository.setBackendImplementation(BackendImplementation.SHIZUKU)
        repository.setProtectEnabled(true)
        content { MainScreen(rememberNavController()) }
        compose.waitUntil(5_000) { ShizukuAppLockService.isServiceRunning }
    }

    @Test fun turningProtectionOnRestartsStoppedBackend() {
        repository.setBackendImplementation(BackendImplementation.SHIZUKU)
        repository.setProtectEnabled(false)
        content { MainScreen(rememberNavController()) }
        compose.onNodeWithText("OFF").performClick()
        compose.waitUntil(5_000) { ShizukuAppLockService.isServiceRunning }
    }

    @Test fun pinAutoUnlockWorksInAppLockAndRejectsWrongPrefix() {
        repository.setPassword("1234")
        repository.setAutoUnlockEnabled(true)
        var successes = 0
        content { PinPasswordOverlayScreen(fromMainActivity = true, onAuthSuccess = { successes++ }) }
        listOf("1", "2", "3").forEach { compose.onNodeWithText(it).performClick() }
        compose.runOnIdle { assertEquals(0, successes) }
        compose.onNodeWithText("4").performClick()
        compose.runOnIdle { assertEquals(1, successes) }
    }

    @Test fun changingPinCannotSkipCurrentPinBySwitchingMethods() {
        repository.setPassword("1234")
        val patternLabel = context.getString(R.string.use_pattern_button)
        content {
            dev.pranav.applock.features.setpassword.ui.SetPasswordScreen(
                rememberNavController(), isFirstTimeSetup = false)
        }
        compose.onNodeWithText(patternLabel).assertDoesNotExist()
        listOf("1", "2", "3", "4").forEach { compose.onNodeWithText(it).performClick() }
        compose.onNodeWithContentDescription("proceed").performClick()
        compose.onNodeWithText(patternLabel).assertExists()
    }

    @Test fun usagePermissionLossRevokesPreviousGrants() {
        appOp("GET_USAGE_STATS", "allow")
        appOp("SYSTEM_ALERT_WINDOW", "allow")
        repository.setBackendImplementation(BackendImplementation.USAGE_STATS)
        repository.setProtectEnabled(true)
        compose.runOnUiThread { context.startForegroundService(Intent(context, UsageLockService::class.java)) }
        compose.waitUntil(5_000) { UsageLockService.isServiceRunning }
        compose.waitForIdle()
        val sessions = AppLockManager.sessions
        val token = sessions.begin("audit.synthetic")!!
        assertTrue(sessions.claim(token, "audit.synthetic"))
        assertTrue(sessions.authenticate(token))
        sessions.release(token)
        appOp("GET_USAGE_STATS", "ignore")
        compose.waitUntil(5_000) {
            sessions.needsAuthentication("audit.synthetic", setOf("audit.synthetic"), Int.MAX_VALUE)
        }
        assertTrue(UsageLockService.isServiceRunning)
    }

    @Test fun missingOverlayPermissionCannotKeepAnUnfinishableFallbackChallenge() {
        appOp("SYSTEM_ALERT_WINDOW", "ignore")
        repository.setBackendImplementation(BackendImplementation.SHIZUKU)
        repository.setProtectEnabled(true)
        compose.runOnUiThread { context.startForegroundService(Intent(context, ShizukuAppLockService::class.java)) }
        compose.waitUntil(5_000) { ShizukuAppLockService.isServiceRunning }
        val sessions = AppLockManager.sessions
        var token = 0L
        compose.runOnUiThread {
            // Model the last usable overlay backend and its outstanding challenge.
            repository.setShizukuRuntimeBackend(BackendImplementation.USAGE_STATS)
            token = sessions.begin("audit.synthetic")!!
            assertTrue(sessions.claim(token, "audit.synthetic"))
        }
        compose.waitUntil(5_000) { !sessions.owns(token) }
        assertFalse(sessions.authenticate(token))
    }

    @Test fun repeatedExportDoesNotChangePreviouslySharedSnapshot() = runBlocking {
        val source = File(context.filesDir, "audit_log.txt")
        try {
            source.writeText("first export")
            val first = LogUtils.exportAuditLogs()!!
            source.writeText("second export")
            val second = LogUtils.exportAuditLogs()!!
            assertNotEquals(first, second)
            assertEquals("first export", context.contentResolver.openInputStream(first)!!
                .bufferedReader().use { it.readText() })
        } finally { source.delete() }
    }

    @Test fun retentionRemovesWholeExpiredException() {
        val file = File.createTempFile("retention", ".txt", context.cacheDir)
        try {
            file.writeText("2000-01-01T00:00:00Z E test: old\nold exception payload\n" +
                "${Instant.now()} E test: current\ncurrent exception payload\n")
            purge(file, "audit")
            val text = file.readText()
            assertFalse(text.contains("old exception payload"))
            assertTrue(text.contains("current exception payload"))
        } finally { file.delete() }
    }

    @Test fun retentionExpiresLogcatFilesWithoutIsoTimestamps() {
        val file = File.createTempFile("logcat", ".txt", context.cacheDir)
        try {
            file.writeText("01-01 00:00:00.000 123 123 D Test: expired")
            assertTrue(file.setLastModified(1L))
            purge(file, "app")
            assertFalse(file.exists())
        } finally { file.delete() }
    }

    private fun purge(file: File, kind: String) {
        LogUtils::class.java.getDeclaredMethod("purgeOldLogsFromFile", File::class.java, String::class.java)
            .apply { isAccessible = true }.invoke(LogUtils, file, kind)
    }
}
