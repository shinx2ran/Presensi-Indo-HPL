package com.indohpl.presensi.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.indohpl.presensi.data.Repository
import com.indohpl.presensi.domain.BonusShare
import com.indohpl.presensi.domain.RecapFormatter
import com.indohpl.presensi.domain.SalesBonus
import com.indohpl.presensi.ui.Avatar
import com.indohpl.presensi.ui.shortLabel
import com.indohpl.presensi.ui.theme.Amber
import com.indohpl.presensi.ui.theme.Green
import com.indohpl.presensi.ui.theme.Red
import com.indohpl.presensi.util.Export
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.launch

private const val LOCKED_MESSAGE = "Bulan ini sudah dikunci (sudah dibayar). Buka lagi dulu untuk mengubah."

/** "1", "1,5", "2" dari jumlah setengah hari. */
private fun daysText(halfDays: Int): String =
    if (halfDays % 2 == 0) "${halfDays / 2}" else "${halfDays / 2},5"

private fun shareColor(share: BonusShare): Color = when (share) {
    BonusShare.FULL -> Green
    BonusShare.THREE_QUARTERS, BonusShare.ONE_QUARTER -> Amber
    BonusShare.NONE -> Red
}

/** Owner: bonus penjualan toko. Tab Pengaturan sudah di balik PIN owner. */
@Composable
fun SalesBonusSection(repository: Repository, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val relocked = LocalOwnerRelocked.current

    val currentYm = YearMonth.now()
    var ymText by rememberSaveable { mutableStateOf(currentYm.toString()) }
    val ym = remember(ymText) { runCatching { YearMonth.parse(ymText) }.getOrDefault(currentYm) }
    val isCurrent = ym == currentYm
    val today = LocalDate.now()

    val config by repository.settingsStore.salesBonusConfig
        .collectAsStateWithLifecycle(com.indohpl.presensi.domain.SalesBonusConfig())
    val loaded by remember(ym) { repository.observeSalesBonus(ym) }
        .collectAsStateWithLifecycle<Repository.SalesBonusState?>(null)
    // Nilai lama bisa tertinggal sebentar saat pindah bulan.
    val state = loaded?.takeIf { it.ym == ym }

    // Isian form.
    var grossText by rememberSaveable(ym) { mutableStateOf("") }
    var excludedText by rememberSaveable(ym) { mutableStateOf("") }
    var noteText by rememberSaveable(ym) { mutableStateOf("") }
    var asOfText by rememberSaveable(ym) { mutableStateOf((if (isCurrent) today else ym.atEndOfMonth()).toString()) }
    var prefilled by rememberSaveable(ym) { mutableStateOf(false) }

    LaunchedEffect(state?.month, state != null) {
        if (state != null && !prefilled) {
            prefilled = true
            val m = state.month
            if (m != null) {
                grossText = m.grossSales.toString()
                excludedText = if (m.excludedSales > 0) m.excludedSales.toString() else ""
                noteText = m.note
                // Tanggal "data sampai" sengaja TIDAK diambil dari simpanan: angka baru hampir selalu untuk tanggal
                // yang lebih baru, jadi bawaannya hari ini (bulan ini) atau akhir bulan (bulan lalu).
            }
        }
    }

    var expanded by remember(ym) { mutableStateOf<Set<Long>>(emptySet()) }
    var confirmPaid by remember { mutableStateOf(false) }
    var confirmReopen by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Bonus penjualan", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

        // 1. Pilih bulan: bulan lalu atau bulan ini.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(
                onClick = { ymText = currentYm.minusMonths(1).toString() },
                enabled = isCurrent,
            ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Bulan lalu") }
            Text(
                RecapFormatter.monthLabel(ym),
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            IconButton(
                onClick = { ymText = currentYm.toString() },
                enabled = !isCurrent,
            ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Bulan ini") }
        }

        if (state != null && !state.applies) {
            Text(
                "Bonus penjualan berlaku mulai ${RecapFormatter.monthLabel(config.startMonth)}.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (state != null) {
            val locked = state.paid

            // 2. Isian penjualan.
            Text(
                "Isi dari laporan Toqoo, lalu kurangi proyek CE LENY.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = grossText, onValueChange = { grossText = SalesBonus.rupiahDigits(it) },
                label = { Text("Penjualan Toqoo (Rp)") }, singleLine = true, enabled = !locked,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = { SalesBonus.parseRupiah(grossText)?.let { Text("= ${SalesBonus.rp(it)}") } },
            )
            OutlinedTextField(
                value = excludedText, onValueChange = { excludedText = SalesBonus.rupiahDigits(it) },
                label = { Text("Dikurangi proyek CE LENY (Rp)") }, singleLine = true, enabled = !locked,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = { SalesBonus.parseRupiah(excludedText)?.let { Text("= ${SalesBonus.rp(it)}") } },
            )

            Text("Data sampai tanggal", style = MaterialTheme.typography.labelLarge)
            val asOfOptions: List<Pair<String, LocalDate>> = if (isCurrent) {
                listOf(10, 20, 25).filter { it <= today.dayOfMonth }
                    .map { "Tgl $it" to ym.atDay(it) } + ("Hari ini" to today)
            } else {
                listOf("Akhir bulan" to ym.atEndOfMonth())
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            ) {
                asOfOptions.forEach { (label, date) ->
                    FilterChip(
                        selected = asOfText == date.toString(),
                        onClick = { asOfText = date.toString() },
                        enabled = !locked,
                        label = { Text(label) },
                    )
                }
            }
            state.salesAsOf?.let { saved ->
                Text(
                    "Tersimpan: data sampai ${saved.shortLabel()} ${saved.year}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = noteText, onValueChange = { noteText = it },
                label = { Text("Catatan (boleh kosong)") }, enabled = !locked,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = !locked,
                onClick = {
                    val gross = SalesBonus.parseRupiah(grossText)
                    if (gross == null) { onMessage("Isi penjualan Toqoo dulu"); return@Button }
                    val excluded = SalesBonus.parseRupiah(excludedText) ?: 0L
                    val asOf = runCatching { LocalDate.parse(asOfText) }.getOrDefault(today)
                    scope.launch {
                        val ok = repository.saveSales(ym, gross, excluded, asOf, noteText.trim())
                        onMessage(if (ok) "Penjualan ${RecapFormatter.monthLabel(ym)} disimpan" else LOCKED_MESSAGE)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Simpan penjualan") }

            val month = state.month
            if (month != null) {
                val result = state.result
                HorizontalDivider()

                // 3. Ringkasan.
                Text(
                    "Penjualan dihitung: ${SalesBonus.rp(result.netSales)}",
                    style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                )
                val tier = result.tier
                val next = result.nextTier
                if (tier != null) {
                    Text("✅ Target ${SalesBonus.juta(tier.minSales)} tercapai → ${SalesBonus.rp(tier.perPerson)}/orang")
                } else if (next != null && !isCurrent) {
                    // Bulan ini sudah ada baris "🎯 Kurang ... lagi" di bawah; jangan diulang.
                    Text("Belum mencapai ${SalesBonus.juta(next.minSales)} (kurang ${SalesBonus.rp(result.shortfall)})")
                }
                if (isCurrent) {
                    if (next != null) {
                        Text("🎯 Kurang ${SalesBonus.rp(result.shortfall)} lagi ke ${SalesBonus.juta(next.minSales)} (${SalesBonus.rp(next.perPerson)}/orang)")
                    } else if (tier != null) {
                        Text("🏆 Target tertinggi tercapai")
                    }
                }
                if (tier != null) {
                    Text(
                        "Uang bonus: ${SalesBonus.rp(tier.perPerson)} x ${result.participants} orang = ${SalesBonus.rp(result.pool)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                if (state.attendanceDeleted) {
                    Text(
                        "Data presensi bulan ini sudah terhapus otomatis, hitungan tidak bisa dipakai",
                        style = MaterialTheme.typography.bodyMedium, color = Red,
                    )
                } else {
                    // 4. Tabel per karyawan.
                    Text(
                        if (isCurrent) "Bagian per orang (sementara)" else "Bagian per orang",
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    )
                    Card {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                            result.people.forEachIndexed { index, p ->
                                val id = p.employee.id
                                if (index > 0) HorizontalDivider()
                                Column(
                                    Modifier.fillMaxWidth()
                                        .clickable(enabled = p.absences.isNotEmpty()) {
                                            expanded = if (id in expanded) expanded - id else expanded + id
                                        }
                                        .padding(vertical = 8.dp),
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Avatar(p.employee)
                                        Column(Modifier.weight(1f)) {
                                            Text(p.employee.name, fontWeight = FontWeight.SemiBold)
                                            Text(
                                                "Tidak masuk: ${daysText(p.absentHalfDays)} hari",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            if (p.warnings > 0) {
                                                Text(
                                                    "Teguran: ${p.warnings}x",
                                                    style = MaterialTheme.typography.bodySmall, color = Amber,
                                                )
                                            }
                                        }
                                        Column(horizontalAlignment = Alignment.End) {
                                            Text(
                                                if (p.participating) p.share.label else "tidak ikut",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = if (p.participating) shareColor(p.share) else MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                            Text(RecapFormatter.rupiah(p.payout), fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = p.participating,
                                            enabled = !locked,
                                            onCheckedChange = { checked ->
                                                scope.launch {
                                                    if (!repository.setSalesParticipant(ym, id, checked)) onMessage(LOCKED_MESSAGE)
                                                }
                                            },
                                        )
                                        Text("ikut", style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (id in expanded) {
                                        p.absences.forEach { a ->
                                            Text(
                                                "• ${a.date.shortLabel()} · ${if (a.halfDays >= 2) "1 hari" else "½ hari"} · ${a.reason}",
                                                style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier.padding(start = 54.dp, top = 2.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    val total = result.totalPaid
                    Text(
                        "Total dibagikan: ${RecapFormatter.rupiah(total)}",
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                    )
                    if (total != result.pool) {
                        Text(
                            "(selisih pembulatan ${RecapFormatter.rupiah(kotlin.math.abs(total - result.pool))})",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // 5. Teguran dan setengah hari.
            BonusEventsPanel(repository = repository, state = state, onMessage = onMessage)

            // 6. Pengumuman WA.
            if (month != null) {
                // Hasil akhir hanya bila bulan sudah lewat DAN penjualan sudah diisi sampai akhir bulan.
                val final = state.monthFinished && state.salesComplete
                val asOf = state.salesAsOf ?: today
                val text = SalesBonus.announcement(state.result, state.config, asOf, final = final)
                HorizontalDivider()
                if (state.monthFinished && !state.salesComplete) {
                    Text(
                        "Bulan sudah selesai, tetapi penjualan baru diisi sampai ${asOf.shortLabel()}. " +
                            "Isi angka akhir bulan dulu (pilih \"Akhir bulan\", lalu Simpan penjualan) sebelum hasil akhir " +
                            "diumumkan dan ditandai sudah dibayar.",
                        style = MaterialTheme.typography.bodyMedium, color = Amber,
                    )
                }
                Text("Pengumuman untuk grup WA", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(
                    onClick = {
                        Export.copyToClipboard(context, "Pengumuman penjualan", text)
                        onMessage("Pengumuman disalin, tempel di grup WA")
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Salin pengumuman WA") }

                // 7. Tandai dibayar / buka lagi.
                if (state.paid) {
                    val paidDate = month.paidAt?.let {
                        Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Sudah dibayar" + (paidDate?.let { " ${it.shortLabel()} ${it.year}" } ?: ""),
                            style = MaterialTheme.typography.bodyMedium, color = Green,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { confirmReopen = true }) { Text("Buka lagi") }
                    }
                } else if (state.monthFinished && state.salesComplete && !state.attendanceDeleted) {
                    OutlinedButton(onClick = { confirmPaid = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Tandai sudah dibayar")
                    }
                }
            }
        }

        // 8. Target bonus (selalu tampil).
        HorizontalDivider()
        SalesTierEditor(repository = repository, onMessage = onMessage)
    }

    // Disembunyikan selama lapisan PIN tampil (lihat LocalOwnerRelocked); muncul lagi setelah PIN benar.
    if (confirmPaid && !relocked) {
        AlertDialog(
            onDismissRequest = { confirmPaid = false },
            title = { Text("Tandai sudah dibayar?") },
            text = { Text("Angka dikunci dan tidak berubah lagi. Bisa dibuka lagi bila perlu.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmPaid = false
                    scope.launch {
                        val ok = repository.markSalesPaid(ym)
                        onMessage(if (ok) "Bonus ${RecapFormatter.monthLabel(ym)} ditandai sudah dibayar" else "Belum bisa ditandai dibayar")
                    }
                }) { Text("Ya, tandai") }
            },
            dismissButton = { TextButton(onClick = { confirmPaid = false }) { Text("Batal") } },
        )
    }
    if (confirmReopen && !relocked) {
        AlertDialog(
            onDismissRequest = { confirmReopen = false },
            title = { Text("Buka lagi?") },
            text = { Text("Angka bisa diubah lagi sampai ditandai sudah dibayar kembali.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReopen = false
                    scope.launch {
                        val ok = repository.reopenSales(ym)
                        onMessage(if (ok) "Bonus ${RecapFormatter.monthLabel(ym)} dibuka lagi" else "Gagal membuka lagi")
                    }
                }) { Text("Ya, buka") }
            },
            dismissButton = { TextButton(onClick = { confirmReopen = false }) { Text("Batal") } },
        )
    }
}
