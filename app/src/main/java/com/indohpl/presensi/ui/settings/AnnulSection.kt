package com.indohpl.presensi.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.indohpl.presensi.data.AttendanceRecord
import com.indohpl.presensi.data.Repository
import com.indohpl.presensi.domain.AnnulRule
import com.indohpl.presensi.ui.NoGainChip
import com.indohpl.presensi.ui.shortLabel
import com.indohpl.presensi.ui.theme.Blue
import java.time.LocalDate

private const val MAX_ROWS = 15

/** Owner: anulir (atau batalkan anulir) telat bulan ini dan bulan lalu. Tab Pengaturan sudah di balik PIN owner. */
@Composable
fun AnnulSection(repository: Repository, onMessage: (String) -> Unit) {
    val today = LocalDate.now()
    val from = today.minusMonths(1).withDayOfMonth(1)
    val records by remember(from, today) { repository.observeLateOrAnnulled(from, today) }
        .collectAsStateWithLifecycle(emptyList())
    val employees by repository.observeEmployees().collectAsStateWithLifecycle(emptyList())
    val names = remember(employees) { employees.associate { it.id to it.name } }

    var showAll by remember { mutableStateOf(false) }
    var annulTarget by remember { mutableStateOf<AttendanceRecord?>(null) }
    var undoTarget by remember { mutableStateOf<AttendanceRecord?>(null) }

    fun nameOf(r: AttendanceRecord) = names[r.employeeId] ?: "Karyawan #${r.employeeId}"

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Anulir telat", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "Untuk hari ketika karyawan sebenarnya datang tepat waktu tetapi belum bisa absen, mis. pintu toko belum dibuka. " +
                "Telat yang dianulir dihitung tepat waktu dan saldo bertambah. " +
                "Jam dan menit telat asli tetap tersimpan, dan anulir bisa dibatalkan.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (records.isEmpty()) {
            Text(
                "Tidak ada catatan telat bulan ini dan bulan lalu.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val shown = if (showAll) records else records.take(MAX_ROWS)
            shown.forEach { r ->
                Column(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${LocalDate.parse(r.date).shortLabel()} · ${nameOf(r)} · ${r.timeIn.take(5)}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        if (AnnulRule.canAnnul(r)) {
                            NoGainChip("telat ${r.lateMinutes} mnt")
                            TextButton(onClick = { annulTarget = r }) { Text("Anulir") }
                        } else if (AnnulRule.canUndo(r)) {
                            TextButton(onClick = { undoTarget = r }) { Text("Batalkan") }
                        }
                    }
                    if (r.annulledLateMinutes != null) {
                        Text(
                            "dianulir · telat ${r.annulledLateMinutes} mnt",
                            style = MaterialTheme.typography.labelMedium, color = Blue,
                        )
                        if (r.annulReason.isNotBlank()) {
                            Text(
                                r.annulReason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
            }
            if (!showAll && records.size > MAX_ROWS) {
                TextButton(onClick = { showAll = true }) { Text("Tampilkan semua (${records.size})") }
            }
        }
    }

    annulTarget?.let { r ->
        AnnulLateDialog(
            repository = repository, employeeName = nameOf(r), record = r,
            onDismiss = { annulTarget = null },
            onDone = { msg -> annulTarget = null; onMessage(msg) },
        )
    }
    undoTarget?.let { r ->
        UndoAnnulDialog(
            repository = repository, employeeName = nameOf(r), record = r,
            onDismiss = { undoTarget = null },
            onDone = { msg -> undoTarget = null; onMessage(msg) },
        )
    }
}
