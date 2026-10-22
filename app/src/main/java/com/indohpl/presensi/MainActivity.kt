package com.indohpl.presensi

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.indohpl.presensi.cloud.CloudSync
import com.indohpl.presensi.ui.PresensiNavHost
import com.indohpl.presensi.ui.theme.PresensiTheme
import kotlinx.coroutines.launch

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

    override fun onResume() {
        super.onResume()
        // Setiap aplikasi dibuka/kembali ke depan: sinkron sekali dengan dashboard (bila HP tersambung).
        val app = application as PresensiApp
        app.appScope.launch {
            if (app.repository.settingsStore.currentCloud().isLinked) CloudSync.syncSoon(app)
        }
    }
}
