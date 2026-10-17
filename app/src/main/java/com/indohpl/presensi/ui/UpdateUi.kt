package com.indohpl.presensi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.indohpl.presensi.PresensiApp
import com.indohpl.presensi.util.AppUpdater
import com.indohpl.presensi.util.UpdateState
import kotlinx.coroutines.launch

/**
 * Kartu pembaruan OTA. Di Beranda hanya muncul bila ada versi baru / sedang diunduh / siap dipasang / gagal.
 * [showAll] = true (Pengaturan) juga menampilkan "memeriksa…" dan "sudah terbaru".
 */
@Composable
fun UpdateBanner(showAll: Boolean = false) {
    val context = LocalContext.current
    val app = context.applicationContext as PresensiApp
    val state by AppUpdater.state.collectAsStateWithLifecycle()
    var needPermission by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { AppUpdater.checkIfDue(app) }

    when (val s = state) {
        is UpdateState.Available -> UpdateCard(
            title = "Versi baru ${s.manifest.versionName} tersedia",
            body = listOfNotNull(
                s.manifest.notes.takeIf { it.isNotBlank() },
                if (s.manifest.sizeBytes > 0) "Ukuran ${s.manifest.sizeBytes / 1_000_000} MB. Data presensi tetap aman." else "Data presensi tetap aman.",
            ).joinToString("\n"),
        ) {
            Button(onClick = { AppUpdater.download(app.appScope, app, s.manifest) }) { Text("Perbarui") }
        }

        is UpdateState.Downloading -> UpdateCard(
            title = "Mengunduh pembaruan ${s.manifest.versionName}…" + if (s.progress >= 0f) " ${(s.progress * 100).toInt()}%" else "",
            body = "Boleh tetap memakai aplikasi selama mengunduh.",
        ) {
            if (s.progress >= 0f) {
                LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        is UpdateState.Ready -> UpdateCard(
            title = "Pembaruan ${s.manifest.versionName} siap dipasang",
            body = if (needPermission) {
                "Aktifkan \"Izinkan dari sumber ini\" untuk aplikasi ini, kembali ke sini, lalu tekan Pasang lagi."
            } else {
                "Tekan Pasang, lalu Perbarui di dialog Android. Aplikasi akan tertutup sebentar; data presensi tetap ada."
            },
        ) {
            Button(onClick = { needPermission = !AppUpdater.install(context, s.file) }) { Text("Pasang") }
        }

        is UpdateState.Failed -> UpdateCard(title = s.message, body = null, isError = true) {
            TextButton(onClick = {
                val m = s.manifest
                if (m != null) AppUpdater.download(app.appScope, app, m) else app.appScope.launch { AppUpdater.check(app, userInitiated = true) }
            }) { Text("Coba lagi") }
        }

        UpdateState.Checking -> if (showAll) Text("Memeriksa pembaruan…", style = MaterialTheme.typography.bodySmall)
        is UpdateState.UpToDate -> if (showAll) Text(s.message, style = MaterialTheme.typography.bodySmall)
        UpdateState.Idle -> Unit
    }
}

/** Bagian Pengaturan: versi terpasang + tombol cek manual + status. */
@Composable
fun UpdateSection() {
    val app = LocalContext.current.applicationContext as PresensiApp
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Pembaruan aplikasi", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "Versi terpasang ${AppUpdater.installedVersionName} (${AppUpdater.installedVersionCode}). " +
                "Aplikasi memeriksa versi baru otomatis saat dibuka; pemasangan cukup dengan beberapa ketukan, tanpa mengunduh dari browser.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = { app.appScope.launch { AppUpdater.check(app, userInitiated = true) } },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Cek pembaruan sekarang") }
        UpdateBanner(showAll = true)
    }
}

@Composable
private fun UpdateCard(title: String, body: String?, isError: Boolean = false, action: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            body?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            action()
        }
    }
}
