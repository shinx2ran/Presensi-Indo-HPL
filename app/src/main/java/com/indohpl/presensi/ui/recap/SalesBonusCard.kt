package com.indohpl.presensi.ui.recap

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.indohpl.presensi.data.Repository
import com.indohpl.presensi.domain.BonusShare
import com.indohpl.presensi.domain.SalesBonus
import com.indohpl.presensi.domain.SalesTier
import com.indohpl.presensi.ui.theme.Amber
import com.indohpl.presensi.ui.theme.Green
import com.indohpl.presensi.ui.theme.NeutralLight
import com.indohpl.presensi.ui.theme.Red
import java.time.LocalDate

private val MonthNames = listOf(
    "Januari", "Februari", "Maret", "April", "Mei", "Juni",
    "Juli", "Agustus", "September", "Oktober", "November", "Desember",
)

/** "600 jt" untuk label di bawah bar. */
private fun tickLabel(minSales: Long): String = (minSales / 1_000_000L).toString() + " jt"

/**
 * Kartu bonus penjualan toko di tab Saldo. Hanya tampilan (tidak ada tombol), hanya angka tim:
 * tidak menampilkan bonus per orang, teguran, atau bagian akhir seseorang.
 */
@Composable
fun SalesBonusCard(state: Repository.SalesBonusState, modifier: Modifier = Modifier) {
    if (!state.applies) return
    val tiers = state.config.sortedTiers
    val result = state.result
    val month = state.month
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Bonus penjualan toko",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (state.paid) {
                    Text(
                        "Sudah dicairkan",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Green,
                    )
                }
            }
            if (month == null) {
                Text(
                    "Penjualan bulan ini belum diisi.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (tiers.isNotEmpty()) {
                    Text(
                        "Target bonus per orang:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    tiers.forEach { t ->
                        Text(
                            "• ${SalesBonus.juta(t.minSales)} → ${SalesBonus.rp(t.perPerson)}/orang",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            } else {
                val asOf = runCatching { LocalDate.parse(month.asOfDate) }.getOrNull()
                // Tulis tanggal sebenarnya; "akhir bulan" hanya bila data memang sudah sampai akhir bulan.
                val untilText = when {
                    asOf == null -> ""
                    !asOf.isBefore(state.ym.atEndOfMonth()) -> "akhir bulan"
                    else -> "sampai tgl ${asOf.dayOfMonth} ${MonthNames[asOf.monthValue - 1]}"
                }
                Text(
                    "Penjualan${if (untilText.isEmpty()) "" else " $untilText"}: ${SalesBonus.rp(result.netSales)}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                // Bulan yang sudah dibayar memakai hasil terkunci; target sekarang bisa sudah diubah, jadi bar tidak ditampilkan.
                if (tiers.isNotEmpty() && !state.paid) TierProgress(netSales = result.netSales, tiers = tiers)
                result.tier?.let { t ->
                    Text(
                        "✅ ${SalesBonus.juta(t.minSales)} tercapai → ${SalesBonus.rp(t.perPerson)}/orang",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Green,
                    )
                }
                if (!state.monthFinished) {
                    val next = result.nextTier
                    if (next != null) {
                        Text(
                            "🎯 Kurang ${SalesBonus.rp(result.shortfall)} lagi ke ${SalesBonus.juta(next.minSales)} " +
                                "(${SalesBonus.rp(next.perPerson)}/orang)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Amber,
                        )
                    } else if (result.tier != null) {
                        Text(
                            "🏆 Target tertinggi tercapai!",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Green,
                        )
                    }
                }
            }
            Text(
                "Angka per orang berlaku kalau semua masuk terus. Bonus dicairkan tanggal 1 bulan berikutnya.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Bar 0 sampai target tertinggi, dengan label tiap target di ujung segmennya. */
@Composable
private fun TierProgress(netSales: Long, tiers: List<SalesTier>) {
    val top = tiers.last().minSales.coerceAtLeast(1L)
    val fraction = (netSales.toFloat() / top.toFloat()).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(RoundedCornerShape(50))
                .background(NeutralLight),
        ) {
            if (fraction > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .background(Green, RoundedCornerShape(50)),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            var prev = 0L
            tiers.forEach { t ->
                val w = ((t.minSales - prev).toFloat() / top.toFloat()).coerceAtLeast(0.01f)
                prev = t.minSales
                Text(
                    tickLabel(t.minSales),
                    modifier = Modifier.weight(w),
                    textAlign = TextAlign.End,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (netSales >= t.minSales) FontWeight.Bold else FontWeight.Normal,
                    color = if (netSales >= t.minSales) Green else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** "½ hari", "1 hari", "1,5 hari", "2 hari". */
private fun absentText(halfDays: Int): String = when (halfDays) {
    1 -> "½ hari"
    2 -> "1 hari"
    3 -> "1,5 hari"
    4 -> "2 hari"
    else -> "${halfDays / 2} hari"
}

/**
 * Satu baris status kehadiran untuk bonus penjualan satu karyawan. Hanya memakai attendanceShare dan
 * absentHalfDays (tidak pernah teguran atau jumlah bonus). Tidak tampil bila belum berlaku atau tidak ikut.
 */
@Composable
fun SalesBonusStatusLine(state: Repository.SalesBonusState?, employeeId: Long, modifier: Modifier = Modifier) {
    if (state == null || !state.applies) return
    val person = state.result.people.firstOrNull { it.employee.id == employeeId } ?: return
    if (!person.participating) return
    val (emoji, text, color) = when (person.attendanceShare) {
        BonusShare.FULL -> Triple("🟢", "masuk terus", Green)
        BonusShare.THREE_QUARTERS -> Triple("🟡", "¾ · tidak masuk ${absentText(person.absentHalfDays)}", Amber)
        BonusShare.ONE_QUARTER -> Triple("🟠", "¼ · tidak masuk ${absentText(person.absentHalfDays)}", Amber)
        BonusShare.NONE -> Triple("🔴", "tidak masuk lebih dari 2 hari", Red)
    }
    Column(modifier) {
        Text(
            "$emoji Kehadiran untuk bonus penjualan: $text",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
        if (person.attendanceShare == BonusShare.FULL && !state.monthFinished) {
            Text(
                "Jaga terus sampai akhir bulan!",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
