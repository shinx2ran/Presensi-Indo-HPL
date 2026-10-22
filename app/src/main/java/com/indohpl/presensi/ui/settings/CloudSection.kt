package com.indohpl.presensi.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.indohpl.presensi.Site
import com.indohpl.presensi.cloud.CloudEndpoint
import com.indohpl.presensi.cloud.CloudSync
import com.indohpl.presensi.data.CloudConfig
import com.indohpl.presensi.data.CloudState
import com.indohpl.presensi.data.Repository
import com.indohpl.presensi.ui.LOCALE_ID
import com.indohpl.presensi.ui.theme.Green
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

private val SYNC_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", LOCALE_ID)

private fun formatTime(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(SYNC_TIME)

/** Kode pasangan: huruf/angka saja (tanda hubung & spasi boleh diketik), huruf besar. */
private fun normalizeCode(code: String): String = code.filter { it.isLetterOrDigit() }.uppercase()

/**
 * Sambungan ke dashboard owner (Supabase). Khusus owner (di dalam Pengaturan berkunci PIN).
 * Belum tersambung: isi nama HP + kode dari dashboard. Tersambung: status sinkron dan tombol-tombolnya.
 */
@Composable
fun CloudSection(repository: Repository, onMessage: (String) -> Unit) {
    val config by repository.settingsStore.cloud.collectAsStateWithLifecycle(null)
    val state by repository.settingsStore.cloudState.collectAsStateWithLifecycle(CloudState())
    val unsynced by repository.attendanceDao.observeUnsyncedCount().collectAsStateWithLifecycle(0)
    val status by CloudSync.status.collectAsStateWithLifecycle()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Dashboard owner (cloud)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        val c = config
        when {
            c == null -> Unit // masih memuat
            c.isLinked -> LinkedPanel(repository, c, state, unsynced, status.running, onMessage)
            else -> PairPanel(repository, c, state, onMessage)
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun PairPanel(repository: Repository, config: CloudConfig, state: CloudState, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var label by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var pairing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(config.deviceLabel) {
        if (label.isBlank()) label = config.deviceLabel.ifBlank { Site.defaultDeviceLabel }
    }

    fun pair() {
        val normalized = normalizeCode(code)
        if (label.isBlank()) { error = "Isi nama HP dulu, contoh ${Site.defaultDeviceLabel}."; return }
        if (normalized.length != 8) { error = "Kode harus 8 huruf/angka, contoh K7P2-9XQM."; return }
        error = null
        pairing = true
        scope.launch {
            val err = CloudSync.pair(context, repository, normalized, label.trim())
            pairing = false
            if (err == null) {
                code = ""
                onMessage("${Site.device} terhubung ke dashboard. Presensi mulai dikirim.")
            } else {
                error = err
            }
        }
    }

    Text(
        "Hubungkan ${Site.device} ini ke dashboard owner supaya presensi (jam masuk, status, koordinat GPS) terkirim otomatis " +
            "dan pengaturan bisa diubah dari jauh. Foto selfie tidak dikirim; foto tetap di HP ini.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        "Buka dashboard di HP/laptop owner, pilih ${Site.name}, lalu buat kode untuk HP baru (berlaku 15 menit):",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SelectionContainer {
        Text(CloudEndpoint.DASHBOARD_URL, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
    if (state.lastMessageIsError && state.lastMessage.isNotBlank()) {
        Text(state.lastMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    OutlinedTextField(
        value = label, onValueChange = { label = it.take(40) },
        label = { Text("Nama HP") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(Site.defaultDeviceLabel) },
        supportingText = { Text("Tampil di dashboard, mis. ${Site.defaultDeviceLabel}.") },
    )
    OutlinedTextField(
        value = code,
        onValueChange = { v -> code = v.uppercase().filter { it.isLetterOrDigit() || it == '-' || it == ' ' }.take(12) },
        label = { Text("Kode dari dashboard") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("K7P2-9XQM") },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Done,
        ),
        isError = error != null,
        supportingText = { error?.let { Text(it) } },
    )
    Button(onClick = { pair() }, enabled = !pairing, modifier = Modifier.fillMaxWidth()) {
        Text(if (pairing) "Menghubungkan…" else "Hubungkan")
    }
}

@Composable
private fun LinkedPanel(
    repository: Repository,
    config: CloudConfig,
    state: CloudState,
    unsynced: Int,
    running: Boolean,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmDisconnect by remember { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Text(
        "Terhubung sebagai ${config.deviceLabel.ifBlank { Site.device }} · ${config.siteName.ifBlank { Site.name }}",
        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Green,
    )
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            when {
                running -> "Sedang sinkron…"
                state.lastSyncAt > 0 -> "Terakhir sinkron ${formatTime(state.lastSyncAt)}"
                else -> "Belum pernah sinkron (menunggu internet)"
            },
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            if (unsynced > 0) "$unsynced presensi menunggu dikirim" else "Semua presensi sudah terkirim",
            style = MaterialTheme.typography.bodySmall,
            color = if (unsynced > 0) muted else Green,
        )
        Text(
            (if (state.appliedVersion > 0) "Versi pengaturan ${state.appliedVersion}" else "Pengaturan belum pernah diambil dari dashboard") +
                if (state.settingsDirty) " · perubahan di HP belum terkirim" else "",
            style = MaterialTheme.typography.bodySmall, color = muted,
        )
        if (state.lastMessage.isNotBlank()) {
            Text(
                state.lastMessage + if (state.lastMessageAt > 0) " (${formatTime(state.lastMessageAt)})" else "",
                style = MaterialTheme.typography.bodySmall,
                color = if (state.lastMessageIsError) MaterialTheme.colorScheme.error else muted,
            )
        }
        Text(
            "Foto selfie tidak dikirim ke cloud. Dashboard: ${CloudEndpoint.DASHBOARD_URL}",
            style = MaterialTheme.typography.bodySmall, color = muted,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { CloudSync.syncSoon(context, force = true); onMessage("Sinkronisasi dijalankan") },
            enabled = !running,
            modifier = Modifier.weight(1f),
        ) { Text("Sinkron sekarang") }
        OutlinedButton(
            onClick = { scope.launch { repository.resyncAll(); onMessage("Semua presensi akan dikirim ulang") } },
            modifier = Modifier.weight(1f),
        ) { Text("Kirim ulang semua") }
    }
    TextButton(onClick = { confirmDisconnect = true }, modifier = Modifier.fillMaxWidth()) {
        Text("Putuskan HP ini", color = MaterialTheme.colorScheme.error)
    }

    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("Putuskan ${Site.device} ini?") },
            text = {
                Text(
                    "Presensi berikutnya tidak dikirim lagi ke dashboard. Data presensi dan foto di HP tetap ada. " +
                        "Bila ada internet, HP ini otomatis hilang dari daftar HP di dashboard. Untuk menghubungkan lagi perlu kode baru dari dashboard.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDisconnect = false
                    scope.launch {
                        val serverDone = CloudSync.disconnect(repository)
                        onMessage(
                            if (serverDone) "${Site.device} diputus dari dashboard"
                            else "${Site.device} diputus di HP. Tidak ada internet: hapus juga di dashboard, tab HP.",
                        )
                    }
                }) { Text("Putuskan", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("Batal") } },
        )
    }
}
