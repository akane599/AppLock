package dev.pranav.applock.features.lockscreen.ui

import android.app.Activity
import android.app.KeyguardManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.LocalActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dev.pranav.applock.R
import dev.pranav.applock.core.utils.appLockRepository
import kotlinx.coroutines.delay

/** Replaces credential input during cooldowns and in biometric-only mode. */
@Composable
fun AuthenticationGate(
    modifier: Modifier = Modifier,
    onBiometricAuth: () -> Unit = {},
    onClose: () -> Unit = {},
    showCloseButton: Boolean = true,
    showBiometricOnly: Boolean = true
): Boolean {
    val repository = LocalContext.current.appLockRepository()
    var remaining by remember { mutableLongStateOf(repository.cooldownRemainingMillis()) }
    LaunchedEffect(repository) {
        while (true) {
            remaining = repository.cooldownRemainingMillis()
            delay(250)
        }
    }
    if (remaining <= 0L && (!showBiometricOnly || !repository.isBiometricOnly())) return false
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (remaining > 0L) {
                val seconds = (remaining + 999L) / 1000L
                Text(
                    stringResource(R.string.unlock_cooldown, seconds / 60L, seconds % 60L),
                    textAlign = TextAlign.Center
                )
            } else {
                Text(stringResource(R.string.biometric_only_description), textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onBiometricAuth) {
                    Text(stringResource(R.string.biometric_authentication_cd))
                }
            }
            if (showCloseButton) {
                TextButton(onClick = onClose) { Text(stringResource(R.string.cancel_button)) }
            }
        }
    }
    return true
}

/** Keeps one prompt per composition and cancels it when its owning screen leaves. */
@Composable
fun rememberBiometricAuthentication(
    enableBiometricOnly: Boolean = false,
    allowDeviceCredential: Boolean = false,
    onSuccess: () -> Unit
): () -> Unit {
    val context = LocalContext.current
    val resources = LocalResources.current
    val activity = LocalActivity.current as? FragmentActivity
    val repository = context.appLockRepository()
    val success by rememberUpdatedState(onSuccess)
    val enableOnly by rememberUpdatedState(enableBiometricOnly)
    val recovery by rememberUpdatedState(allowDeviceCredential)
    var showing by remember { mutableStateOf(false) }
    var active by remember { mutableStateOf(true) }
    val completeAuthentication = {
        if (active) {
            showing = false
            if (repository.cooldownRemainingMillis() == 0L) {
                if (enableOnly) repository.setBiometricOnly(true)
                val accepted = if (recovery) repository.recordDeviceCredentialSuccess()
                    else repository.recordBiometricSuccess()
                if (accepted) success()
            }
        }
    }
    val complete by rememberUpdatedState(completeAuthentication)
    val credentialLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) complete()
        else showing = false
    }
    val prompt = remember(activity) {
        activity?.let {
            BiometricPrompt(it, ContextCompat.getMainExecutor(context),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (!active) return
                        showing = false
                        if (errorCode == BiometricPrompt.ERROR_LOCKOUT ||
                            errorCode == BiometricPrompt.ERROR_LOCKOUT_PERMANENT) {
                            repository.recordBiometricLockout()
                        }
                        Toast.makeText(context, errString, Toast.LENGTH_SHORT).show()
                    }
                    override fun onAuthenticationFailed() {
                        if (!active) return
                        repository.recordAuthenticationFailure()
                    }
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        complete()
                    }
                })
        }
    }
    LaunchedEffect(prompt, showing) {
        while (showing) {
            if (repository.cooldownRemainingMillis() > 0L) {
                prompt?.cancelAuthentication()
                showing = false
            }
            delay(100)
        }
    }
    DisposableEffect(prompt) {
        active = true
        onDispose {
            active = false
            prompt?.cancelAuthentication()
        }
    }
    return authenticate@{
        if (!active || showing || repository.cooldownRemainingMillis() > 0L || prompt == null) {
            return@authenticate
        }
        if (allowDeviceCredential && repository.isBiometricOnly()) return@authenticate
        if (!allowDeviceCredential && !enableBiometricOnly && !repository.isBiometricAuthEnabled()) {
            return@authenticate
        }
        try {
            if (allowDeviceCredential && Build.VERSION.SDK_INT in 28..29) {
                // AndroidX rejects STRONG | DEVICE_CREDENTIAL on these versions.
                // Keep the strength requirement by using the system credential screen.
                val keyguard = context.getSystemService(KeyguardManager::class.java)
                @Suppress("DEPRECATION")
                val intent = keyguard.createConfirmDeviceCredentialIntent(
                    resources.getString(R.string.authenticate_to_reset_pin_title),
                    resources.getString(R.string.use_device_pin_pattern_password_subtitle)
                )
                if (intent != null) {
                    showing = true
                    credentialLauncher.launch(intent)
                } else Toast.makeText(context, R.string.biometric_unavailable, Toast.LENGTH_LONG).show()
            } else {
                val authenticators = if (allowDeviceCredential) {
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
                } else BiometricManager.Authenticators.BIOMETRIC_WEAK
                val available = BiometricManager.from(context).canAuthenticate(authenticators) ==
                    BiometricManager.BIOMETRIC_SUCCESS
                if (available) {
                    val builder = BiometricPrompt.PromptInfo.Builder()
                        .setTitle(resources.getString(if (allowDeviceCredential)
                            R.string.authenticate_to_reset_pin_title else R.string.biometric_authentication_cd))
                        .setAllowedAuthenticators(authenticators)
                    if (allowDeviceCredential) {
                        builder.setSubtitle(resources.getString(R.string.use_device_pin_pattern_password_subtitle))
                    } else builder.setNegativeButtonText(resources.getString(R.string.cancel_button))
                    showing = true
                    prompt.authenticate(builder.build())
                } else Toast.makeText(context, R.string.biometric_unavailable, Toast.LENGTH_LONG).show()
            }
        } catch (_: RuntimeException) {
            showing = false
            Toast.makeText(context, R.string.biometric_unavailable, Toast.LENGTH_LONG).show()
        }
    }
}

@Composable
fun BiometricOnlySetupButton(onSuccess: () -> Unit) {
    val authenticate = rememberBiometricAuthentication(enableBiometricOnly = true, onSuccess = onSuccess)
    Box(Modifier.fillMaxWidth().navigationBarsPadding(), contentAlignment = Alignment.Center) {
        TextButton(onClick = authenticate) { Text(stringResource(R.string.biometric_only_title)) }
    }
}
