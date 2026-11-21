package com.indohpl.presensi.ui.recap

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.indohpl.presensi.data.AppSettings
import com.indohpl.presensi.data.AttendanceRecord
import com.indohpl.presensi.data.Employee
import com.indohpl.presensi.data.Repository
import com.indohpl.presensi.data.Status
import com.indohpl.presensi.domain.BonusCalculator
import com.indohpl.presensi.domain.EmployeeRecap
import com.indohpl.presensi.domain.LateRule
import com.indohpl.presensi.domain.RecapFormatter
import com.indohpl.presensi.ui.Avatar
import com.indohpl.presensi.ui.NoGainChip
import com.indohpl.presensi.ui.PhotoDialog
import com.indohpl.presensi.ui.PlusChip
import com.indohpl.presensi.ui.AnimatedRupiah
import com.indohpl.presensi.ui.SaldoProgress
import com.indohpl.presensi.ui.StatusPill
import com.indohpl.presensi.ui.StreakChip
import com.indohpl.presensi.ui.shortLabel
import com.indohpl.presensi.ui.statusLabel
import com.indohpl.presensi.ui.theme.Green
import com.indohpl.presensi.ui.theme.GreenLight
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecapScreen(repository: Repository) {
    var month by remember { mutableStateOf(YearMonth.now()) }
    val today = LocalDate.now()

    val employees by repository.observeEmployees().collectAsStateWithLifecycle(emptyList())
    val records by remember(month) { repository.observeMonth(month) }.collectAsStateWithLifecycle(emptyList())
    val holidays by repository.observeHolidays().collectAsStateWithLifecycle(emptyList())
    val settings by repository.settingsStore.settings.collectAsStateWithLifecycle(AppSettings())

    val recaps: List<EmployeeRecap> = remember(month, today, employees, records, holidays, settings) {
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
        topBar = { TopAppBar(title = { Text("Saldo Uang Rajin") }) },
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
                        textAlign = TextAlign.Center,
                    )
                    IconButton(onClick = { month = month.plusMonths(1) }, enabled = !isCurrentMonth) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Bulan berikutnya")
                    }
                }
            }
            item {
                val first = recaps.firstOrNull()
                var showHelp by remember { mutableStateOf(false) }
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Uang rajin bulan ini",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { showHelp = true }) {
                                Icon(Icons.Filled.Info, contentDescription = "Cara kerja uang rajin")
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Maksimal ",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                RecapFormatter.rupiah(first?.maxThisMonth ?: 0L),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Green,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Setiap hari tepat waktu",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            val per = first?.perDayAmount ?: 0L
                            if (per > 0L) PlusChip(per)
                        }
                        Text(
                            "Telat, izin, sakit, dan bolos tidak dihitung. Saldo tidak pernah berkurang.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (showHelp) {
                    HelpDialog(
                        recap = first,
                        employees = employees.filter { it.active },
                        settings = settings,
                        onDismiss = { showHelp = false },
                    )
                }
            }
            items(recaps, key = { it.employee.id }) { r ->
                SaldoCard(
                    recap = r,
                    records = records.filter { it.employeeId == r.employee.id }.sortedByDescending { it.date },
                    isCurrentMonth = isCurrentMonth,
                    today = today,
                    onShowPhoto = { path, title -> photoToShow = path to title },
                )
            }
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Ketuk kartu untuk melihat riwayat harian.",
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

/** Pelajaran singkat "Cara kerja uang rajin". Batas waktu per gender dihitung dari pengaturan. */
@Composable
private fun HelpDialog(
    recap: EmployeeRecap?,
    employees: List<Employee>,
    settings: AppSettings,
    onDismiss: () -> Unit,
) {
    val max = recap?.maxThisMonth ?: 0L
    val per = recap?.perDayAmount ?: 0L
    val days = recap?.workDaysInMonth ?: 0
    fun deadline(gender: String): String {
        val start = settings.workStartFor(gender)
        val t = LateRule.parseWorkStart(start)?.plusMinutes(settings.toleranceFor(gender).toLong())
        return if (t == null) start else String.format(Locale.US, "%02d:%02d", t.hour, t.minute)
    }
    val groups = listOf("P", "L").mapNotNull { g ->
        val names = employees.filter { it.gender == g }.map { it.name }
        if (names.isEmpty()) null else names.joinToString(", ") + ": sampai " + deadline(g)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Mengerti") } },
        title = { Text("Cara kerja uang rajin") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Maksimal sebulan " + RecapFormatter.rupiah(max) + ".")
                if (per > 0L) {
                    Text("Setiap hari tepat waktu: " + RecapFormatter.rupiahPlus(per) + " ($days hari kerja bulan ini).")
                }
                Text("Telat, izin, sakit, dan bolos: tidak dihitung. Saldo tidak pernah berkurang.")
                if (groups.isNotEmpty()) {
                    Text("Batas tepat waktu:", fontWeight = FontWeight.SemiBold)
                    groups.forEach { Text(it) }
                }
                Text("Izin dan sakit menjeda hari beruntun, tidak memutus. Telat dan bolos memutus.")
                Text("Sebulan penuh tepat waktu = tepat " + RecapFormatter.rupiah(max) + ".")
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SaldoCard(
    recap: EmployeeRecap,
    records: List<AttendanceRecord>,
    isCurrentMonth: Boolean,
    today: LocalDate,
    onShowPhoto: (String, String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val todayCredit = if (isCurrentMonth) recap.dailyCredits[today.toString()] ?: 0L else 0L
    Card(elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(recap.employee)
                Spacer(Modifier.width(12.dp))
                Text(
                    recap.employee.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Tutup riwayat" else "Buka riwayat",
                )
            }
            Spacer(Modifier.height(12.dp))
            AnimatedRupiah(
                target = recap.earned,
                style = MaterialTheme.typography.headlineLarge,
            )
            Spacer(Modifier.height(8.dp))
            SaldoProgress(earned = recap.earned, max = recap.maxThisMonth, milestones = recap.milestones)
            Spacer(Modifier.height(6.dp))
            Text(
                "${recap.creditedDays} dari ${recap.workDaysInMonth} hari tepat waktu",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val showStreak = isCurrentMonth && recap.streak >= 2
            val hasChips = todayCredit > 0 || showStreak || recap.isFullMonth
            if (hasChips) {
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (todayCredit > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PlusChip(todayCredit)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "hari ini",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (showStreak) StreakChip(recap.streak)
                    if (recap.isFullMonth) {
                        Text(
                            "🏆 Full bulan!",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Green,
                            modifier = Modifier
                                .background(GreenLight, RoundedCornerShape(50))
                                .padding(horizontal = 10.dp, vertical = 3.dp),
                        )
                    }
                }
            }
            // Satu baris tujuan: bulan berjalan = target terdekat; bulan lalu = laporan akhir.
            val goalLine: String? = if (isCurrentMonth) {
                val next = recap.nextMilestone
                when {
                    recap.maxThisMonth > 0 && recap.earned >= recap.maxThisMonth -> if (recap.isFullMonth) null else "🏆 Full bulan!"
                    next != null -> "Tinggal ${recap.daysToNextMilestone} hari tepat waktu lagi ke " + RecapFormatter.rupiah(next)
                    recap.maxReachable > recap.earned -> "Maksimal bulan ini " + RecapFormatter.rupiah(recap.maxReachable)
                    else -> null
                }
            } else {
                "Selesai: ${recap.creditedDays} dari ${recap.workDaysInMonth} hari = " + RecapFormatter.rupiah(recap.earned)
            }
            if (goalLine != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    goalLine,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Green,
                )
            }
            if (recap.bestStreak >= 2) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Rekor bulan ini ${recap.bestStreak} hari 🔥",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(top = 12.dp)) {
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    SaldoCalendar(recap.calendar)
                    if (isCurrentMonth && recap.missedDays > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Tidak masuk saldo bulan ini " + RecapFormatter.rupiah(recap.missed) + " (${recap.missedDays} hari)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Tepat ${recap.tepat} · Telat ${recap.telat} · Izin/Sakit ${recap.izin + recap.sakit} · Tidak absen ${recap.alpa}" +
                            (if (recap.outsideLocation > 0) " · Luar lokasi ${recap.outsideLocation}" else ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    if (records.isEmpty()) {
                        Text("Belum ada catatan bulan ini.", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                    records.forEach { rec ->
                        val credit = recap.dailyCredits[rec.date]
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 8.dp)
                                .then(if (rec.photoPath != null) Modifier.clickable { onShowPhoto(rec.photoPath, "${recap.employee.name} · ${rec.date} ${rec.timeIn}") } else Modifier),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    LocalDate.parse(rec.date).shortLabel() +
                                        (if (rec.timeIn.isNotBlank()) "  ${rec.timeIn.take(5)}" else ""),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                if (credit != null && credit > 0) {
                                    PlusChip(credit)
                                } else if (rec.status == Status.TELAT) {
                                    NoGainChip("+Rp 0 · telat ${rec.lateMinutes} mnt")
                                } else if (rec.status == Status.IZIN || rec.status == Status.SAKIT) {
                                    NoGainChip("tidak dihitung")
                                } else if (credit == null) {
                                    NoGainChip("libur") // bukan hari kerja / hari libur toko
                                } else {
                                    NoGainChip("maksimal tercapai") // hari kerja, tapi saldo sudah di batas maksimal
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                StatusPill(status = rec.status, text = statusLabel(rec.status))
                                if ((rec.status == Status.TEPAT || rec.status == Status.TELAT) && !rec.inLocation) {
                                    Spacer(Modifier.width(6.dp))
                                    StatusPill(status = Status.TELAT, text = "Luar lokasi")
                                }
                            }
                            if (rec.note.isNotBlank()) {
                                Text(
                                    rec.note,
                                    modifier = Modifier.padding(top = 4.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
