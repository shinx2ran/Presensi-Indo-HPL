package com.indohpl.presensi.ui.settings

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.indohpl.presensi.data.Repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Impor file CSV detail dari aplikasi versi lama (pindah HP / pindah ke APK berkunci permanen).
 * Khusus owner (ada di dalam Pengaturan berkunci PIN).
 */
@Composable
fun ImportSection(repository: Repository, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val picked = withContext(Dispatchers.IO) {
                uris.map { uri ->
                    val name = runCatching {
                        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                    }.getOrNull() ?: uri.lastPathSegment ?: "file"
                    name to runCatching {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                            ?: error("tidak bisa dibuka")
                    }
                }
            }
            val readErrors = picked.filter { it.second.isFailure }
                .map { (n, t) -> "$n: tidak bisa dibaca (${t.exceptionOrNull()?.message ?: "?"}). Unduh dulu filenya, lalu impor lagi." }
            val r = repository.importCsv(picked.mapNotNull { (n, t) -> t.getOrNull()?.let { n to it } })
            busy = false
            report = buildString {
                readErrors.forEach { append(it).append('\n') }
                append("Masuk: ${r.imported} catatan.")
                if (r.duplicates > 0) append(" Sudah ada (dilewati): ${r.duplicates}.")
                if (r.tooOld > 0) append(" Lebih lama dari batas simpan (dilewati): ${r.tooOld}.")
                if (r.unknownNames.isNotEmpty()) append(" Nama tidak dikenal: ${r.unknownNames.joinToString()}.")
                if (r.errors.isNotEmpty()) append("\n" + r.errors.take(5).joinToString("\n") + if (r.errors.size > 5) "\n…dan ${r.errors.size - 5} lainnya" else "")
            }
            onMessage(if (readErrors.isEmpty()) "Impor selesai: ${r.imported} catatan masuk" else "Ada file yang gagal dibaca, lihat keterangan")
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Impor data dari versi lama", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "Pilih file \"presensi_YYYY-MM_detail.csv\" yang diekspor dari aplikasi lama (boleh beberapa bulan sekaligus). Catatan absen, izin, sakit, telat, dan GPS ikut masuk. Foto selfie tidak ikut. Catatan yang sudah ada tidak digandakan.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = { picker.launch(arrayOf("text/*", "text/csv", "text/comma-separated-values", "application/csv", "application/vnd.ms-excel", "application/octet-stream")) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "Mengimpor…" else "Pilih file CSV detail") }
        report?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
