package dev.pranav.applock.core.navigation

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import dev.pranav.applock.AppLockApplication
import dev.pranav.applock.data.repository.PreferencesRepository
import dev.pranav.applock.features.antiuninstall.ui.AntiUninstallScreen
import dev.pranav.applock.features.appintro.ui.AppIntroScreen
import dev.pranav.applock.features.applist.ui.MainScreen
import dev.pranav.applock.features.lockscreen.ui.AlphanumericPasswordOverlayScreen
import dev.pranav.applock.features.lockscreen.ui.PatternLockScreen
import dev.pranav.applock.features.lockscreen.ui.PinPasswordOverlayScreen
import dev.pranav.applock.features.setpassword.ui.AlphanumericSetPasswordScreen
import dev.pranav.applock.features.setpassword.ui.PatternSetPasswordScreen
import dev.pranav.applock.features.setpassword.ui.SetPasswordScreen
import dev.pranav.applock.features.settings.ui.SettingsScreen
import dev.pranav.applock.features.triggerexclusions.ui.TriggerExclusionsScreen

@Composable
fun AppNavHost(navController: NavHostController, startDestination: String) {
    val application = LocalContext.current.applicationContext as AppLockApplication

    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = {
            fadeIn(animationSpec = tween(ANIMATION_DURATION)) +
                    scaleIn(initialScale = SCALE_INITIAL, animationSpec = tween(ANIMATION_DURATION))
        },
        popEnterTransition = {
            fadeIn(animationSpec = tween(ANIMATION_DURATION)) +
                    scaleIn(initialScale = SCALE_INITIAL, animationSpec = tween(ANIMATION_DURATION))
        },
    ) {
        composable(Screen.AppIntro.route) {
            AppIntroScreen(navController)
        }

        composable(Screen.SetPassword.route) {
            SetPasswordScreen(navController, isFirstTimeSetup = true)
        }

        composable(Screen.ChangePassword.route) {
            if (application.appLockRepository.isBiometricOnly()) {
                BiometricCredentialSetup(navController)
            } else when (application.appLockRepository.getLockType()) {
                PreferencesRepository.LOCK_TYPE_PATTERN -> {
                    PatternSetPasswordScreen(navController, false)
                }
                PreferencesRepository.LOCK_TYPE_PASSWORD -> {
                    AlphanumericSetPasswordScreen(navController, false)
                }
                else -> {
                    SetPasswordScreen(navController, isFirstTimeSetup = false)
                }
            }
        }

        composable(Screen.SetPasswordPattern.route) {
            PatternSetPasswordScreen(navController, isFirstTimeSetup = true)
        }

        composable(Screen.SetPasswordAlphanumeric.route) {
            AlphanumericSetPasswordScreen(navController, isFirstTimeSetup = true)
        }

        composable(Screen.Main.route) {
            MainScreen(navController)
        }

        composable(Screen.PasswordOverlay.route) {
            val context = LocalActivity.current as FragmentActivity
            // Back must leave the activity, never reveal the protected back stack.
            BackHandler { context.finish() }
            val authenticate = dev.pranav.applock.features.lockscreen.ui.rememberBiometricAuthentication {
                handleAuthenticationSuccess(navController)
            }
            val lockType = application.appLockRepository.getLockType()

            when (lockType) {
                PreferencesRepository.LOCK_TYPE_PATTERN -> {
                    PatternLockScreen(
                        fromMainActivity = true,
                        onPatternAttempt = { pattern ->
                            val isValid = application.appLockRepository.validatePattern(pattern)
                            if (isValid) {
                                handleAuthenticationSuccess(navController)
                            }
                            isValid
                        },
                        onBiometricAuth = {
                            authenticate()
                        }
                    )
                }

                PreferencesRepository.LOCK_TYPE_PASSWORD -> {
                    AlphanumericPasswordOverlayScreen(
                        showBiometricButton = application.appLockRepository.isBiometricAuthEnabled(),
                        fromMainActivity = true,
                        onBiometricAuth = {
                            authenticate()
                        },
                        onAuthSuccess = {
                            handleAuthenticationSuccess(navController)
                        }
                    )
                }

                else -> {
                    PinPasswordOverlayScreen(
                        showBiometricButton = application.appLockRepository.isBiometricAuthEnabled(),
                        fromMainActivity = true,
                        onBiometricAuth = {
                            authenticate()
                        },
                        onAuthSuccess = {
                            handleAuthenticationSuccess(navController)
                        }
                    )
                }
            }
        }

        composable(Screen.Settings.route) {
            SettingsScreen(navController)
        }

        composable(Screen.TriggerExclusions.route) {
            TriggerExclusionsScreen(navController)
        }

        composable(Screen.AntiUninstall.route) {
            AntiUninstallScreen(navController)
        }
    }
}

fun NavController.finishPasswordSetup(isFirstTimeSetup: Boolean) {
    if (isFirstTimeSetup) {
        navigate(Screen.Main.route) {
            popUpTo(graph.id) {
                inclusive = true
            }
            launchSingleTop = true
        }
    } else {
        navigate(Screen.Main.route) {
            popUpTo(Screen.Main.route) {
                inclusive = false
            }
            launchSingleTop = true
        }
    }
}

private fun handleAuthenticationSuccess(navController: NavHostController) {
    if (navController.previousBackStackEntry != null) {
        navController.popBackStack()
    } else {
        navigateToMain(navController)
    }
}

private fun navigateToMain(navController: NavHostController) {
    navController.navigate(Screen.Main.route) {
        popUpTo(Screen.PasswordOverlay.route) { inclusive = true }
    }
}

private const val ANIMATION_DURATION = 400
private const val SCALE_INITIAL = 0.9f

@Composable
private fun BiometricCredentialSetup(navController: NavHostController) {
    var verified by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val authenticate = dev.pranav.applock.features.lockscreen.ui.rememberBiometricAuthentication {
        verified = true
    }
    if (verified) SetPasswordScreen(navController, isFirstTimeSetup = true)
    else dev.pranav.applock.features.lockscreen.ui.AuthenticationGate(
        onBiometricAuth = authenticate,
        onClose = { navController.popBackStack() }
    )
}
