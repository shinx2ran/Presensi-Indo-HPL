package com.indohpl.presensi.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.indohpl.presensi.data.Repository
import com.indohpl.presensi.domain.RecapFormatter
import com.indohpl.presensi.domain.SalesBonus
import com.indohpl.presensi.domain.SalesBonusConfig
import com.indohpl.presensi.domain.SalesTier
import com.indohpl.presensi.ui.theme.Red
import java.math.BigDecimal
import java.time.YearMonth
import kotlinx.coroutines.launch

/** Satu baris isian: target (juta) dan bonus per orang (Rp), disimpan sebagai teks supaya bisa diketik bebas. */
private data class TierRow(val targetJuta: String, val bonus: String)

private const val MAX_TIER_ROWS = 10
private val ONE_MILLION = BigDecimal(1_000_000)

/**
 * Target dalam juta, gaya Indonesia: titik = pemisah ribuan, koma = desimal ("1.250" = 1.250 juta, "612,5" = 612,5 juta).
 * null bila kosong, rusak, atau di luar 1..999.999 juta.
 */
private fun TierRow.targetValue(): Long? {
    val v = targetJuta.trim().replace(".", "").replace(',', '.').toBigDecimalOrNull() ?: return null
    if (v <= BigDecimal.ZERO || v > BigDecimal(999_999)) return null
    return v.multiply(ONE_MILLION).toLong()
}

/** 612_500_000 -> "612,5" (koma desimal, tanpa pemisah ribuan supaya terbaca ulang dengan benar). */
private fun jutaText(v: Long): String =
    BigDecimal(v).divide(ONE_MILLION).stripTrailingZeros().toPlainString().replace('.', ',')

private fun toRows(tiers: List<SalesTier>): List<TierRow> = tiers.map {
    TierRow(targetJuta = jutaText(it.minSales), bonus = it.perPerson.toString())
}

/** Owner: atur target penjualan, bonus per orang, dan bulan mulai berlaku. */
@Composable
fun SalesTierEditor(repository: Repository, onMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val config by repository.settingsStore.salesBonusConfig.collectAsState(SalesBonusConfig())
    var expanded by remember { mutableStateOf(false) }

    // Isian dimuat ulang hanya bila pengaturan tersimpan berubah.
    var rows by remember(config) { mutableStateOf(toRows(config.sortedTiers.ifEmpty { SalesBonus.DEFAULT_TIERS })) }
    var startMonth by remember(config) { mutableStateOf(config.startMonth) }

    val targets = rows.map { it.targetValue() }
    val bonuses = rows.map { SalesBonus.parseRupiah(it.bonus) }
    val error: String? = when {
        rows.isEmpty() -> "Minimal ada satu target."
        targets.any { it == null || it <= 0L } -> "Target harus angka lebih dari 0 (dalam juta)."
        bonuses.any { it == null || it <= 0L } -> "Bonus per orang harus angka lebih dari 0."
        targets.zipWithNext().any { (a, b) -> a!! >= b!! } -> "Target harus makin besar ke bawah (tidak boleh sama)."
        else -> null
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { expanded = !expanded }) { Text("Atur target & bonus per orang") }
        if (expanded) {
            Text(
                "Masa percobaan Desember 2026 – Mei 2027; target ditinjau ulang setelahnya.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            rows.forEachIndexed { i, row ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = row.targetJuta,
                        onValueChange = { v -> rows = rows.toMutableList().also { it[i] = row.copy(targetJuta = v.filter { c -> c.isDigit() || c == ',' || c == '.' }.take(12)) } },
                        label = { Text("Target (juta)") },
                        singleLine = true,
                        supportingText = { row.targetValue()?.let { Text("= ${SalesBonus.rp(it)}") } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = row.bonus,
                        onValueChange = { v -> rows = rows.toMutableList().also { it[i] = row.copy(bonus = SalesBonus.rupiahDigits(v)) } },
                        label = { Text("Bonus/orang (Rp)") },
                        singleLine = true,
                        supportingText = { SalesBonus.parseRupiah(row.bonus)?.let { Text("= ${SalesBonus.rp(it)}") } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { rows = rows.toMutableList().also { it.removeAt(i) } },
                        enabled = rows.size > 1,
                    ) { Icon(Icons.Filled.Delete, contentDescription = "Hapus baris") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = rows.size < MAX_TIER_ROWS,
                    onClick = {
                        val nextTarget = (targets.lastOrNull() ?: 0L) + 50_000_000L
                        rows = rows + TierRow(jutaText(nextTarget), bonuses.lastOrNull()?.toString() ?: "")
                    },
                ) { Text("+ Tambah target") }
                TextButton(onClick = {
                    rows = toRows(SalesBonus.DEFAULT_TIERS)
                    startMonth = SalesBonus.DEFAULT_START
                }) { Text("Kembalikan bawaan") }
            }

            Text("Mulai berlaku bulan", style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { startMonth = startMonth.minusMonths(1) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Bulan sebelumnya")
                }
                Text(RecapFormatter.monthLabel(startMonth), style = MaterialTheme.typography.bodyLarge)
                IconButton(onClick = { startMonth = startMonth.plusMonths(1) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Bulan berikutnya")
                }
            }

            if (error != null) {
                Text(error, style = MaterialTheme.typography.bodySmall, color = Red)
            }
            Button(
                enabled = error == null,
                onClick = {
                    val tiers = rows.map { SalesTier(it.targetValue()!!, SalesBonus.parseRupiah(it.bonus)!!) }
                    scope.launch {
                        repository.settingsStore.saveSalesBonusConfig(SalesBonusConfig(tiers, startMonth))
                        onMessage("Target bonus penjualan disimpan")
                    }
                },
            ) { Text("Simpan") }
        }
    }
}
