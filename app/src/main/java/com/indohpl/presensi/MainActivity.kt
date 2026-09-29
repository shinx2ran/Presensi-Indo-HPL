package com.indohpl.presensi

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.indohpl.presensi.ui.PresensiNavHost
import com.indohpl.presensi.ui.theme.PresensiTheme

/**
 * FragmentActivity (bukan ComponentActivity) karena BiometricPrompt dari androidx.biometric
 * membutuhkan FragmentActivity.
 */
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository = (application as PresensiApp).repository
        setContent {
            PresensiTheme {
                PresensiNavHost(repository = repository)
            }
        }
    }
}
