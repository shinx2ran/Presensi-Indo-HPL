package com.indohpl.presensi.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.indohpl.presensi.data.CheckResult
import com.indohpl.presensi.data.CheckState
import com.indohpl.presensi.data.FixAction
import com.indohpl.presensi.data.Inspector
import com.indohpl.presensi.data.Repository
import com.indohpl.presensi.ui.theme.Amber
import com.indohpl.presensi.ui.theme.Green
import kotlinx.coroutines.launch

/**
 * Pemeriksaan aplikasi (Inspector & Fixer): memeriksa kesehatan aplikasi + HP dan menawarkan tombol perbaikan.
 * Khusus owner (ada di dalam Pengaturan berkunci PIN).
 */
@Composable
fun InspectorSection(repository: Repository, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val inspector = remember(repository) { Inspector(context, repository) }
    var results by remember { mutableStateOf<List<CheckResult>>(emptyList()) }
    var running by remember { mutableStateOf(false) }

    suspend fun load() {
        running = true
        try {
            results = inspector.run()
        } finally {
            running = false
        }
    }

    fun rerun() {
        scope.launch { load() }
    }

    LaunchedEffect(Unit) { load() }

    val storagePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) onMessage("Izin penyimpanan ditolak.")
        rerun()
    }

    fun openIntent(intent: Intent) {
        runCatching { context.startActivity(intent) }
            .onFailure { onMessage("Tidak bisa membuka layar yang diminta.") }
    }

    fun handleFix(action: FixAction) {
        if (!action.needsActivity) {
            scope.launch {
                onMessage(inspector.fix(action))
                load()
            }
            return
        }
        when (action) {
            FixAction.OPEN_APP_SETTINGS -> openIntent(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
            )
            FixAction.OPEN_DATE_SETTINGS -> openIntent(Intent(android.provider.Settings.ACTION_DATE_SETTINGS))
            FixAction.REQUEST_STORAGE_PERMISSION -> storagePermissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            FixAction.OPEN_UPDATE_LINK -> openIntent(Intent(Intent.ACTION_VIEW, Uri.parse(Inspector.UPDATE_URL)))
            else -> Unit
        }
    }

    val attention = results.count { it.state != CheckState.OK }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Pemeriksaan aplikasi", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            if (running) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Memeriksa…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (results.isNotEmpty()) {
                if (attention == 0) {
                    Text("Semua baik ✓", color = Green, fontWeight = FontWeight.SemiBold)
                } else {
                    Text("$attention hal perlu perhatian", color = Amber, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        results.forEach { r ->
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    val (icon, tint) = when (r.state) {
                        CheckState.OK -> Icons.Filled.CheckCircle to Green
                        CheckState.WARN -> Icons.Filled.Warning to Amber
                        CheckState.FAIL -> Icons.Filled.Error to MaterialTheme.colorScheme.error
                    }
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(r.title, fontWeight = FontWeight.SemiBold)
                        Text(r.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val fix = r.fix
                        if (fix != null) {
                            OutlinedButton(onClick = { handleFix(fix) }, enabled = !running) {
                                Text(r.fixLabel ?: "Perbaiki")
                            }
                        }
                    }
                }
            }
        }

        OutlinedButton(onClick = { rerun() }, enabled = !running, modifier = Modifier.fillMaxWidth()) {
            Text(if (running) "Memeriksa…" else "Periksa ulang")
        }
    }
}
