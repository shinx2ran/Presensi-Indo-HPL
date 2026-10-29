package com.indohpl.presensi.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.indohpl.presensi.data.Repository
import com.indohpl.presensi.domain.RecapFormatter
import com.indohpl.presensi.domain.Retention
import com.indohpl.presensi.ui.longLabel
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.launch

/** Pengaturan pembersihan otomatis (self-regulating): data lama dihapus sendiri. */
@Composable
fun RetentionSection(repository: Repository, onMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val settings by repository.settingsStore.settings.collectAsStateWithLifecycle(null)
    val oldest by repository.attendanceDao.observeOldestDate().collectAsStateWithLifecycle(null)
    var months by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }

    LaunchedEffect(settings) {
        val s = settings ?: return@LaunchedEffect
        if (!loaded) { months = s.retentionMonths.toString(); loaded = true }
    }

    val today = LocalDate.now()
    val m = months.toIntOrNull()?.coerceIn(Retention.MIN_MONTHS, Retention.MAX_MONTHS) ?: (settings?.retentionMonths ?: 2)
    val cutoff = Retention.cutoffDate(today, m)
    val (nextDate, nextMonth) = Retention.nextCleanup(today, m)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Pembersihan otomatis", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "Aplikasi menjaga dirinya sendiri: catatan presensi dan foto yang lebih lama dari batas di bawah dihapus otomatis (dicek setiap hari). Ekspor rekap sebelum bulan itu terhapus.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = months, onValueChange = { months = it.filter(Char::isDigit).take(2) },
            label = { Text("Simpan data selama (bulan)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            supportingText = {
                Text(
                    "Saat ini disimpan: ${RecapFormatter.monthLabel(YearMonth.from(cutoff))} sampai ${RecapFormatter.monthLabel(YearMonth.from(today))}. " +
                        "Pembersihan berikutnya ${nextDate.longLabel()} menghapus ${RecapFormatter.monthLabel(nextMonth)}." +
                        (oldest?.let { "\nData tertua tersimpan: $it." } ?: "\nBelum ada data."),
                )
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val v = months.toIntOrNull()
                    if (v == null || v < Retention.MIN_MONTHS || v > Retention.MAX_MONTHS) {
                        onMessage("Isi ${Retention.MIN_MONTHS} sampai ${Retention.MAX_MONTHS} bulan"); return@Button
                    }
                    scope.launch {
                        val s = repository.settingsStore.current()
                        repository.settingsStore.save(s.copy(retentionMonths = v))
                        onMessage("Batas simpan $v bulan disimpan")
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text("Simpan batas") }
            OutlinedButton(onClick = { confirm = true }, modifier = Modifier.weight(1f)) { Text("Bersihkan sekarang") }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Hapus data lama sekarang?") },
            text = { Text("Semua catatan dan foto sebelum ${cutoff.longLabel()} akan dihapus permanen. Pastikan rekap bulan-bulan itu sudah diekspor.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    scope.launch {
                        val n = repository.cleanupOldData()
                        onMessage(if (n > 0) "$n catatan lama dihapus" else "Tidak ada data lama")
                    }
                }) { Text("Hapus") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Batal") } },
        )
    }
}
