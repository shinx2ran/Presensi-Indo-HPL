package com.indohpl.presensi.ui.recap

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.indohpl.presensi.data.AppSettings
import com.indohpl.presensi.data.AttendanceRecord
import com.indohpl.presensi.data.Repository
import com.indohpl.presensi.data.Status
import com.indohpl.presensi.domain.BonusCalculator
import com.indohpl.presensi.domain.EmployeeRecap
import com.indohpl.presensi.domain.RecapFormatter
import com.indohpl.presensi.ui.Avatar
import com.indohpl.presensi.ui.PhotoDialog
import com.indohpl.presensi.ui.SourceChips
import com.indohpl.presensi.ui.StatusPill
import com.indohpl.presensi.ui.cloudPhotoToFile
import com.indohpl.presensi.ui.loadCloudMonth
import com.indohpl.presensi.ui.shortLabel
import com.indohpl.presensi.ui.theme.Green
import java.time.LocalDate
import java.time.YearMonth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecapScreen(repository: Repository) {
    val context = LocalContext.current
    var month by remember { mutableStateOf(YearMonth.now()) }
    val today = LocalDate.now()

    val employees by repository.observeEmployees().collectAsStateWithLifecycle(emptyList())
    val localRecords by remember(month) { repository.observeMonth(month) }.collectAsStateWithLifecycle(emptyList())
    val cloudConfig by repository.settingsStore.cloud.collectAsStateWithLifecycle(null)
    val cloudAvailable = cloudConfig?.let { it.enabled && it.isComplete } == true
    var useCloud by remember { mutableStateOf(false) }
    var cloudRecords by remember { mutableStateOf<List<AttendanceRecord>>(emptyList()) }
    var cloudPhotos by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var cloudError by remember { mutableStateOf<String?>(null) }
    var cloudLoading by remember { mutableStateOf(false) }
    LaunchedEffect(useCloud, month) {
        if (!useCloud) return@LaunchedEffect
        cloudLoading = true
        val m = loadCloudMonth(context, repository, month)
        cloudRecords = m.records; cloudPhotos = m.photos; cloudError = m.error
        cloudLoading = false
    }
    val records = if (useCloud) cloudRecords else localRecords
    val holidays by repository.observeHolidays().collectAsStateWithLifecycle(emptyList())
    val settings by repository.settingsStore.settings.collectAsStateWithLifecycle(AppSettings())

    val recaps: List<EmployeeRecap> = remember(month, employees, records, holidays, settings) {
        BonusCalculator.recap(
            ym = month,
            today = today,
            employees = employees.filter { it.active },
            records = records,
            workDayOfWeeks = settings.workDays,
            holidays = holidays.map { LocalDate.parse(it.date) }.toSet(),
            config = settings.bonusConfig(),
        )
    }
    val isCurrentMonth = month == YearMonth.from(today)
    var photoToShow by remember { mutableStateOf<Pair<String, String>?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Rekap Bulanan") }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    IconButton(onClick = { month = month.minusMonths(1) }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Bulan sebelumnya")
                    }
                    Text(
                        RecapFormatter.monthLabel(month),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    IconButton(onClick = { month = month.plusMonths(1) }, enabled = !isCurrentMonth) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Bulan berikutnya")
                    }
                }
            }
            if (cloudAvailable) {
                item {
                    SourceChips(useCloud = useCloud, onChange = { useCloud = it })
                    if (useCloud && cloudLoading) Text("Memuat data cloud…", style = MaterialTheme.typography.bodySmall)
                    cloudError?.takeIf { useCloud }?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
            item {
                val perDay = recaps.firstOrNull()?.deductionPerDay ?: 0L
                val workDays = recaps.firstOrNull()?.workDaysInMonth ?: 0
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "Uang rajin penuh ${RecapFormatter.rupiah(settings.monthlyBonus)} · $workDays hari kerja",
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Potongan ${RecapFormatter.rupiah(perDay)} per hari telat" +
                                if (settings.deductAbsent) " atau alpa." else ". Alpa tidak dipotong.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (isCurrentMonth) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Bulan berjalan: angka masih sementara sampai akhir bulan.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            items(recaps, key = { it.employee.id }) { r ->
                RecapCard(
                    recap = r,
                    records = records.filter { it.employeeId == r.employee.id }.sortedByDescending { it.date },
                    cloudPhotos = if (useCloud) cloudPhotos else emptyMap(),
                    onShowPhoto = { path, title -> photoToShow = path to title },
                    onShowCloudPhoto = { rec, title ->
                        cloudPhotos[rec.cloudId]?.let { b64 -> photoToShow = cloudPhotoToFile(context, rec.cloudId, b64) to title }
                    },
                )
            }
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Ketuk nama untuk melihat detail harian. Ekspor rekap ada di Pengaturan (khusus owner).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    photoToShow?.let { (path, title) ->
        PhotoDialog(path = path, title = title, onDismiss = { photoToShow = null })
    }
}

@Composable
private fun RecapCard(
    recap: EmployeeRecap,
    records: List<AttendanceRecord>,
    cloudPhotos: Map<String, String>,
    onShowPhoto: (String, String) -> Unit,
    onShowCloudPhoto: (AttendanceRecord, String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Card(elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(recap.employee)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(recap.employee.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Hadir ${recap.hadir}/${recap.workDaysElapsed} · Tepat ${recap.tepat} · Telat ${recap.telat} · Alpa ${recap.alpa}" +
                            (if (recap.izin + recap.sakit > 0) " · Izin/Sakit ${recap.izin + recap.sakit}" else "") +
                            (if (recap.outsideLocation > 0) " · Luar lokasi ${recap.outsideLocation}" else ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        RecapFormatter.rupiah(recap.bonus),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (recap.totalDeduction == 0L) Green else MaterialTheme.colorScheme.error,
                    )
                    if (recap.totalDeduction > 0) {
                        Text(
                            "−${RecapFormatter.rupiah(recap.totalDeduction)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else if (recap.isFullMonth) {
                        Text("Full bulan ✓", style = MaterialTheme.typography.bodySmall, color = Green)
                    }
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(top = 12.dp)) {
                    HorizontalDivider()
                    if (records.isEmpty()) {
                        Text("Belum ada catatan bulan ini.", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                    records.forEach { rec ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 8.dp)
                                .then(
                                    when {
                                        rec.photoPath != null -> Modifier.clickable { onShowPhoto(rec.photoPath, "${recap.employee.name} · ${rec.date} ${rec.timeIn}") }
                                        cloudPhotos.containsKey(rec.cloudId) -> Modifier.clickable { onShowCloudPhoto(rec, "${recap.employee.name} · ${rec.date} ${rec.timeIn}") }
                                        else -> Modifier
                                    },
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(LocalDate.parse(rec.date).shortLabel(), Modifier.width(96.dp), style = MaterialTheme.typography.bodyMedium)
                            Text(rec.timeIn.take(5), Modifier.width(56.dp), style = MaterialTheme.typography.bodyMedium)
                            StatusPill(
                                status = rec.status,
                                text = if (rec.lateMinutes > 0) "Telat ${rec.lateMinutes} mnt" else com.indohpl.presensi.ui.statusLabel(rec.status),
                            )
                            if ((rec.status == Status.TEPAT || rec.status == Status.TELAT) && !rec.inLocation) {
                                Spacer(Modifier.width(6.dp))
                                StatusPill(status = Status.TELAT, text = "Luar lokasi")
                            }
                            if (rec.note.isNotBlank()) {
                                Spacer(Modifier.width(8.dp))
                                Text(rec.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
