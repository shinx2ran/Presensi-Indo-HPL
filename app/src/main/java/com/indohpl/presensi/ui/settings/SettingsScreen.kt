package com.indohpl.presensi.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.indohpl.presensi.data.AppSettings
import com.indohpl.presensi.data.Holiday
import com.indohpl.presensi.data.Repository
import com.indohpl.presensi.domain.GeoRule
import com.indohpl.presensi.domain.LateRule
import com.indohpl.presensi.util.LocationHelper
import com.indohpl.presensi.domain.RecapFormatter
import com.indohpl.presensi.ui.shortLabel
import java.time.DayOfWeek
import java.time.LocalDate
import kotlinx.coroutines.launch

private val dayLabels = mapOf(
    DayOfWeek.MONDAY to "Sen", DayOfWeek.TUESDAY to "Sel", DayOfWeek.WEDNESDAY to "Rab",
    DayOfWeek.THURSDAY to "Kam", DayOfWeek.FRIDAY to "Jum", DayOfWeek.SATURDAY to "Sab", DayOfWeek.SUNDAY to "Min",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(repository: Repository, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved by repository.settingsStore.settings.collectAsStateWithLifecycle(null)
    val holidays by repository.observeHolidays().collectAsStateWithLifecycle(emptyList())

    var workStartF by remember { mutableStateOf("") }
    var toleranceF by remember { mutableStateOf("") }
    var workStartM by remember { mutableStateOf("") }
    var toleranceM by remember { mutableStateOf("") }
    var bonus by remember { mutableStateOf("") }
    var deduction by remember { mutableStateOf("") }
    var deductAbsent by remember { mutableStateOf(true) }
    var workDays by remember { mutableStateOf<Set<DayOfWeek>>(emptySet()) }
    var storeLatLon by remember { mutableStateOf("") }      // "lat, lon"
    var radius by remember { mutableStateOf("100") }
    var requireLocation by remember { mutableStateOf(true) }
    var locating by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }

    fun fetchStoreLocation() {
        locating = true
        scope.launch {
            val loc = LocationHelper.current(context)
            locating = false
            if (loc == null) {
                onMessage("GPS tidak didapat. Nyalakan Lokasi HP lalu coba lagi.")
            } else if (LocationHelper.isMock(loc)) {
                onMessage("Terdeteksi lokasi palsu. Matikan aplikasi fake GPS.")
            } else {
                storeLatLon = GeoRule.formatCoord(loc.latitude, loc.longitude)
                onMessage("Posisi HP diambil (akurasi ±${loc.accuracy.toInt()} m). Tekan Simpan pengaturan.")
            }
        }
    }
    val locationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) fetchStoreLocation() else onMessage("Izin lokasi ditolak.")
    }

    // Isi form sekali dari nilai tersimpan.
    LaunchedEffect(saved) {
        val s = saved ?: return@LaunchedEffect
        if (!loaded) {
            workStartF = s.workStartFemale
            toleranceF = s.toleranceFemale.toString()
            workStartM = s.workStartMale
            toleranceM = s.toleranceMale.toString()
            bonus = s.monthlyBonus.toString()
            deduction = s.lateDeduction.toString()
            deductAbsent = s.deductAbsent
            workDays = s.workDays
            storeLatLon = if (s.storeLat != null && s.storeLon != null) GeoRule.formatCoord(s.storeLat, s.storeLon) else ""
            radius = s.radiusMeters.toString()
            requireLocation = s.requireLocation
            loaded = true
        }
    }

    var newHolidayDate by remember { mutableStateOf("") }
    var newHolidayName by remember { mutableStateOf("") }

    fun save() {
        if (LateRule.parseWorkStart(workStartF) == null || LateRule.parseWorkStart(workStartM) == null) {
            onMessage("Jam masuk harus format HH:mm, contoh 07:45"); return
        }
        val tolF = toleranceF.trim().toIntOrNull()
        val tolM = toleranceM.trim().toIntOrNull()
        val bon = bonus.trim().toLongOrNull()
        val ded = deduction.trim().toLongOrNull()
        if (tolF == null || tolF < 0 || tolM == null || tolM < 0) { onMessage("Toleransi harus angka ≥ 0"); return }
        if (bon == null || bon < 0) { onMessage("Uang rajin harus angka ≥ 0"); return }
        if (ded == null || ded < 0) { onMessage("Potongan harus angka ≥ 0 (0 = otomatis)"); return }
        if (workDays.isEmpty()) { onMessage("Pilih minimal satu hari kerja"); return }
        val latLon = if (storeLatLon.isBlank()) null else GeoRule.parseLatLon(storeLatLon)
        if (storeLatLon.isNotBlank() && latLon == null) { onMessage("Koordinat harus \"lat, lon\", contoh -6.200000, 106.816666"); return }
        val rad = radius.trim().toIntOrNull()
        if (rad == null || rad < 10) { onMessage("Radius minimal 10 meter"); return }
        scope.launch {
            repository.settingsStore.save(
                AppSettings(
                    workStartFemale = workStartF.trim(),
                    toleranceFemale = tolF,
                    workStartMale = workStartM.trim(),
                    toleranceMale = tolM,
                    monthlyBonus = bon,
                    lateDeduction = ded,
                    deductAbsent = deductAbsent,
                    workDays = workDays,
                    storeLat = latLon?.first,
                    storeLon = latLon?.second,
                    radiusMeters = rad,
                    requireLocation = requireLocation,
                ),
            )
            onMessage("Pengaturan disimpan")
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Pengaturan") }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Aturan jam masuk", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Datang lewat dari jam masuk + toleransi = telat. Menit telat dihitung dari jam masuk.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Perempuan (Sara, Riyanti)", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = workStartF, onValueChange = { workStartF = it },
                    label = { Text("Jam masuk") }, singleLine = true, modifier = Modifier.weight(1f),
                    placeholder = { Text("07:45") },
                )
                OutlinedTextField(
                    value = toleranceF, onValueChange = { toleranceF = it.filter(Char::isDigit) },
                    label = { Text("Toleransi (mnt)") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            Text("Laki-laki (Evan, Madi, Roni)", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = workStartM, onValueChange = { workStartM = it },
                    label = { Text("Jam masuk") }, singleLine = true, modifier = Modifier.weight(1f),
                    placeholder = { Text("08:00") },
                )
                OutlinedTextField(
                    value = toleranceM, onValueChange = { toleranceM = it.filter(Char::isDigit) },
                    label = { Text("Toleransi (mnt)") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }

            Text("Hari kerja", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                DayOfWeek.values().forEach { d ->
                    FilterChip(
                        selected = d in workDays,
                        onClick = { workDays = if (d in workDays) workDays - d else workDays + d },
                        label = { Text(dayLabels[d] ?: d.name) },
                    )
                }
            }

            Text("Uang rajin", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = bonus, onValueChange = { bonus = it.filter(Char::isDigit) },
                label = { Text("Uang rajin penuh per bulan (Rp)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedTextField(
                value = deduction, onValueChange = { deduction = it.filter(Char::isDigit) },
                label = { Text("Potongan per hari telat (Rp)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = {
                    val b = bonus.toLongOrNull() ?: 0L
                    val wd = workDays.size
                    Text("0 = otomatis: uang rajin ÷ jumlah hari kerja bulan itu (≈ ${RecapFormatter.rupiah(if (wd > 0) b / (wd * 4 + 2) else 0)} untuk bulan dengan ${wd * 4 + 2} hari kerja).")
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Potong juga hari alpa")
                    Text(
                        "Alpa = tidak absen di hari kerja tanpa izin/sakit.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = deductAbsent, onCheckedChange = { deductAbsent = it })
            }
            Text("Lokasi toko Indo HPL", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Selfie hanya sah bila HP berada dalam radius ini dari titik toko. Ambil titiknya sambil berdiri di dalam toko, atau salin koordinat dari Google Maps (tekan lama lokasi → angka di atas).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = storeLatLon, onValueChange = { storeLatLon = it },
                label = { Text("Koordinat toko (lat, lon)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("-6.200000, 106.816666") },
                supportingText = { if (storeLatLon.isBlank()) Text("Kosong = pengecekan lokasi tidak aktif.") },
            )
            OutlinedButton(
                onClick = {
                    if (LocationHelper.hasPermission(context)) fetchStoreLocation() else locationPermissionLauncher.launch(LocationHelper.PERMISSIONS)
                },
                enabled = !locating,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (locating) "Mencari posisi GPS…" else "Pakai lokasi HP sekarang") }
            OutlinedTextField(
                value = radius, onValueChange = { radius = it.filter(Char::isDigit) },
                label = { Text("Radius toleransi (meter)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = { Text("Akurasi GPS ikut diperhitungkan. 100 m cukup untuk toko di dalam ruko/mall.") },
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Wajib di lokasi toko")
                    Text(
                        "Aktif: absen ditolak jika di luar radius atau GPS tidak didapat. Nonaktif: tetap tercatat tapi ditandai \"di luar lokasi\".",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = requireLocation, onCheckedChange = { requireLocation = it })
            }

            Button(onClick = { save() }, modifier = Modifier.fillMaxWidth()) { Text("Simpan pengaturan") }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text("Hari libur toko", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Tanggal di sini tidak dihitung sebagai hari kerja (tidak ada alpa, tidak dipotong).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newHolidayDate, onValueChange = { newHolidayDate = it },
                    label = { Text("Tanggal (yyyy-mm-dd)") }, singleLine = true, modifier = Modifier.weight(1.1f),
                )
                OutlinedTextField(
                    value = newHolidayName, onValueChange = { newHolidayName = it },
                    label = { Text("Nama") }, singleLine = true, modifier = Modifier.weight(1f),
                )
            }
            Button(
                onClick = {
                    val d = runCatching { LocalDate.parse(newHolidayDate.trim()) }.getOrNull()
                    if (d == null) { onMessage("Tanggal harus format yyyy-mm-dd, contoh 2026-12-25"); return@Button }
                    scope.launch {
                        repository.holidayDao.upsert(Holiday(d.toString(), newHolidayName.trim().ifEmpty { "Libur" }))
                        newHolidayDate = ""; newHolidayName = ""
                        onMessage("Hari libur ditambahkan")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Tambah hari libur") }

            if (holidays.isNotEmpty()) {
                Card {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                        holidays.forEach { h ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text(LocalDate.parse(h.date).shortLabel() + " " + h.date.take(4), Modifier.width(140.dp))
                                Text(h.name, Modifier.weight(1f))
                                IconButton(onClick = { scope.launch { repository.holidayDao.delete(h) } }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Hapus")
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Catatan penting", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "• Biometrik memakai sidik jari/wajah/PIN yang terdaftar di HP kasir ini. Android tidak memberi tahu aplikasi sidik jari siapa yang dipakai, jadi bukti identitas utama adalah selfie berstempel waktu.\n" +
                    "• Waktu diambil dari jam HP kasir. Pastikan jam HP otomatis (dari jaringan).\n" +
                    "• Koordinat GPS dibakar ke foto. Aplikasi fake GPS terdeteksi dan ditolak.\n" +
                    "• Foto tersimpan di dalam aplikasi (folder privat). Menghapus aplikasi = menghapus semua data. Ekspor rekap tiap bulan.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
