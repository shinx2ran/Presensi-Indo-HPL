package com.indohpl.presensi.ui.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.indohpl.presensi.data.Inspector
import com.indohpl.presensi.data.Repository
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Memilih satu file lewat pemilih file sistem, langsung membuka folder cadangan bila didukung. */
private class OpenBackupFile(private val initialUri: Uri) : ActivityResultContract<Array<String>, Uri?>() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, input)
            if (Build.VERSION.SDK_INT >= 26) putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
        }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}

private val backupTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

/**
 * Cadangan data: cadangkan sekarang, pulihkan dari file cadangan, dan unduh versi terbaru.
 * Khusus owner (ada di dalam Pengaturan berkunci PIN).
 */
@Composable
fun BackupSection(repository: Repository, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val last by repository.settingsStore.lastBackup.collectAsStateWithLifecycle(null)
    var busy by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<String?>(null) }
    var pickedText by remember { mutableStateOf<String?>(null) }
    var applySettings by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        remember(repository) { OpenBackupFile(repository.localBackup.pickerInitialUri()) },
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                        ?: error("tidak bisa dibuka")
                }
            }
            val content = text.getOrNull()
            if (content == null) {
                onMessage("File tidak bisa dibaca: ${text.exceptionOrNull()?.message ?: "?"}")
            } else {
                applySettings = false
                pickedText = content
            }
        }
    }

    fun restore(text: String, withSettings: Boolean) {
        busy = true
        scope.launch {
            try {
                val r = repository.restoreFromBackup(text, withSettings)
                report = buildString {
                    append("Masuk ${r.imported} catatan · sudah ada ${r.duplicates} · karyawan ditambah ${r.employeesAdded} · libur ${r.holidays}")
                    if (r.tooOld > 0) append(" · terlalu lama ${r.tooOld}")
                    if (r.unmatched > 0) append(" · nama karyawan tidak cocok ${r.unmatched}")
                    if (r.settingsApplied) append(" · pengaturan dipakai")
                }
                onMessage("Pemulihan selesai: ${r.imported} catatan masuk")
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                report = e.message ?: "File cadangan tidak sah."
                onMessage("Pemulihan gagal")
            } catch (e: Exception) {
                report = "Pemulihan gagal: ${e.message ?: e.javaClass.simpleName}"
                onMessage("Pemulihan gagal")
            } finally {
                busy = false
            }
        }
    }

    val lastLabel = last?.let { (at, file) ->
        "Cadangan terakhir: ${Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(backupTimeFormat)} · $file"
    } ?: "Belum ada cadangan"

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Cadangan data", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "Aplikasi menyimpan cadangan otomatis setiap ada absen ke folder Download/PresensiIndoHPL. File ini tetap ada walau aplikasi dihapus. Saat memasang ulang, pilih Pulihkan dari cadangan.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(lastLabel, style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(
            onClick = {
                busy = true
                scope.launch {
                    try {
                        repository.backupNow().fold(
                            onSuccess = { onMessage("Cadangan disimpan: $it") },
                            onFailure = { onMessage("Cadangan belum dibuat: ${it.message ?: "terjadi kesalahan"}") },
                        )
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Cadangkan sekarang") }
        OutlinedButton(
            onClick = { picker.launch(arrayOf("application/json", "*/*")) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "Memproses…" else "Pulihkan dari cadangan") }
        report?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        OutlinedButton(
            onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Inspector.UPDATE_URL))) }
                    .onFailure { onMessage("Tidak bisa membuka browser.") }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Unduh versi terbaru") }
        Text(
            "Buka di browser, unduh, lalu ketuk file untuk Perbarui. Data tetap.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // Disembunyikan selama lapisan PIN tampil (lihat LocalOwnerRelocked); muncul lagi setelah PIN benar.
    pickedText?.takeIf { !LocalOwnerRelocked.current }?.let { text ->
        AlertDialog(
            onDismissRequest = { pickedText = null },
            title = { Text("Pulihkan data dari cadangan?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { applySettings = !applySettings },
                    ) {
                        Checkbox(checked = applySettings, onCheckedChange = { applySettings = it })
                        Text("Pakai juga pengaturan dari cadangan")
                    }
                    Text(
                        "Data yang sudah ada tidak dihapus; yang sama tidak digandakan.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pickedText = null
                    restore(text, applySettings)
                }) { Text("Pulihkan") }
            },
            dismissButton = { TextButton(onClick = { pickedText = null }) { Text("Batal") } },
        )
    }
}
