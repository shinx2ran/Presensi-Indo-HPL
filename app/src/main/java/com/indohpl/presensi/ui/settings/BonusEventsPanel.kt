package com.indohpl.presensi.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.indohpl.presensi.data.BonusEvent
import com.indohpl.presensi.data.BonusEventType
import com.indohpl.presensi.data.Repository
import com.indohpl.presensi.ui.shortLabel
import com.indohpl.presensi.ui.theme.Amber
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.launch

private const val LOCKED_MESSAGE = "Bulan sudah ditandai dibayar, catatan tidak bisa diubah"

/** Owner: catat teguran (Akong / Koko) dan "setengah hari" untuk bonus penjualan bulan [Repository.SalesBonusState.ym]. */
@Composable
fun BonusEventsPanel(repository: Repository, state: Repository.SalesBonusState, onMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val names = remember(state.employees) { state.employees.associate { it.id to it.name } }
    fun nameOf(id: Long) = names[id] ?: "Karyawan #$id"

    var addType by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<BonusEvent?>(null) }
    val events = remember(state.events) { state.events.sortedWith(compareByDescending<BonusEvent> { it.date }.thenByDescending { it.createdAt }) }
    val canAdd = !state.paid && !state.ym.isAfter(YearMonth.now()) && state.employees.isNotEmpty()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Teguran & setengah hari", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "Teguran hanya dari Akong atau Koko. Teguran ke-2 dalam sebulan = tidak dapat bonus. " +
                "Setengah hari / pulang cepat dihitung ½ hari tidak masuk.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.paid) {
            Text(
                "Bulan sudah ditandai dibayar.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (events.isEmpty()) {
            Text(
                "Belum ada catatan bulan ini.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            events.forEach { e ->
                val isWarning = e.type == BonusEventType.TEGURAN
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${LocalDate.parse(e.date).shortLabel()} · ${nameOf(e.employeeId)}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        val who = if (e.byWhom.isNotBlank()) " · ${e.byWhom}" else ""
                        Text(
                            (if (isWarning) "Teguran" else "Setengah hari") + who,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isWarning) Amber else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (e.note.isNotBlank()) {
                            Text(
                                e.note,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (!state.paid) {
                        IconButton(onClick = { deleteTarget = e }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Hapus catatan")
                        }
                    }
                }
            }
        }
        if (!state.paid) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { addType = BonusEventType.TEGURAN }, enabled = canAdd) { Text("+ Teguran") }
                OutlinedButton(onClick = { addType = BonusEventType.SETENGAH_HARI }, enabled = canAdd) { Text("+ Setengah hari") }
            }
        }
    }

    // Disembunyikan selama lapisan PIN tampil (lihat LocalOwnerRelocked); muncul lagi setelah PIN benar.
    val relocked = LocalOwnerRelocked.current
    addType?.takeIf { !relocked }?.let { type ->
        AddBonusEventDialog(
            state = state, type = type,
            onDismiss = { addType = null },
            onConfirm = { employeeId, date, note, byWhom ->
                addType = null
                scope.launch {
                    val ok = repository.addBonusEvent(employeeId, date, type, note, byWhom)
                    onMessage(
                        when {
                            !ok -> LOCKED_MESSAGE
                            type == BonusEventType.TEGURAN -> "Teguran untuk ${nameOf(employeeId)} dicatat"
                            else -> "Setengah hari ${nameOf(employeeId)} dicatat"
                        },
                    )
                }
            },
        )
    }
    deleteTarget?.takeIf { !relocked }?.let { e ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Hapus catatan?") },
            text = {
                Text(
                    "${if (e.type == BonusEventType.TEGURAN) "Teguran" else "Setengah hari"} " +
                        "${nameOf(e.employeeId)} (${LocalDate.parse(e.date).shortLabel()}) akan dihapus.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    scope.launch {
                        val ok = repository.deleteBonusEvent(e)
                        onMessage(if (ok) "Catatan dihapus" else LOCKED_MESSAGE)
                    }
                }) { Text("Hapus") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Batal") } },
        )
    }
}

@Composable
private fun AddBonusEventDialog(
    state: Repository.SalesBonusState,
    type: String,
    onDismiss: () -> Unit,
    onConfirm: (employeeId: Long, date: LocalDate, note: String, byWhom: String) -> Unit,
) {
    val isWarning = type == BonusEventType.TEGURAN
    val today = LocalDate.now()
    val firstDay = state.ym.atDay(1)
    val lastDay = state.ym.atEndOfMonth().let { if (it.isAfter(today)) today else it }

    var employeeId by remember { mutableStateOf<Long?>(null) }
    var date by remember { mutableStateOf(lastDay) }
    var byWhom by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    val selected = state.employees.firstOrNull { it.id == employeeId }
    val existing = if (selected == null) 0 else state.events.count { it.type == BonusEventType.TEGURAN && it.employeeId == selected.id }
    val noteOk = !isWarning || note.isNotBlank()
    val canSave = selected != null && (!isWarning || byWhom.isNotBlank()) && noteOk

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isWarning) "Catat teguran" else "Catat setengah hari") },
        text = {
            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Karyawan", style = MaterialTheme.typography.labelLarge)
                state.employees.forEach { emp ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = employeeId == emp.id, onClick = { employeeId = emp.id })
                        Text(emp.name, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                Text("Tanggal", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = date == today, onClick = { date = today },
                        enabled = !today.isBefore(firstDay) && !today.isAfter(lastDay),
                        label = { Text("Hari ini") },
                    )
                    val yesterday = today.minusDays(1)
                    FilterChip(
                        selected = date == yesterday, onClick = { date = yesterday },
                        enabled = !yesterday.isBefore(firstDay) && !yesterday.isAfter(lastDay),
                        label = { Text("Kemarin") },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { date = date.minusDays(1) }, enabled = date.isAfter(firstDay)) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Hari sebelumnya")
                    }
                    Text(date.shortLabel(), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = { date = date.plusDays(1) }, enabled = date.isBefore(lastDay)) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Hari berikutnya")
                    }
                }

                if (isWarning) {
                    Text("Oleh", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Akong", "Koko").forEach { who ->
                            FilterChip(selected = byWhom == who, onClick = { byWhom = who }, label = { Text(who) })
                        }
                    }
                }

                OutlinedTextField(
                    value = note, onValueChange = { note = it },
                    label = { Text(if (isWarning) "Alasan" else "Catatan (boleh kosong)") },
                    placeholder = { Text(if (isWarning) "Contoh: main HP saat ada pembeli" else "Contoh: pulang cepat jam 2") },
                    modifier = Modifier.fillMaxWidth(),
                )

                if (isWarning && selected != null) {
                    Text(
                        "${selected.name} sudah punya $existing teguran bulan ini.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (existing + 1 >= 2) {
                        Text(
                            "Ini teguran ke-${existing + 1} bulan ini: ${selected.name} tidak dapat bonus penjualan bulan ini.",
                            style = MaterialTheme.typography.bodySmall, color = Amber, fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = { selected?.let { onConfirm(it.id, date, note.trim(), if (isWarning) byWhom else "") } },
            ) { Text("Simpan") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } },
    )
}
