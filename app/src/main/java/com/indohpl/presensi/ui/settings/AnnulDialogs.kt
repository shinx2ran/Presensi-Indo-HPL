package com.indohpl.presensi.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.indohpl.presensi.data.AttendanceRecord
import com.indohpl.presensi.data.Repository
import com.indohpl.presensi.domain.AnnulRule
import com.indohpl.presensi.domain.RecapFormatter
import com.indohpl.presensi.ui.PlusChip
import com.indohpl.presensi.ui.longLabel
import com.indohpl.presensi.ui.theme.Green
import java.time.LocalDate
import kotlinx.coroutines.launch

/**
 * Dialog PIN owner untuk aksi khusus owner di luar tab Pengaturan (mis. anulir telat dari Beranda).
 * Memakai kunci PIN yang sama dengan gerbang Pengaturan (tersimpan permanen, lihat SettingsStore.checkOwnerPin).
 * [onVerified] menerima jumlah PIN salah sebelum berhasil, supaya pemanggil bisa memperingatkan owner.
 */
@Composable
fun OwnerPinDialog(
    repository: Repository,
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onVerified: (failedBefore: Int) -> Unit,
) {
    val hasPin by repository.settingsStore.hasOwnerPin.collectAsStateWithLifecycle(null)
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val lock = rememberPinLock(repository)
    val locked = lock.isLocked

    if (hasPin == false) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(title) },
            text = { Text("Belum ada PIN owner. Buat PIN dulu di tab Pengaturan, lalu coba lagi.") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Mengerti") } },
        )
        return
    }

    fun submit() {
        if (locked || busy || pin.isEmpty()) return
        busy = true
        scope.launch {
            val r = repository.settingsStore.checkOwnerPin(pin)
            pin = ""
            busy = false
            when {
                r.ok -> onVerified(r.failedBefore)
                r.lockedMsLeft > 0L -> { lock.lockFor(r.lockedMsLeft); error = "Terlalu banyak percobaan." }
                else -> error = "PIN salah (${r.attemptsLeft} percobaan lagi)"
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(message)
                Spacer(Modifier.height(12.dp))
                PinField(pin, { pin = it }, "PIN owner", enabled = !locked && hasPin == true, onDone = { submit() })
                val shown = if (locked) lock.label() else error
                shown?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { submit() }, enabled = !locked && !busy && pin.isNotEmpty() && hasPin == true) { Text("Lanjut") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } },
    )
}

private val REASONS = listOf(AnnulRule.DEFAULT_REASON, "HP kasir belum bisa dipakai")
private const val OTHER = "Lainnya"

/**
 * Konfirmasi anulir telat (dipanggil setelah owner terverifikasi). Menampilkan berapa saldo yang masuk,
 * meminta alasan, lalu menyimpan. [onDone] menerima pesan singkat untuk snackbar.
 */
@Composable
fun AnnulLateDialog(
    repository: Repository,
    employeeName: String,
    record: AttendanceRecord,
    onDismiss: () -> Unit,
    onDone: (message: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var change by remember(record) { mutableStateOf<Long?>(null) }
    LaunchedEffect(record) { change = repository.annulSaldoChange(record) }
    var choice by remember { mutableStateOf(REASONS.first()) }
    var other by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val reason = if (choice == OTHER) other.trim() else choice
    val date = runCatching { LocalDate.parse(record.date) }.getOrNull()

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Anulir telat — $employeeName") },
        text = {
            Column {
                Text(
                    "Tercatat ${record.timeIn.take(5)}, telat ${record.lateMinutes} menit" +
                        (date?.let { " (${it.longLabel()})" } ?: "") +
                        ". Setelah dianulir, hari itu dihitung tepat waktu.",
                )
                Spacer(Modifier.height(10.dp))
                val c = change
                when {
                    c == null -> Text("Menghitung saldo…", style = MaterialTheme.typography.bodySmall)
                    c > 0L -> Row(verticalAlignment = Alignment.CenterVertically) {
                        PlusChip(c)
                        Spacer(Modifier.width(8.dp))
                        Text("masuk saldo $employeeName", style = MaterialTheme.typography.bodyMedium, color = Green, fontWeight = FontWeight.SemiBold)
                    }
                    else -> Text(
                        "Saldo tidak berubah (hari itu libur, di luar hari kerja, atau saldo sudah maksimal).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text("Alasan", style = MaterialTheme.typography.labelLarge)
                Column(Modifier.selectableGroup()) {
                    (REASONS + OTHER).forEach { option ->
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(selected = choice == option, onClick = { choice = option }, role = Role.RadioButton),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = choice == option, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(option, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (choice == OTHER) {
                    OutlinedTextField(
                        value = other,
                        onValueChange = { other = it.take(80) },
                        label = { Text("Tulis alasan") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Jam absen dan menit telat asli tetap tersimpan sebagai jejak. Anulir bisa dibatalkan kapan saja.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving && reason.isNotEmpty(),
                onClick = {
                    saving = true
                    scope.launch {
                        val saved = repository.annulLate(record.id, reason)
                        val c = change ?: 0L
                        onDone(
                            when {
                                saved == null -> "Catatan $employeeName sudah berubah, tidak dianulir"
                                c > 0L -> "Telat $employeeName dianulir · ${RecapFormatter.rupiahPlus(c)} masuk saldo"
                                else -> "Telat $employeeName dianulir"
                            },
                        )
                    }
                },
            ) { Text("Anulir") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("Batal") } },
    )
}

/** Konfirmasi membatalkan anulir: catatan kembali telat dengan menit asli. */
@Composable
fun UndoAnnulDialog(
    repository: Repository,
    employeeName: String,
    record: AttendanceRecord,
    onDismiss: () -> Unit,
    onDone: (message: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var change by remember(record) { mutableStateOf<Long?>(null) }
    LaunchedEffect(record) { change = repository.annulSaldoChange(record) }
    var saving by remember { mutableStateOf(false) }
    val minutes = record.annulledLateMinutes ?: 0

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Batalkan anulir — $employeeName?") },
        text = {
            Column {
                Text("Catatan ${record.timeIn.take(5)} kembali menjadi telat $minutes menit.")
                if (record.annulReason.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text("Alasan anulir: ${record.annulReason}", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(10.dp))
                val c = change
                Text(
                    when {
                        c == null -> "Menghitung saldo…"
                        c < 0L -> "Saldo $employeeName berkurang ${RecapFormatter.rupiah(-c)}."
                        else -> "Saldo $employeeName tidak berubah."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    saving = true
                    scope.launch {
                        val saved = repository.undoAnnul(record.id)
                        onDone(if (saved == null) "Catatan $employeeName sudah berubah" else "Anulir $employeeName dibatalkan")
                    }
                },
            ) { Text("Batalkan anulir") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("Tutup") } },
    )
}
