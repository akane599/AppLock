package dev.pranav.applock

import android.content.ComponentName
import androidx.test.platform.app.InstrumentationRegistry
import dev.pranav.applock.core.utils.enableAccessibilityComponent
import dev.pranav.applock.core.navigation.NavigationManager
import dev.pranav.applock.core.navigation.Screen
import dev.pranav.applock.features.antiuninstall.ui.AntiUninstallViewModel
import org.junit.Assert.*
import org.junit.Test

class PolicyRegressionTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun enablingAccessibilityRunsBothWritesAndRemovesNullSentinel() {
        val calls = mutableListOf<List<String>>()
        enableAccessibilityComponent(ComponentName("test.app", "test.app.Service")) { args ->
            calls.add(args.toList())
            if (args[1] == "get") "null\n" else ""
        }
        assertEquals(3, calls.size)
        assertEquals("test.app/test.app.Service", calls[1].last())
        assertEquals(listOf("settings", "put", "secure", "accessibility_enabled", "1"), calls[2])
    }

    @Test fun accessibilityPreservesOtherComponentsAndRepairsGlobalFlag() {
        val calls = mutableListOf<List<String>>()
        enableAccessibilityComponent(ComponentName("test.app", "test.app.Service")) { args ->
            calls.add(args.toList())
            if (args[1] == "get") "other/other.Service:test.app/.Service" else ""
        }
        assertEquals("other/other.Service:test.app/test.app.Service", calls[1].last())
        assertEquals("accessibility_enabled", calls[2][3])
    }

    @Test fun manualProtectionAppliesPolicyBeforeSavingAndFailureDoesNotSave() {
        val repository = (context.applicationContext as AppLockApplication).appLockRepository
        val name = "audit.synthetic.package"
        repository.removeAntiUninstallApp(name)
        val calls = mutableListOf<Pair<String, Boolean>>()
        val model = AntiUninstallViewModel { pkg, enabled ->
            assertFalse(repository.isAppAntiUninstall(pkg))
            calls.add(pkg to enabled)
        }
        model.addManualPackage(context, " $name ")
        assertEquals(listOf(name to true), calls)
        assertTrue(repository.isAppAntiUninstall(name))
        repository.removeAntiUninstallApp(name)
        val failing = AntiUninstallViewModel { _, _ -> throw SecurityException("synthetic denial") }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            failing.addManualPackage(context, name)
        }
        assertFalse(repository.isAppAntiUninstall(name))
    }

    @Test fun setupRoutesCannotSkipResumeAuthenticationWithExistingCredential() {
        val repository = (context.applicationContext as AppLockApplication).appLockRepository
        repository.setPassword("1234")
        val navigation = NavigationManager(context)
        listOf(Screen.SetPassword, Screen.SetPasswordPattern, Screen.SetPasswordAlphanumeric).forEach {
            assertFalse(navigation.shouldSkipPasswordCheck(it.route))
        }
    }
    @Test fun auditExportIsSnapshotAndOtherPrivateFilesCannotBeShared() {
        val source = java.io.File(context.filesDir, "audit_log.txt")
        source.writeText("synthetic audit entry")
        try {
            val uri = kotlinx.coroutines.runBlocking {
                dev.pranav.applock.core.utils.LogUtils.exportAuditLogs()
            }
            assertNotNull(uri)
            source.writeText("later entry")
            val snapshot = context.contentResolver.openInputStream(uri!!)!!.bufferedReader().use { it.readText() }
            assertEquals("synthetic audit entry", snapshot)
            try {
                androidx.core.content.FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", source)
                fail("Private audit source must not be exposed")
            } catch (_: IllegalArgumentException) {
                // Only cache/shared_logs snapshots can be shared.
            }
        } finally {
            source.delete()
        }
    }

}
