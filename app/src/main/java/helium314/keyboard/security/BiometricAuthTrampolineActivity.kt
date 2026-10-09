// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.security

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricPrompt as FrameworkBiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.LogCatcher

/**
 * BiometricAuthTrampolineActivity provides an invisible, zero-animation trampoline
 * allowing InputMethodService and background components to invoke BiometricPrompt
 * or device credential confirmation without window token errors.
 */
class BiometricAuthTrampolineActivity : ComponentActivity() {

    private var cancellationSignal: CancellationSignal? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra(EXTRA_AUTH_MODE) ?: MODE_BIOMETRIC
        val targetVault = intent.getStringExtra(EXTRA_TARGET_VAULT) ?: VAULT_SECURITY

        when (mode) {
            MODE_DEVICE_PIN -> promptDevicePin()
            else -> promptBiometricOrFallback(targetVault)
        }
    }

    private fun promptBiometricOrFallback(targetVault: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val promptBuilder = FrameworkBiometricPrompt.Builder(this)
                .setTitle(getString(R.string.vault_auth_biometric_prompt_title))
                .setSubtitle(getString(R.string.vault_auth_biometric_prompt_subtitle))

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                promptBuilder.setAllowedAuthenticators(
                    android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
            } else {
                promptBuilder.setNegativeButton(
                    getString(R.string.vault_auth_use_backup_method),
                    mainExecutor
                ) { _, _ ->
                    notifyResult(false, isBackupRequested = true)
                }
            }

            cancellationSignal = CancellationSignal()
            val biometricPrompt = promptBuilder.build()
            biometricPrompt.authenticate(
                cancellationSignal!!,
                mainExecutor,
                object : FrameworkBiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: FrameworkBiometricPrompt.AuthenticationResult?) {
                        super.onAuthenticationSucceeded(result)
                        startVaultSession(targetVault)
                        notifyResult(true, isBackupRequested = false)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                        super.onAuthenticationError(errorCode, errString)
                        LogCatcher.log('W', TAG, "Biometric error $errorCode: $errString")
                        // If user canceled or tapped negative button (BIOMETRIC_ERROR_CANCELED = 5, BIOMETRIC_ERROR_USER_CANCELED = 10, BIOMETRIC_ERROR_NEGATIVE_BUTTON = 13), fall back to backup method
                        if (errorCode == FrameworkBiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED ||
                            errorCode == FrameworkBiometricPrompt.BIOMETRIC_ERROR_CANCELED ||
                            errorCode == 13 /* BIOMETRIC_ERROR_NEGATIVE_BUTTON */) {
                            notifyResult(false, isBackupRequested = true)
                        } else {
                            notifyResult(false, isBackupRequested = false)
                        }
                    }

                    override fun onAuthenticationFailed() {
                        super.onAuthenticationFailed()
                        LogCatcher.log('W', TAG, "Biometric auth failed")
                    }
                }
            )
        } else {
            promptDevicePin()
        }
    }

    private fun promptDevicePin() {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (km != null && km.isDeviceSecure) {
            val intent = km.createConfirmDeviceCredentialIntent(
                getString(R.string.vault_auth_biometric_prompt_title),
                getString(R.string.vault_auth_biometric_prompt_subtitle)
            )
            if (intent != null) {
                @Suppress("DEPRECATION")
                startActivityForResult(intent, REQUEST_CODE_CONFIRM_CREDENTIAL)
                return
            }
        }
        notifyResult(false, isBackupRequested = true)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_CONFIRM_CREDENTIAL) {
            if (resultCode == RESULT_OK) {
                val targetVault = intent.getStringExtra(EXTRA_TARGET_VAULT) ?: VAULT_SECURITY
                startVaultSession(targetVault)
                notifyResult(true, isBackupRequested = false)
            } else {
                notifyResult(false, isBackupRequested = false)
            }
        }
    }

    private fun startVaultSession(targetVault: String) {
        when (targetVault) {
            VAULT_PRIVACY -> VaultSessionManager.startPrivacySession()
            VAULT_SETTINGS -> VaultSessionManager.startSettingsSession()
            else -> VaultSessionManager.startSecuritySession()
        }
    }

    private fun notifyResult(success: Boolean, isBackupRequested: Boolean) {
        cancellationSignal = null
        val callback = onAuthResultListener
        onAuthResultListener = null
        callback?.invoke(success, isBackupRequested)
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        cancellationSignal?.cancel()
        cancellationSignal = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BiometricAuthTrampoline"
        const val EXTRA_AUTH_MODE = "extra_auth_mode"
        const val EXTRA_TARGET_VAULT = "extra_target_vault"

        const val MODE_BIOMETRIC = "mode_biometric"
        const val MODE_DEVICE_PIN = "mode_device_pin"

        const val VAULT_SECURITY = "security"
        const val VAULT_PRIVACY = "privacy"
        const val VAULT_SETTINGS = "settings"

        private const val REQUEST_CODE_CONFIRM_CREDENTIAL = 901

        private var onAuthResultListener: ((success: Boolean, isBackupRequested: Boolean) -> Unit)? = null

        fun launch(
            context: Context,
            mode: String,
            targetVault: String,
            onResult: (success: Boolean, isBackupRequested: Boolean) -> Unit
        ) {
            onAuthResultListener = onResult
            val intent = Intent(context, BiometricAuthTrampolineActivity::class.java).apply {
                putExtra(EXTRA_AUTH_MODE, mode)
                putExtra(EXTRA_TARGET_VAULT, targetVault)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
            }
            context.startActivity(intent)
        }
    }
}
