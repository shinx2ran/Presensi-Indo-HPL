package com.indohpl.presensi.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.indohpl.presensi.data.AppSettings
import com.indohpl.presensi.data.AttendanceRecord
import com.indohpl.presensi.data.Employee
import com.indohpl.presensi.data.Repository
import com.indohpl.presensi.data.Status
import com.indohpl.presensi.ui.Avatar
import com.indohpl.presensi.ui.PhotoDialog
import com.indohpl.presensi.ui.StatusPill
import com.indohpl.presensi.ui.longLabel
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    repository: Repository,
    onCheckIn: (Long) -> Unit,
    onMessage: (String) -> Unit,
) {
    // Jam berjalan; tanggal "hari ini" ikut berubah otomatis lewat tengah malam.
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(1000)
        }
    }
    val today: LocalDate = now.toLocalDate()

    val employees by repository.observeEmployees().collectAsStateWithLifecycle(emptyList())
    val todayRecords by remember(today) { repository.observeToday(today) }
        .collectAsStateWithLifecycle(emptyList())
    val settings by repository.settingsStore.settings.collectAsStateWithLifecycle(AppSettings())
    val holidays by repository.observeHolidays().collectAsStateWithLifecycle(emptyList())

    val recordByEmployee = remember(todayRecords) { todayRecords.associateBy { it.employeeId } }
    val holiday = holidays.firstOrNull { it.date == today.toString() }
    val isWorkDay = today.dayOfWeek in settings.workDays && holiday == null
    val scope = rememberCoroutineScope()

    var photoToShow by remember { mutableStateOf<Pair<String, String>?>(null) }
    var excuseDialog by remember { mutableStateOf<Pair<Employee, String>?>(null) }
    var deleteDialog by remember { mutableStateOf<Pair<Employee, AttendanceRecord>?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Presensi Indo HPL", fontWeight = FontWeight.Bold)
                        Text(today.longLabel(), style = MaterialTheme.typography.bodySmall)
                    }
                },
                actions = {
                    Text(
                        now.format(CLOCK),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(end = 16.dp),
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                InfoBanner(
                    workStart = settings.workStart,
                    tolerance = settings.toleranceMinutes,
                    isWorkDay = isWorkDay,
                    holidayName = holiday?.name,
                    done = todayRecords.count { it.status == Status.TEPAT || it.status == Status.TELAT },
                    total = employees.count { it.active },
                )
            }
            items(employees.filter { it.active }, key = { it.id }) { emp ->
                EmployeeCard(
                    employee = emp,
                    record = recordByEmployee[emp.id],
                    onCheckIn = { onCheckIn(emp.id) },
                    onShowPhoto = { path -> photoToShow = path to "${emp.name} · ${recordByEmployee[emp.id]?.timeIn ?: ""}" },
                    onExcuse = { status -> excuseDialog = emp to status },
                    onDelete = { rec -> deleteDialog = emp to rec },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    photoToShow?.let { (path, title) ->
        PhotoDialog(path = path, title = title, onDismiss = { photoToShow = null })
    }

    excuseDialog?.let { (emp, status) ->
        var note by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { excuseDialog = null },
            title = { Text("Tandai ${if (status == Status.IZIN) "Izin" else "Sakit"} — ${emp.name}") },
            text = {
                Column {
                    Text("Hari ini (${today.longLabel()}) dicatat sebagai ${if (status == Status.IZIN) "izin" else "sakit"}, tanpa selfie. Tidak dipotong dari uang rajin.")
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text("Keterangan (opsional)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val ok = repository.recordExcused(emp.id, today, status, note.trim())
                        onMessage(if (ok) "${emp.name} ditandai ${status.lowercase()}" else "${emp.name} sudah punya catatan hari ini")
                    }
                    excuseDialog = null
                }) { Text("Simpan") }
            },
            dismissButton = { TextButton(onClick = { excuseDialog = null }) { Text("Batal") } },
        )
    }

    deleteDialog?.let { (emp, rec) ->
        AlertDialog(
            onDismissRequest = { deleteDialog = null },
            title = { Text("Hapus catatan hari ini?") },
            text = { Text("Catatan ${emp.name} (${rec.status}${if (rec.timeIn.isNotEmpty()) " " + rec.timeIn else ""}) dan fotonya akan dihapus. Tidak bisa dibatalkan.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        repository.deleteRecord(rec)
                        onMessage("Catatan ${emp.name} dihapus")
                    }
                    deleteDialog = null
                }) { Text("Hapus") }
            },
            dismissButton = { TextButton(onClick = { deleteDialog = null }) { Text("Batal") } },
        )
    }
}

@Composable
private fun InfoBanner(
    workStart: String,
    tolerance: Int,
    isWorkDay: Boolean,
    holidayName: String?,
    done: Int,
    total: Int,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Jam masuk $workStart" + if (tolerance > 0) " (toleransi $tolerance menit)" else "",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    holidayName != null -> "Hari libur: $holidayName. Presensi tetap bisa dicatat."
                    !isWorkDay -> "Hari ini bukan hari kerja. Presensi tetap bisa dicatat."
                    else -> "Sudah hadir $done dari $total karyawan."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun EmployeeCard(
    employee: Employee,
    record: AttendanceRecord?,
    onCheckIn: () -> Unit,
    onShowPhoto: (String) -> Unit,
    onExcuse: (String) -> Unit,
    onDelete: (AttendanceRecord) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(employee)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(employee.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                if (record == null) {
                    Text("Belum absen", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusPill(
                            status = record.status,
                            text = when (record.status) {
                                Status.TEPAT -> "Tepat · ${record.timeIn.take(5)}"
                                Status.TELAT -> "Telat ${record.lateMinutes} mnt · ${record.timeIn.take(5)}"
                                Status.IZIN -> "Izin"
                                Status.SAKIT -> "Sakit"
                                else -> record.status
                            },
                        )
                        if ((record.status == Status.TEPAT || record.status == Status.TELAT) && !record.inLocation) {
                            Spacer(Modifier.width(6.dp))
                            StatusPill(status = Status.TELAT, text = "Luar lokasi")
                        }
                    }
                }
            }
            if (record == null) {
                Button(onClick = onCheckIn) { Text("Absen") }
            } else {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Sudah absen",
                    tint = MaterialTheme.colorScheme.secondary,
                )
            }
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (record?.photoPath != null) {
                    DropdownMenuItem(text = { Text("Lihat foto") }, onClick = { menuOpen = false; onShowPhoto(record.photoPath) })
                }
                if (record == null) {
                    DropdownMenuItem(text = { Text("Tandai izin") }, onClick = { menuOpen = false; onExcuse(Status.IZIN) })
                    DropdownMenuItem(text = { Text("Tandai sakit") }, onClick = { menuOpen = false; onExcuse(Status.SAKIT) })
                } else {
                    DropdownMenuItem(text = { Text("Hapus catatan hari ini") }, onClick = { menuOpen = false; onDelete(record) })
                }
            }
        }
    }
}
