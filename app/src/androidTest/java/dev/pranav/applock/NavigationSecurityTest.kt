package dev.pranav.applock

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import dev.pranav.applock.core.broadcast.DeviceAdmin
import dev.pranav.applock.core.navigation.AppNavHost
import dev.pranav.applock.core.navigation.Screen
import dev.pranav.applock.core.navigation.finishPasswordSetup
import dev.pranav.applock.data.repository.PreferencesRepository
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NavigationSecurityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun repository() = (compose.activity.application as AppLockApplication).appLockRepository

    private fun showChallenge(lockType: String) {
        compose.runOnUiThread {
            repository().setLockType(lockType)
            repository().setBiometricAuthEnabled(false)
            compose.activity.setContentView(androidx.compose.ui.platform.ComposeView(compose.activity).apply {
                setContent {
                    val nav = rememberNavController()
                    AppNavHost(nav, Screen.Settings.route)
                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        nav.navigate(Screen.PasswordOverlay.route)
                    }
                }
            })
        }
        compose.waitForIdle()
        val activity = compose.activity
        compose.runOnUiThread { activity.onBackPressedDispatcher.onBackPressed() }
        assertTrue(activity.isFinishing || activity.isDestroyed)
    }

    @Test fun backCannotRevealSettingsBehindPin() = showChallenge(PreferencesRepository.LOCK_TYPE_PIN)
    @Test fun backCannotRevealSettingsBehindPassword() = showChallenge(PreferencesRepository.LOCK_TYPE_PASSWORD)
    @Test fun backCannotRevealSettingsBehindPattern() = showChallenge(PreferencesRepository.LOCK_TYPE_PATTERN)

    @Test fun setupCompletionRemovesAllSetupHistoryWithoutIntroEntry() {
        lateinit var nav: NavHostController
        compose.runOnUiThread {
            compose.activity.setContentView(androidx.compose.ui.platform.ComposeView(compose.activity).apply {
                setContent {
                    nav = rememberNavController()
                    AppNavHost(nav, Screen.SetPassword.route)
                }
            })
        }
        compose.waitForIdle()
        compose.runOnUiThread {
            nav.navigate(Screen.SetPasswordPattern.route)
            nav.finishPasswordSetup(true)
            assertEquals(Screen.Main.route, nav.currentDestination?.route)
            assertNull(nav.previousBackStackEntry)
        }
    }

    @Test fun ordinaryAdminActivationDoesNotCallOwnerOnlyPolicy() {
        val context = compose.activity
        DeviceAdmin().onEnabled(context, Intent("android.app.action.DEVICE_ADMIN_ENABLED"))
        assertTrue(context.getSharedPreferences("app_lock_settings", Context.MODE_PRIVATE)
            .getBoolean("anti_uninstall", false))
        repository().setAntiUninstallEnabled(false)
    }
}
