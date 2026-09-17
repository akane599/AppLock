package dev.pranav.applock

import android.content.Context
import android.os.SystemClock
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.compose.rememberNavController
import dev.pranav.applock.features.setpassword.ui.AlphanumericSetPasswordScreen
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class SetupCooldownRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = compose.activity
    private val repository get() = (context.application as AppLockApplication).appLockRepository

    @Before fun prepare() {
        check(context.packageName.endsWith(".audit")) { "Use -PauditBuild for synthetic credentials" }
        clearPreferences()
        repository.setPassword("1234")
    }

    @After fun clearPreferences() {
        if (!context.packageName.endsWith(".audit")) return
        listOf("app_lock_prefs", "app_lock_settings").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test fun passwordSetupDuringCooldownWaitsToFocusUntilInputIsAttached() {
        repeat(5) { repository.recordAuthenticationFailure() }
        compose.runOnUiThread {
            context.setContentView(ComposeView(context).apply {
                setContent { AlphanumericSetPasswordScreen(rememberNavController(), false) }
            })
        }
        // The original focus effect crashes here because the gate hides the field.
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()

        context.getSharedPreferences("app_lock_prefs", Context.MODE_PRIVATE).edit()
            .putLong("cooldown_elapsed_end", SystemClock.elapsedRealtime() - 1L).commit()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasSetTextAction()).assertIsFocused()
    }
}
