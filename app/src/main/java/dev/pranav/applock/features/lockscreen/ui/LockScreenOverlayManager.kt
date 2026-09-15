package dev.pranav.applock.features.lockscreen.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.view.WindowManager
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.pranav.applock.core.utils.appLockRepository
import dev.pranav.applock.data.repository.PreferencesRepository
import dev.pranav.applock.services.AppLockManager
import dev.pranav.applock.ui.theme.AppLockTheme

@SuppressLint("ViewConstructor")
class LockScreenOverlayManager(private val context: Context):
    LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner, OnBackPressedDispatcherOwner {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var composeView: ComposeView? = null
    private var activeToken: Long? = null

    // Lifecycle setup
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()
    private var isStateRestored = false

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    override val onBackPressedDispatcher = OnBackPressedDispatcher {
        exitAction?.invoke()
    }
    private var exitAction: (() -> Unit)? = null

    fun showOverlay(
        lockedPackageName: String,
        triggeringPackageName: String,
        lockToken: Long,
        autoBiometric: Boolean = false,
        onUnlock: () -> Unit,
        onExit: () -> Unit,
        onBiometricHandoff: () -> Unit
    ) {
        check(composeView == null) { "Authentication overlay already attached" }
        activeToken = lockToken
        var completed = false
        val finishUnlock = {
            if (!completed) {
                completed = true
                onUnlock()
                removeOverlay()
            }
        }
        exitAction = {
            onExit()
            removeOverlay()
        }
        val startBiometrics: () -> Unit = {
            try {
                AppLockManager.prepareActivityHandoff(lockToken) {
                    onBiometricHandoff()
                    removeOverlay()
                }
                context.startActivity(PasswordOverlayActivity.createIntent(
                    context, lockedPackageName, triggeringPackageName, lockToken
                ))
            } catch (e: Exception) {
                AppLockManager.cancelActivityHandoff(lockToken)
                // Keep the password overlay attached if the handoff fails.
                android.util.Log.e("LockScreenOverlay", "Cannot open biometric authentication", e)
            }
        }

        if (!isStateRestored) {
            savedStateRegistryController.performRestore(null)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            isStateRestored = true
        }

        composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@LockScreenOverlayManager)
            setViewTreeSavedStateRegistryOwner(this@LockScreenOverlayManager)
            setViewTreeViewModelStoreOwner(this@LockScreenOverlayManager)

            setContent {
                CompositionLocalProvider(
                    LocalOnBackPressedDispatcherOwner provides this@LockScreenOverlayManager
                ) {
                    AppLockTheme {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.background
                        ) {
                            val appLockRepository = context.appLockRepository()
                            val appName = try {
                                val pm = context.packageManager
                                pm.getApplicationLabel(pm.getApplicationInfo(lockedPackageName, 0))
                                    .toString()
                            } catch (_: Exception) {
                                "App"
                            }

                            val onPinAttemptCallback = { pin: String ->
                                val isValid = appLockRepository.validatePassword(pin)
                                if (isValid) {
                                    finishUnlock()
                                }
                                isValid
                            }

                            val onPatternAttemptCallback = { pattern: String ->
                                val isValid = appLockRepository.validatePattern(pattern)
                                if (isValid) {
                                    finishUnlock()
                                }
                                isValid
                            }

                            BackHandler {
                                onExit()
                                removeOverlay()
                            }

                            val lockType = appLockRepository.getLockType()

                            when (lockType) {
                                PreferencesRepository.LOCK_TYPE_PATTERN -> {
                                    PatternLockScreen(
                                        fromMainActivity = false,
                                        showCloseButton = true,
                                        onClose = {
                                            onExit()
                                            removeOverlay()
                                        },
                                        lockedAppName = appName,
                                        triggeringPackageName = triggeringPackageName,
                                        onPatternAttempt = onPatternAttemptCallback,
                                        onBiometricAuth = startBiometrics
                                    )
                                }

                                PreferencesRepository.LOCK_TYPE_PASSWORD -> {
                                    AlphanumericPasswordOverlayScreen(
                                        showBiometricButton = appLockRepository.isBiometricAuthEnabled(),
                                        fromMainActivity = false,
                                        showCloseButton = true,
                                        onClose = {
                                            onExit()
                                            removeOverlay()
                                        },
                                        lockedAppName = appName,
                                        triggeringPackageName = triggeringPackageName,
                                        onAuthSuccess = finishUnlock,
                                        onBiometricAuth = startBiometrics,
                                        onPasswordAttempt = onPinAttemptCallback
                                    )
                                }

                                else -> {
                                    PinPasswordOverlayScreen(
                                        showBiometricButton = appLockRepository.isBiometricAuthEnabled(),
                                        fromMainActivity = false,
                                        showCloseButton = true,
                                        onClose = {
                                            onExit()
                                            removeOverlay()
                                        },
                                        lockedAppName = appName,
                                        triggeringPackageName = triggeringPackageName,
                                        onAuthSuccess = finishUnlock,
                                        onBiometricAuth = startBiometrics,
                                        onPinAttempt = onPinAttemptCallback
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Window Layout Parameters
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (context is android.accessibilityservice.AccessibilityService) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            },
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_SECURE or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            // Respect brightness setting
            if (context.appLockRepository().shouldUseMaxBrightness()) {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
            }
        }

        composeView?.isFocusableInTouchMode = true
        composeView?.requestFocus()

        try {
            windowManager.addView(composeView, params)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            if (autoBiometric) composeView?.post {
                if (activeToken == lockToken) startBiometrics()
            }
        } catch (e: Exception) {
            composeView?.disposeComposition()
            composeView = null
            exitAction = null
            throw e
        }
    }

    fun removeOverlay() {
        activeToken?.let(AppLockManager::cancelActivityHandoff)
        activeToken = null
        composeView?.let {
            try {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                windowManager.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            it.disposeComposition()
            composeView = null
            exitAction = null
        }
    }

    fun destroy() {
        removeOverlay()
        if (isStateRestored) lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }
}
