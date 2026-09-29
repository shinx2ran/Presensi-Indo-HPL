package com.indohpl.presensi.util

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** Hasil verifikasi biometrik. */
sealed class BiometricResult {
    data object Success : BiometricResult()
    data class Failed(val message: String) : BiometricResult()
}

object Biometric {
    /**
     * Sidik jari / wajah / PIN HP. Dipakai BIOMETRIC_WEAK | DEVICE_CREDENTIAL supaya
     * face unlock (yang di banyak HP tergolong "weak") tetap bisa dipakai, dan PIN sebagai cadangan.
     */
    private const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    /** null = bisa dipakai, selain itu pesan kenapa tidak bisa. */
    fun availabilityProblem(context: Context): String? =
        when (BiometricManager.from(context).canAuthenticate(AUTHENTICATORS)) {
            BiometricManager.BIOMETRIC_SUCCESS -> null
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                "Belum ada sidik jari / wajah / PIN terdaftar di HP ini. Daftarkan dulu di Pengaturan HP."
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "HP ini tidak punya sensor biometrik."
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "Sensor biometrik sedang tidak tersedia."
            else -> "Biometrik tidak bisa dipakai di HP ini."
        }

    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onResult: (BiometricResult) -> Unit,
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onResult(BiometricResult.Success)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onResult(BiometricResult.Failed(errString.toString()))
                }

                override fun onAuthenticationFailed() {
                    // Satu percobaan gagal; prompt tetap terbuka untuk mencoba lagi.
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(AUTHENTICATORS)
            .setConfirmationRequired(false)
            .build()
        prompt.authenticate(info)
    }
}
